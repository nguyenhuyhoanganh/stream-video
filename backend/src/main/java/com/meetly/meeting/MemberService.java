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

    /** HOST nếu là chủ phòng; role member nếu có trong meeting_members; empty nếu người lạ. */
    @Transactional
    public Optional<MeetingRole> resolveRole(Meeting meeting, UUID userId, String email) {
        if (meeting.getHostId().equals(userId)) return Optional.of(MeetingRole.HOST);
        Optional<MeetingMember> byUser = members.findByMeetingIdAndUserId(meeting.getId(), userId);
        if (byUser.isPresent()) return byUser.map(MeetingMember::getRole);
        Optional<MeetingMember> byEmail = members.findByMeetingIdAndInvitedEmail(meeting.getId(), email);
        byEmail.ifPresent(mm -> mm.setUserId(userId)); // backfill lần đầu join
        return byEmail.map(MeetingMember::getRole);
    }

    @Transactional
    public MeetingMember add(UUID meetingId, UUID actorId, String email, MeetingRole role) {
        Meeting m = requireHost(meetingId, actorId);
        if (role == MeetingRole.HOST) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Không thể gán role HOST cho member");
        }
        MeetingMember mm = new MeetingMember();
        mm.setMeetingId(m.getId());
        mm.setInvitedEmail(email);
        users.findByEmail(email).ifPresent(u -> mm.setUserId(u.getId()));
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
        members.deleteById(memberId);
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
