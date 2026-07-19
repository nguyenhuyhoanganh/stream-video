package com.meetly.recording;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.recording.RecordingDtos.PlaybackUrlDto;
import com.meetly.recording.RecordingDtos.RecordingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RecordingController {
    private final RecordingService recordingService;

    @PostMapping("/meetings/{meetingId}/recordings/start")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordingDto start(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable UUID meetingId) {
        return RecordingDto.from(recordingService.start(meetingId, user.id()));
    }

    @PostMapping("/meetings/{meetingId}/recordings/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId) {
        recordingService.stop(meetingId, user.id());
    }

    @GetMapping("/meetings/{meetingId}/recordings")
    public List<RecordingDto> list(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID meetingId) {
        return recordingService.list(meetingId, user.id()).stream()
                .map(RecordingDto::from).toList();
    }

    @GetMapping("/recordings/{recordingId}/playback-url")
    public PlaybackUrlDto playbackUrl(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID recordingId) {
        return new PlaybackUrlDto(recordingService.playbackUrl(recordingId, user.id()));
    }
}
