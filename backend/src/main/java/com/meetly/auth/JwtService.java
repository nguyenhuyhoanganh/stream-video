package com.meetly.auth;

import com.meetly.common.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    public record AccessTokenClaims(UUID userId, String email) {}

    private final SecretKey key;
    private final AuthProperties props;

    public JwtService(AuthProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException when the token is malformed, expired or badly signed */
    public AccessTokenClaims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new AccessTokenClaims(UUID.fromString(c.getSubject()), c.get("email", String.class));
    }

    public String generateGuestToken(UUID meetingId, String identity, String displayName,
                                     Instant expiresAt) {
        return Jwts.builder()
                .subject(identity)
                .claim("typ", "guest")
                .claim("mtg", meetingId.toString())
                .claim("name", displayName)
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** Returns an AuthenticatedUser (access token) or a GuestUser (guest token). */
    public Object parsePrincipal(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        if ("guest".equals(c.get("typ", String.class))) {
            return new GuestUser(c.getSubject(), c.get("name", String.class),
                    UUID.fromString(c.get("mtg", String.class)));
        }
        return new AuthenticatedUser(UUID.fromString(c.getSubject()), c.get("email", String.class));
    }
}
