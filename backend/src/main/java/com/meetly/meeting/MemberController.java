package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.AddMemberRequest;
import com.meetly.meeting.MeetingDtos.MemberResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable UUID meetingId,
                              @Valid @RequestBody AddMemberRequest req) {
        MeetingMember mm = memberService.add(meetingId, user.id(), req.email(), req.role());
        return new MemberResponse(mm.getId(), mm.getInvitedEmail(), mm.getRole());
    }

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID meetingId) {
        return memberService.list(meetingId, user.id()).stream()
                .map(mm -> new MemberResponse(mm.getId(), mm.getInvitedEmail(), mm.getRole()))
                .toList();
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID meetingId, @PathVariable UUID memberId) {
        memberService.remove(meetingId, user.id(), memberId);
        return ResponseEntity.noContent().build();
    }
}
