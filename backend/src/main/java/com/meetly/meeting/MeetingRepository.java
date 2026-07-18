package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
    Optional<Meeting> findByCode(String code);
    List<Meeting> findByHostIdOrderByScheduledStartAtDesc(UUID hostId);
    boolean existsByCode(String code);
}
