package com.meetly.recording;

import java.time.Instant;
import java.util.UUID;

public class RecordingDtos {
    public record RecordingDto(UUID id, String status, Instant startedAt, Instant endedAt,
                               Long durationSeconds) {
        static RecordingDto from(Recording r) {
            return new RecordingDto(r.getId(), r.getStatus().name(), r.getStartedAt(),
                    r.getEndedAt(), r.getDurationSeconds());
        }
    }

    public record PlaybackUrlDto(String url) {}
}
