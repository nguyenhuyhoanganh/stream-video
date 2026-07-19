package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantSessionRepository extends JpaRepository<ParticipantSession, UUID> {
    Optional<ParticipantSession> findFirstByMeetingIdAndIdentityAndLeftAtIsNullOrderByJoinedAtDesc(
            UUID meetingId, String identity);
    List<ParticipantSession> findByMeetingIdAndLeftAtIsNull(UUID meetingId);
}
