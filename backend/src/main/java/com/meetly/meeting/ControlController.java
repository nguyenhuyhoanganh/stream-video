package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.livekit.RoomControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
@RequiredArgsConstructor
public class ControlController {
    private final MeetingRepository meetings;
    private final MeetingMemberRepository members;
    private final RoomControlService roomControl;

    @PostMapping("/participants/{identity}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId, @PathVariable String identity) {
        Meeting m = requireHost(meetingId, user.id());
        roomControl.muteAllAudio(m.getCode(), identity);
    }

    @PostMapping("/participants/{identity}/promote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void promote(@AuthenticationPrincipal AuthenticatedUser user,
                        @PathVariable UUID meetingId, @PathVariable String identity) {
        changeRole(user, meetingId, identity, MeetingRole.SPEAKER);
    }

    @PostMapping("/participants/{identity}/demote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void demote(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID meetingId, @PathVariable String identity) {
        changeRole(user, meetingId, identity, MeetingRole.ATTENDEE);
    }

    @PostMapping("/participants/{identity}/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId, @PathVariable String identity) {
        Meeting m = requireHost(meetingId, user.id());
        roomControl.kick(m.getCode(), identity);
    }

    @PostMapping("/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void end(@AuthenticationPrincipal AuthenticatedUser user,
                    @PathVariable UUID meetingId) {
        Meeting m = requireHost(meetingId, user.id());
        m.setStatus(MeetingStatus.ENDED);
        m.setUpdatedAt(Instant.now());
        roomControl.endRoom(m.getCode());
    }

    private void changeRole(AuthenticatedUser user, UUID meetingId, String identity,
                            MeetingRole newRole) {
        Meeting m = requireHost(meetingId, user.id());
        // identity của user đăng nhập = userId; guest (guest:*) không promote được
        if (!identity.startsWith("guest:")) {
            UUID targetUserId = parseUserIdentity(identity);
            MeetingMember mm = members.findByMeetingIdAndUserId(meetingId, targetUserId)
                    .orElseGet(() -> {
                        // CHECK constraint là (user_id OR invited_email) — có userId là đủ
                        MeetingMember fresh = new MeetingMember();
                        fresh.setMeetingId(meetingId);
                        fresh.setUserId(targetUserId);
                        fresh.setInvitedBy(user.id());
                        return fresh;
                    });
            mm.setRole(newRole);
            members.save(mm);
        }
        roomControl.setRole(m.getCode(), identity, newRole);
    }

    /** identity rác từ client là lỗi 400, không phải 500. */
    private UUID parseUserIdentity(String identity) {
        try {
            return UUID.fromString(identity);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Invalid identity: expected userId or guest:<uuid>");
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
