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

    // noRollbackFor: nhánh phát hiện tái sử dụng vừa thu hồi phiên vừa ném 401.
    // Nếu để rollback mặc định thì việc thu hồi bị huỷ theo exception — kẻ trộm
    // vẫn giữ nguyên phiên, tức là bản vá không có tác dụng gì.
    @Transactional(noRollbackFor = ApiException.class)
    public User rotate(String rawRefreshToken) {
        RefreshToken current = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .orElseThrow(this::invalidRefreshToken);

        if (current.getRevokedAt() != null) {
            // Token đã thu hồi mà vẫn có người dùng lại: hoặc token bị đánh cắp, hoặc
            // bản sao cũ còn sót. Không phân biệt được nên xử lý theo hướng an toàn —
            // thu hồi toàn bộ phiên của user, buộc đăng nhập lại (khuyến nghị OWASP).
            int revoked = revokeAllSessions(current.getUserId());
            log.warn("Phát hiện tái sử dụng refresh token của user {} — đã thu hồi {} phiên",
                    current.getUserId(), revoked);
            throw invalidRefreshToken();
        }
        if (!current.isActive()) throw invalidRefreshToken();   // hết hạn tự nhiên

        current.setRevokedAt(Instant.now());
        return users.findById(current.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_CREDENTIALS, "Account no longer exists"));
    }

    /** Thu hồi mọi refresh token còn sống của user. Trả về số phiên bị thu hồi. */
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
