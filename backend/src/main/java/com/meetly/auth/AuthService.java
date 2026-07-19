package com.meetly.auth;

import com.meetly.common.ApiException;
import com.meetly.common.AuthProperties;
import com.meetly.common.ErrorCode;
import com.meetly.user.User;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    public record TokenPair(String accessToken, String rawRefreshToken) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public User register(String email, String password, String fullName) {
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_TAKEN, "Email is already registered");
        }
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setFullName(fullName);
        return users.save(u);
    }

    @Transactional(readOnly = true)
    public User authenticate(String email, String password) {
        return users.findByEmail(email)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_CREDENTIALS, "Incorrect email or password"));
    }

    @Transactional
    public TokenPair issueTokens(User user) {
        byte[] buf = new byte[48];
        random.nextBytes(buf);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256(raw));
        rt.setExpiresAt(Instant.now().plus(props.refreshTtl()));
        refreshTokens.save(rt);

        return new TokenPair(jwtService.generateAccessToken(user.getId(), user.getEmail()), raw);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // noRollbackFor: the reuse-detection branch both revokes sessions and throws 401.
    // With the default rollback the revocation is undone along with the exception,
    // leaving the thief's session intact — the fix would do nothing.
    @Transactional(noRollbackFor = ApiException.class)
    public User rotate(String rawRefreshToken) {
        RefreshToken current = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .orElseThrow(this::invalidRefreshToken);

        if (current.getRevokedAt() != null) {
            // A revoked token being presented again means either theft or a stale copy.
            // We cannot tell them apart, so take the safe path: revoke every session of
            // the user and force a fresh sign-in (OWASP recommendation).
            int revoked = revokeAllSessions(current.getUserId());
            log.warn("Refresh token reuse detected for user {} — revoked {} sessions",
                    current.getUserId(), revoked);
            throw invalidRefreshToken();
        }
        if (!current.isActive()) throw invalidRefreshToken();   // expired naturally

        current.setRevokedAt(Instant.now());
        return users.findById(current.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_CREDENTIALS, "Account no longer exists"));
    }

    /** Revokes every live refresh token of the user. Returns how many were revoked. */
    @Transactional
    public int revokeAllSessions(UUID userId) {
        List<RefreshToken> active = refreshTokens.findByUserIdAndRevokedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(t -> t.setRevokedAt(now));
        return active.size();
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_REFRESH_TOKEN, "Invalid refresh token");
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(rt -> rt.setRevokedAt(Instant.now()));
    }
}
