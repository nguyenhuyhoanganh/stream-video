package com.meetly.recording;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recordings")
@Getter @Setter @NoArgsConstructor
public class Recording {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "egress_id", nullable = false, unique = true, length = 100)
    private String egressId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingStatus status = RecordingStatus.STARTING;

    @Column(name = "s3_key", length = 500)
    private String s3Key;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;
}
