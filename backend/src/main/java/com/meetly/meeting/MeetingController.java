package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {
    private final MeetingService meetingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody CreateMeetingRequest req) {
        return MeetingResponse.from(meetingService.create(user.id(), req));
    }

    @GetMapping
    public List<MeetingResponse> listMine(@AuthenticationPrincipal AuthenticatedUser user) {
        return meetingService.listMine(user.id()).stream().map(MeetingResponse::from).toList();
    }

    @GetMapping("/{code}")
    public MeetingResponse getByCode(@PathVariable String code) {
        return MeetingResponse.from(meetingService.getByCode(code));
    }

    @PatchMapping("/{id}")
    public MeetingResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody UpdateMeetingRequest req) {
        return MeetingResponse.from(meetingService.update(id, user.id(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID id) {
        meetingService.cancel(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
