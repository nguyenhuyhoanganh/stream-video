package com.meetly.chat;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ChatStompIT {
    @LocalServerPort int port;
    @Autowired MockMvc mvc;

    @Test
    void sendReceivePersistViaStomp() throws Exception {
        // arrange: a user and a webinar
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"chat+%d@meetly.dev","password":"secret123","fullName":"Chatter"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        String access = read(reg, "$.accessToken");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + access)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Chat room","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        String meetingId = read(created, "$.id");

        // STOMP connect
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + access);
        StompSession session = stomp.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);

        BlockingQueue<Map<String, Object>> received = new ArrayBlockingQueue<>(4);
        session.subscribe("/topic/meetings/" + meetingId + "/chat", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Map<String, Object>) payload);
            }
        });
        Thread.sleep(500); // let the subscription settle

        session.send("/app/meetings/" + meetingId + "/chat",
                Map.of("content", "Hello webinar", "type", "TEXT"));

        Map<String, Object> event = received.poll(10, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("kind")).isEqualTo("MESSAGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) event.get("message");
        assertThat(msg.get("content")).isEqualTo("Hello webinar");
        assertThat(msg.get("senderDisplayName")).isEqualTo("Chatter");

        session.disconnect();
    }
}
