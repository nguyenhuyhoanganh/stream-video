package com.meetly.recording;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordingService {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final RecordingRepository recordings;
    private final MeetingRepository meetings;
    private final com.meetly.meeting.MeetingMemberRepository members;
    private final EgressClient egressClient;
    private final StorageService storageService;

    @Transactional
    public Recording start(UUID meetingId, UUID actorId) {
        Meeting m = requireHost(meetingId, actorId);
        if (!m.isAllowRecording()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_NOT_ALLOWED,
                    "Recording is not allowed for this meeting");
        }
        if (recordings.existsByMeetingIdAndStatusIn(meetingId,
                List.of(RecordingStatus.STARTING, RecordingStatus.ACTIVE))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_ALREADY_ACTIVE,
                    "A recording is already in progress");
        }
        String s3Key = "recordings/%s/%s.mp4".formatted(m.getCode(), TS.format(Instant.now()));
        String egressId = egressClient.startRoomComposite(m.getCode(), s3Key);

        Recording rec = new Recording();
        rec.setMeetingId(meetingId);
        rec.setEgressId(egressId);
        rec.setS3Key(s3Key);
        rec.setStartedBy(actorId);
        return recordings.save(rec);
    }

    @Transactional
    public void stop(UUID meetingId, UUID actorId) {
        requireHost(meetingId, actorId);
        recordings.findByMeetingIdOrderByStartedAtDesc(meetingId).stream()
                .filter(r -> r.getStatus() == RecordingStatus.STARTING
                        || r.getStatus() == RecordingStatus.ACTIVE)
                .findFirst()
                .ifPresent(r -> egressClient.stop(r.getEgressId()));
    }

    @Transactional(readOnly = true)
    public List<Recording> list(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        requireHostOrMember(m, actorId);   // spec 4.6: chỉ host/member của meeting
        return recordings.findByMeetingIdOrderByStartedAtDesc(meetingId);
    }

    @Transactional(readOnly = true)
    public String playbackUrl(UUID recordingId, UUID actorId) {
        Recording rec = recordings.findById(recordingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.RECORDING_NOT_FOUND, "Recording not found"));
        Meeting m = meetings.findById(rec.getMeetingId()).orElseThrow();
        requireHostOrMember(m, actorId);   // spec 4.6: chỉ host/member của meeting
        if (rec.getStatus() != RecordingStatus.COMPLETED || rec.getS3Key() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_NOT_READY,
                    "Recording is not ready yet");
        }
        return storageService.presignGetUrl(rec.getS3Key(), Duration.ofHours(1));
    }

    private void requireHostOrMember(Meeting m, UUID actorId) {
        boolean allowed = m.getHostId().equals(actorId)
                || members.findByMeetingIdAndUserId(m.getId(), actorId).isPresent();
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                    "You are not part of this meeting");
        }
    }

    private Meeting requireHost(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        if (!m.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    ErrorCode.NOT_MEETING_HOST, "Only the host can perform this action");
        }
        return m;
    }
}
