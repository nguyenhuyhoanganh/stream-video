package com.meetly.livekit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveKitTokenServiceTest {
    private static final String SECRET = "meetly_dev_secret_0123456789abcdef";
    private final LiveKitTokenService service = new LiveKitTokenService(
            new LiveKitProperties("devkey", SECRET, "ws://localhost:7880", "http://localhost:7880"));

    @Test
    void speakerCanPublish() {
        String jwt = service.createToken("abc-defg-hij", "user-1", "Anh",
                com.meetly.meeting.MeetingRole.SPEAKER, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("roomAdmin")).isNull();
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    void attendeeCannotPublish() {
        String jwt = service.createToken("abc-defg-hij", "guest:123", "Guest",
                com.meetly.meeting.MeetingRole.ATTENDEE, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(false);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
    }

    @Test
    void hostHasRoomAdmin() {
        String jwt = service.createToken("abc-defg-hij", "user-2", "Host",
                com.meetly.meeting.MeetingRole.HOST, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("roomAdmin")).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String jwt) {
        return (Map<String, Object>) Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload().get("video", Map.class);
    }
}
