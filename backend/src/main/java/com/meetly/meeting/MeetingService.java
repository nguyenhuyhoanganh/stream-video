package com.meetly.meeting;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.livekit.LiveKitTokenService;
import com.meetly.meeting.MeetingDtos.CreateMeetingRequest;
import com.meetly.meeting.MeetingDtos.UpdateMeetingRequest;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MeetingService {
    private final MeetingRepository meetings;
    private final MeetingCodeGenerator codeGenerator;
    private final LiveKitTokenService liveKitTokenService;
    private final UserRepository users;
    private final MemberService memberService;
    private final com.meetly.auth.JwtService jwtService;

    @Transactional
    public Meeting create(UUID hostId, CreateMeetingRequest req) {
        Meeting m = new Meeting();
        m.setTitle(req.title());
        m.setDescription(req.description());
        m.setHostId(hostId);
        m.setScheduledStartAt(req.scheduledStartAt() != null ? req.scheduledStartAt() : Instant.now());
        m.setScheduledEndAt(req.scheduledEndAt());
        m.setRoomType(req.roomType() != null ? req.roomType() : RoomType.MEETING);
        m.setCode(uniqueCode());
        return meetings.save(m);
    }

    private String uniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = codeGenerator.newCode();
            if (!meetings.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique room code after 5 attempts");
    }

    @Transactional(readOnly = true)
    public Meeting getByCode(String code) {
        return meetings.findByCode(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
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

    @Transactional
    public MeetingDtos.JoinResponse join(String code, UUID userId) {
        Meeting m = getByCode(code);
        var user = users.findById(userId).orElseThrow();
        MeetingRole role = memberService.resolveRole(m, userId, user.getEmail())
                .orElseGet(() -> {
                    if (m.getRoomType() == RoomType.WEBINAR) return MeetingRole.ATTENDEE;
                    throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                            "You have not been invited to this meeting");
                });
        validateJoinable(m, role == MeetingRole.HOST);
        String token = liveKitTokenService.createToken(
                m.getCode(), userId.toString(), user.getFullName(), role, tokenExpiry(m));
        return new MeetingDtos.JoinResponse(m.getId(), liveKitTokenService.wsUrl(), token,
                role.name(), null);
    }

    @Transactional(readOnly = true)
    public MeetingDtos.JoinResponse joinAsGuest(String code, String displayName) {
        Meeting m = getByCode(code);
        if (displayName == null || displayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.DISPLAY_NAME_REQUIRED,
                    "Please enter a display name");
        }
        if (m.getRoomType() != RoomType.WEBINAR) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.GUEST_MEETING_FORBIDDEN,
                    "This meeting requires you to sign in");
        }
        validateJoinable(m, false);
        String identity = "guest:" + UUID.randomUUID();
        Instant expiresAt = tokenExpiry(m);
        String lkToken = liveKitTokenService.createToken(
                m.getCode(), identity, displayName, MeetingRole.ATTENDEE, expiresAt);
        String chatToken = jwtService.generateGuestToken(m.getId(), identity, displayName, expiresAt);
        return new MeetingDtos.JoinResponse(m.getId(), liveKitTokenService.wsUrl(), lkToken,
                MeetingRole.ATTENDEE.name(), chatToken);
    }

    void validateJoinable(Meeting m, boolean isHost) {
        if (m.getStatus() == MeetingStatus.ENDED || m.getStatus() == MeetingStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.MEETING_ENDED,
                    "This meeting has ended or was cancelled");
        }
        Instant earliestJoin = m.getScheduledStartAt().minus(15, ChronoUnit.MINUTES);
        if (!isHost && Instant.now().isBefore(earliestJoin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.MEETING_NOT_STARTED,
                    "This meeting has not started yet (you may join up to 15 minutes early)");
        }
    }

    /**
     * The spec sets token TTL to "scheduled_end_at + 2h". For a meeting that overruns or
     * starts late that moment may already have passed, and join would answer 200 with a
     * dead token, leaving the user with an unexplained connection failure. Keep a floor
     * of one hour from issue time.
     */
    Instant tokenExpiry(Meeting m) {
        Instant perSchedule = (m.getScheduledEndAt() != null
                ? m.getScheduledEndAt() : Instant.now().plus(4, ChronoUnit.HOURS))
                .plus(2, ChronoUnit.HOURS);
        Instant floor = Instant.now().plus(1, ChronoUnit.HOURS);
        return perSchedule.isAfter(floor) ? perSchedule : floor;
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
