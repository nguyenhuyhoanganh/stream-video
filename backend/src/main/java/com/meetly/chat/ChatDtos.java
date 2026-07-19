package com.meetly.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class ChatDtos {
    public record SendChatRequest(@NotBlank @Size(max = 2000) String content,
                                  ChatMessageType type) {}

    public record ChatMessageDto(UUID id, UUID meetingId, String senderIdentity,
                                 String senderDisplayName, String content, String type,
                                 Instant createdAt) {
        static ChatMessageDto from(ChatMessage m) {
            return new ChatMessageDto(m.getId(), m.getMeetingId(), m.getSenderIdentity(),
                    m.getSenderDisplayName(), m.getContent(), m.getType().name(), m.getCreatedAt());
        }
    }

    public record ChatEvent(String kind, ChatMessageDto message, UUID messageId) {
        public static ChatEvent message(ChatMessageDto dto) { return new ChatEvent("MESSAGE", dto, null); }
        public static ChatEvent deleted(UUID id) { return new ChatEvent("MESSAGE_DELETED", null, id); }
    }
}
