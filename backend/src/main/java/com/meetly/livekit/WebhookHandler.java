package com.meetly.livekit;

import com.meetly.meeting.*;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookHandler {
    private final MeetingRepository meetings;
    private final ParticipantSessionRepository sessions;
    private final StringRedisTemplate redis;

    @Transactional
    public void handle(LivekitWebhook.WebhookEvent event) {
        Boolean first = redis.opsForValue()
                .setIfAbsent("webhook:evt:" + event.getId(), "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(first)) {
            log.debug("Duplicate webhook event {} ignored", event.getId());
            return;
        }
        String roomName = event.getRoom().getName();
        Meeting meeting = meetings.findByCode(roomName).orElse(null);
        if (meeting == null) {
            log.warn("Webhook for unknown room {}", roomName);
            return;
        }
        switch (event.getEvent()) {
            case "room_started" -> {
                if (meeting.getStatus() == MeetingStatus.SCHEDULED) {
                    meeting.setStatus(MeetingStatus.LIVE);
                }
            }
            case "room_finished" -> {
                meeting.setStatus(MeetingStatus.ENDED);
                sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())
                        .forEach(s -> s.setLeftAt(Instant.now()));
            }
            case "participant_joined" -> {
                ParticipantSession s = new ParticipantSession();
                s.setMeetingId(meeting.getId());
                s.setIdentity(event.getParticipant().getIdentity());
                s.setDisplayName(event.getParticipant().getName());
                s.setJoinedAt(Instant.now());
                sessions.save(s);
            }
            case "participant_left" -> sessions
                    .findFirstByMeetingIdAndIdentityAndLeftAtIsNullOrderByJoinedAtDesc(
                            meeting.getId(), event.getParticipant().getIdentity())
                    .ifPresent(s -> s.setLeftAt(Instant.now()));
            default -> log.debug("Unhandled webhook event {}", event.getEvent());
        }
        meeting.setUpdatedAt(Instant.now());
    }
}
