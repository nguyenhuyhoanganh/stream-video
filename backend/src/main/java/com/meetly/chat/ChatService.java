package com.meetly.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetly.auth.AuthenticatedUser;
import com.meetly.auth.GuestUser;
import com.meetly.chat.ChatDtos.ChatEvent;
import com.meetly.chat.ChatDtos.ChatMessageDto;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatMessageRepository chatMessages;
    private final MeetingRepository meetings;
    private final UserRepository users;
    private final ChatAccessGuard accessGuard;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveAndPublish(UUID meetingId, Object principal, String content,
                               ChatMessageType type) {
        accessGuard.check(principal, meetingId);   // 404/403 nếu không thuộc phòng
        String identity;
        String displayName;
        if (principal instanceof GuestUser g) {
            identity = g.identity();
            displayName = g.displayName();
        } else {
            AuthenticatedUser u = (AuthenticatedUser) principal;
            identity = u.id().toString();
            displayName = users.findById(u.id()).orElseThrow().getFullName();
        }

        ChatMessage msg = new ChatMessage();
        msg.setMeetingId(meetingId);
        msg.setSenderIdentity(identity);
        msg.setSenderDisplayName(displayName);
        msg.setContent(content);
        msg.setType(type != null ? type : ChatMessageType.TEXT);
        chatMessages.save(msg);

        publish(meetingId, ChatEvent.message(ChatMessageDto.from(msg)));
    }

    @Transactional
    public void deleteMessage(UUID meetingId, UUID messageId, UUID actorId) {
        Meeting meeting = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (!meeting.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_MEETING_HOST,
                    "Chỉ host mới được xóa tin nhắn");
        }
        ChatMessage msg = chatMessages.findById(messageId)
                .filter(m -> m.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn"));
        msg.setDeletedAt(Instant.now());
        msg.setDeletedBy(actorId.toString());
        publish(meetingId, ChatEvent.deleted(messageId));
    }

    void publish(UUID meetingId, ChatEvent event) {
        try {
            redis.convertAndSend("chat:" + meetingId, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
