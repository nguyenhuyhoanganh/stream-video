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
    private final com.meetly.recording.RecordingRepository recordings;
    private final StringRedisTemplate redis;

    @Transactional
    public void handle(LivekitWebhook.WebhookEvent event) {
        Boolean first = redis.opsForValue()
                .setIfAbsent("webhook:evt:" + event.getId(), "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(first)) {
            log.debug("Duplicate webhook event {} ignored", event.getId());
            return;
        }
        // egress events xử lý theo egressId, không cần meeting lookup
        // (có thể đến sau khi room đã đóng)
        if (event.getEvent().startsWith("egress_")) {
            handleEgress(event);
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
                // Host được vào sớm tùy ý (validateJoinable bỏ qua giới hạn 15 phút cho host).
                // Nếu phòng đóng TRƯỚC giờ hẹn thì đó là buổi vào thử, không phải buổi họp đã
                // diễn ra — đánh ENDED sẽ giết luôn cuộc họp chưa kịp bắt đầu.
                boolean daToiGioHop = !Instant.now().isBefore(meeting.getScheduledStartAt());
                meeting.setStatus(daToiGioHop ? MeetingStatus.ENDED : MeetingStatus.SCHEDULED);
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

    private void handleEgress(LivekitWebhook.WebhookEvent event) {
        switch (event.getEvent()) {
            case "egress_started", "egress_updated" -> recordings
                    .findByEgressId(event.getEgressInfo().getEgressId())
                    .ifPresent(r -> {
                        if (r.getStatus() == com.meetly.recording.RecordingStatus.STARTING) {
                            r.setStatus(com.meetly.recording.RecordingStatus.ACTIVE);
                        }
                    });
            case "egress_ended" -> recordings
                    .findByEgressId(event.getEgressInfo().getEgressId())
                    .ifPresent(r -> {
                        boolean ok = event.getEgressInfo().getStatus()
                                == livekit.LivekitEgress.EgressStatus.EGRESS_COMPLETE;
                        r.setStatus(ok ? com.meetly.recording.RecordingStatus.COMPLETED
                                : com.meetly.recording.RecordingStatus.FAILED);
                        r.setEndedAt(Instant.now());
                        if (event.getEgressInfo().getFileResultsCount() > 0) {
                            var file = event.getEgressInfo().getFileResults(0);
                            r.setDurationSeconds(file.getDuration() / 1_000_000_000L); // ns → s
                            r.setSizeBytes(file.getSize());
                        }
                    });
            default -> log.debug("Unhandled egress event {}", event.getEvent());
        }
    }
}
