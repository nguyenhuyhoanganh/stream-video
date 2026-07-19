package com.meetly.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetly.chat.ChatDtos.ChatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisChatRelay {
    private final SimpMessagingTemplate simp;
    private final ObjectMapper objectMapper;

    @Bean
    RedisMessageListenerContainer chatListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(chatListener(), new PatternTopic("chat:*"));
        return container;
    }

    MessageListener chatListener() {
        return (Message message, byte[] pattern) -> {
            try {
                String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                String meetingId = channel.substring("chat:".length());
                ChatEvent event = objectMapper.readValue(message.getBody(), ChatEvent.class);
                simp.convertAndSend("/topic/meetings/" + meetingId + "/chat", event);
            } catch (Exception e) {
                log.error("Relay chat event failed", e);
            }
        };
    }
}
