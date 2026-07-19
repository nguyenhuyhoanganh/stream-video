package com.meetly.chat;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.auth.GuestUser;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import com.meetly.meeting.MemberService;
import com.meetly.meeting.RoomType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChatAccessGuard {
    private final MeetingRepository meetings;
    private final MemberService memberService;

    /**
     * Chat permission of a principal for a meeting — used by SUBSCRIBE, sending and history.
     * Guest: only the meeting in its token. User: host/member, or any WEBINAR (open) room.
     * @throws ApiException 404 when the meeting does not exist; 403 when access is denied.
     */
    @Transactional
    public Meeting check(Object principal, UUID meetingId) {
        Meeting meeting = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        if (principal instanceof GuestUser g) {
            if (!g.meetingId().equals(meetingId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "Guest token does not belong to this meeting");
            }
            return meeting;
        }
        if (principal instanceof AuthenticatedUser u) {
            boolean allowed = meeting.getRoomType() == RoomType.WEBINAR
                    || memberService.resolveRole(meeting, u.id(), u.email()).isPresent();
            if (!allowed) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "You are not part of this meeting");
            }
            return meeting;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                "Could not authenticate");
    }
}
