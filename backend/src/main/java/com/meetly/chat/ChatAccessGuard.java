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
     * Quyền chat của một principal với một meeting — dùng cho SUBSCRIBE, gửi tin, đọc history.
     * Guest: chỉ phòng trong token. User: host/member, hoặc phòng WEBINAR (mở).
     * @throws ApiException 404 nếu meeting không tồn tại; 403 nếu không có quyền.
     */
    @Transactional
    public Meeting check(Object principal, UUID meetingId) {
        Meeting meeting = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (principal instanceof GuestUser g) {
            if (!g.meetingId().equals(meetingId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "Guest token không thuộc phòng này");
            }
            return meeting;
        }
        if (principal instanceof AuthenticatedUser u) {
            boolean allowed = meeting.getRoomType() == RoomType.WEBINAR
                    || memberService.resolveRole(meeting, u.id(), u.email()).isPresent();
            if (!allowed) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "Bạn không thuộc phòng họp này");
            }
            return meeting;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                "Không xác thực được");
    }
}
