package com.meetly.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class MeetingDtos {
    public record CreateMeetingRequest(@NotBlank @Size(max = 255) String title,
                                       String description,
                                       Instant scheduledStartAt,
                                       Instant scheduledEndAt) {}

    public record UpdateMeetingRequest(@Size(max = 255) String title,
                                       String description,
                                       Instant scheduledStartAt,
                                       Instant scheduledEndAt) {}

    public record JoinResponse(String livekitUrl, String livekitToken, String role) {}

    public record MeetingResponse(UUID id, String code, String title, String description,
                                  UUID hostId, Instant scheduledStartAt, Instant scheduledEndAt,
                                  String status, String roomType) {
        static MeetingResponse from(Meeting m) {
            return new MeetingResponse(m.getId(), m.getCode(), m.getTitle(), m.getDescription(),
                    m.getHostId(), m.getScheduledStartAt(), m.getScheduledEndAt(),
                    m.getStatus().name(), m.getRoomType().name());
        }
    }
}
