package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingMemberRepository extends JpaRepository<MeetingMember, UUID> {
    Optional<MeetingMember> findByMeetingIdAndUserId(UUID meetingId, UUID userId);
    Optional<MeetingMember> findByMeetingIdAndInvitedEmail(UUID meetingId, String invitedEmail);
    List<MeetingMember> findByMeetingId(UUID meetingId);
}
