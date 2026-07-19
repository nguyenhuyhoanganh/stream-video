package com.meetly.chat;

import com.meetly.chat.ChatDtos.SendChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;

    @MessageMapping("/meetings/{meetingId}/chat")
    public void send(@DestinationVariable UUID meetingId,
                     @Payload SendChatRequest req,
                     Principal principal) {
        Object user = ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        ChatMessageType type = req.type() == ChatMessageType.RAISE_HAND
                ? ChatMessageType.RAISE_HAND : ChatMessageType.TEXT;
        chatService.saveAndPublish(meetingId, user, req.content(), type);
    }
}
