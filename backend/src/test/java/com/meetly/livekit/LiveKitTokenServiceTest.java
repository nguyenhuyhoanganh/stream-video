package com.meetly.livekit;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveKitTokenServiceTest {
    private static final String SECRET = "meetly_dev_secret_0123456789abcdef";
    private final LiveKitTokenService service = new LiveKitTokenService(
            new LiveKitProperties("devkey", SECRET, "ws://localhost:7880"));

    @Test
    @SuppressWarnings("unchecked")
    void speakerTokenGrants() {
        UUID userId = UUID.randomUUID();
        String jwt = service.createToken("abc-defg-hij", userId, "Anh",
                true, false, Instant.now().plus(2, ChronoUnit.HOURS));

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("devkey");
        Map<String, Object> video = claims.get("video", Map.class);
        assertThat(video.get("room")).isEqualTo("abc-defg-hij");
        assertThat(video.get("roomJoin")).isEqualTo(true);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostTokenHasRoomAdmin() {
        String jwt = service.createToken("abc-defg-hij", UUID.randomUUID(), "Host",
                true, true, Instant.now().plus(2, ChronoUnit.HOURS));
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();
        Map<String, Object> video = claims.get("video", Map.class);
        assertThat(video.get("roomAdmin")).isEqualTo(true);
    }
}
