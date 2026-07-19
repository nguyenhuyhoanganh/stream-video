package com.meetly.chat;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.jayway.jsonpath.JsonPath.read;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ChatRestIT {
    @Autowired MockMvc mvc;
    @Autowired ChatService chatService;
    @Autowired com.meetly.auth.JwtService jwtService;

    private String hostToken;
    private UUID hostId;
    private String meetingId;

    @BeforeEach
    void setUp() throws Exception {
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"cr+%d@meetly.dev","password":"secret123","fullName":"H"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        hostToken = read(reg, "$.accessToken");
        hostId = UUID.fromString(read(reg, "$.user.id"));
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"History","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
    }

    @Test
    void historyDeleteAndGuestAccess() throws Exception {
        var principal = new com.meetly.auth.AuthenticatedUser(hostId, "x@y.z");
        chatService.saveAndPublish(UUID.fromString(meetingId), principal, "msg 1", ChatMessageType.TEXT);
        chatService.saveAndPublish(UUID.fromString(meetingId), principal, "msg 2", ChatMessageType.TEXT);

        // history
        String list = mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String msg1Id = read(list, "$[0].id");

        // host deletes msg 1 → one left
        mvc.perform(delete("/api/v1/meetings/" + meetingId + "/messages/" + msg1Id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("msg 2"));

        // a guest token can read the history of its own meeting
        String guestJwt = jwtService.generateGuestToken(UUID.fromString(meetingId),
                "guest:abc", "Guest", java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + guestJwt))
                .andExpect(status().isOk());

        // a guest token for ANOTHER meeting (random id) used here → 403
        String otherGuest = jwtService.generateGuestToken(UUID.randomUUID(),
                "guest:zzz", "Guest", java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + otherGuest))
                .andExpect(status().isForbidden());
    }

    @Test
    void strangerCannotReadPrivateMeetingHistory() throws Exception {
        // PRIVATE room (MEETING) — signed in but not a member → 403
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Private","roomType":"MEETING"}"""))
                .andReturn().getResponse().getContentAsString();
        String privateId = read(created, "$.id");

        String otherReg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"cs+%d@meetly.dev","password":"secret123","fullName":"S"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        String strangerToken = read(otherReg, "$.accessToken");

        mvc.perform(get("/api/v1/meetings/" + privateId + "/messages")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));

        // a guest token for another meeting used here → 403
        String foreignGuest = jwtService.generateGuestToken(
                UUID.fromString(meetingId), "guest:zzz", "Guest",
                java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + privateId + "/messages")
                        .header("Authorization", "Bearer " + foreignGuest))
                .andExpect(status().isForbidden());
    }
}
