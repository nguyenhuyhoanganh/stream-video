package com.meetly.meeting;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MeetingRepository meetings;
    private final MeetingMemberRepository members;
    private final UserRepository users;

    /** HOST when they own the room; the member role when listed in meeting_members; empty otherwise. */
    @Transactional
    public Optional<MeetingRole> resolveRole(Meeting meeting, UUID userId, String email) {
        if (meeting.getHostId().equals(userId)) return Optional.of(MeetingRole.HOST);
        Optional<MeetingMember> byUser = members.findByMeetingIdAndUserId(meeting.getId(), userId);
        if (byUser.isPresent()) return byUser.map(MeetingMember::getRole);
        Optional<MeetingMember> byEmail = members.findByMeetingIdAndInvitedEmail(meeting.getId(), email);
        byEmail.ifPresent(mm -> mm.setUserId(userId)); // backfill on first join
        return byEmail.map(MeetingMember::getRole);
    }

    @Transactional
    public MeetingMember add(UUID meetingId, UUID actorId, String email, MeetingRole role) {
        Meeting m = requireHost(meetingId, actorId);
        if (role == MeetingRole.HOST) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Cannot assign the HOST role to a member");
        }
        // duplicate invite used to insert a second row and break unique (meeting_id,user_id) → 500
        if (members.findByMeetingIdAndInvitedEmail(m.getId(), email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.MEMBER_ALREADY_ADDED,
                    "This email is already a member of the meeting");
        }
        MeetingMember mm = new MeetingMember();
        mm.setMeetingId(m.getId());
        mm.setInvitedEmail(email);
        users.findByEmail(email).ifPresent(u -> {
            if (members.findByMeetingIdAndUserId(m.getId(), u.getId()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.MEMBER_ALREADY_ADDED,
                        "This user is already a member of the meeting");
            }
            mm.setUserId(u.getId());
        });
        mm.setRole(role != null ? role : MeetingRole.ATTENDEE);
        mm.setInvitedBy(actorId);
        return members.save(mm);
    }

    @Transactional(readOnly = true)
    public List<MeetingMember> list(UUID meetingId, UUID actorId) {
        requireHost(meetingId, actorId);
        return members.findByMeetingId(meetingId);
    }

    @Transactional
    public void remove(UUID meetingId, UUID actorId, UUID memberId) {
        requireHost(meetingId, actorId);
        // memberId must belong to this meeting: without the check, the host of meeting A
        // could delete a member of meeting B just by changing meetingId in the URL
        MeetingMember mm = members.findById(memberId)
                .filter(m -> m.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.PARTICIPANT_NOT_FOUND,
                        "Member not found in this meeting"));
        members.delete(mm);
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
