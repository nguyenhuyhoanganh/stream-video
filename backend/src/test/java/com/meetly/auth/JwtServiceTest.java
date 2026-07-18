package com.meetly.auth;

import com.meetly.common.AuthProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties(
                "meetly_dev_jwt_secret_min_32_chars_0123",
                Duration.ofMinutes(15), Duration.ofDays(14), false);
        jwtService = new JwtService(props);
    }

    @Test
    void roundTrip() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "a@b.c");
        JwtService.AccessTokenClaims claims = jwtService.parse(token);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.email()).isEqualTo("a@b.c");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongSignature() {
        AuthProperties other = new AuthProperties(
                "another_secret_that_is_long_enough_9999",
                Duration.ofMinutes(15), Duration.ofDays(14), false);
        String forged = new JwtService(other).generateAccessToken(UUID.randomUUID(), "a@b.c");
        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(JwtException.class);
    }
}
