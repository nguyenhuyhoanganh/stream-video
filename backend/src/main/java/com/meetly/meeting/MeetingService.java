package com.meetly.meeting;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.MeetingDtos.CreateMeetingRequest;
import com.meetly.meeting.MeetingDtos.UpdateMeetingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MeetingService {
    private final MeetingRepository meetings;
    private final MeetingCodeGenerator codeGenerator;

    @Transactional
    public Meeting create(UUID hostId, CreateMeetingRequest req) {
        Meeting m = new Meeting();
        m.setTitle(req.title());
        m.setDescription(req.description());
        m.setHostId(hostId);
        m.setScheduledStartAt(req.scheduledStartAt() != null ? req.scheduledStartAt() : Instant.now());
        m.setScheduledEndAt(req.scheduledEndAt());
        m.setCode(uniqueCode());
        return meetings.save(m);
    }

    private String uniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = codeGenerator.newCode();
            if (!meetings.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Không sinh được mã phòng duy nhất sau 5 lần");
    }

    @Transactional(readOnly = true)
    public Meeting getByCode(String code) {
        return meetings.findByCode(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
    }

    @Transactional(readOnly = true)
    public List<Meeting> listMine(UUID hostId) {
        return meetings.findByHostIdOrderByScheduledStartAtDesc(hostId);
    }

    @Transactional
    public Meeting update(UUID meetingId, UUID actorId, UpdateMeetingRequest req) {
        Meeting m = requireHost(meetingId, actorId);
        if (req.title() != null) m.setTitle(req.title());
        if (req.description() != null) m.setDescription(req.description());
        if (req.scheduledStartAt() != null) m.setScheduledStartAt(req.scheduledStartAt());
        if (req.scheduledEndAt() != null) m.setScheduledEndAt(req.scheduledEndAt());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    @Transactional
    public void cancel(UUID meetingId, UUID actorId) {
        Meeting m = requireHost(meetingId, actorId);
        m.setStatus(MeetingStatus.CANCELLED);
        m.setUpdatedAt(Instant.now());
    }

    private Meeting requireHost(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (!m.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    ErrorCode.NOT_MEETING_HOST, "Chỉ host mới được thao tác");
        }
        return m;
    }
}
