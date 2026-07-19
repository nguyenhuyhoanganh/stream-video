package com.meetly.recording;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingRepository extends JpaRepository<Recording, UUID> {
    List<Recording> findByMeetingIdOrderByStartedAtDesc(UUID meetingId);
    Optional<Recording> findByEgressId(String egressId);
    boolean existsByMeetingIdAndStatusIn(UUID meetingId, Collection<RecordingStatus> statuses);
}
