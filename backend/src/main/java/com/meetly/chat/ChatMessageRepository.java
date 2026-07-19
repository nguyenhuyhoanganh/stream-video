package com.meetly.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID meetingId, Instant before, Pageable pageable);
    List<ChatMessage> findByMeetingIdAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID meetingId, Instant after);
}
