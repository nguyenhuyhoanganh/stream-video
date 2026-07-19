package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.JoinResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class JoinController {
    private final MeetingService meetingService;

    @PostMapping("/{code}/join")
    public JoinResponse join(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable String code) {
        return meetingService.join(code, user.id());
    }
}
