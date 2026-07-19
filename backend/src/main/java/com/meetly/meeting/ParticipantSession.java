package com.meetly.meeting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "participant_sessions")
@Getter @Setter @NoArgsConstructor
public class ParticipantSession {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(nullable = false, length = 100)
    private String identity;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;
}
