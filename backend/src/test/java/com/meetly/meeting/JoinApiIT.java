package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class JoinApiIT {
    private static final String LIVEKIT_SECRET = "meetly_dev_secret_0123456789abcdef";

    @Autowired MockMvc mvc;
    private String hostToken;
    private String guestToken;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("h+" + System.nanoTime() + "@meetly.dev");
        guestToken = register("g+" + System.nanoTime() + "@meetly.dev");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    private String createMeeting(String bearer, String bodyJson) throws Exception {
        String body = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(bodyJson))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostAndParticipantJoin() throws Exception {
        String code = createMeeting(hostToken, """
                {"title":"Now meeting"}""");

        // host join → HOST, token có roomAdmin
        String hostJoin = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HOST"))
                .andExpect(jsonPath("$.livekitUrl").value("ws://localhost:7880"))
                .andReturn().getResponse().getContentAsString();
        Claims hostClaims = parseLivekit(read(hostJoin, "$.livekitToken"));
        Map<String, Object> hostVideo = hostClaims.get("video", Map.class);
        assertThat(hostVideo.get("room")).isEqualTo(code);
        assertThat(hostVideo.get("roomAdmin")).isEqualTo(true);
        assertThat(hostVideo.get("canPublish")).isEqualTo(true);

        // người khác join → SPEAKER, không roomAdmin
        String guestJoin = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SPEAKER"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> guestVideo = parseLivekit(read(guestJoin, "$.livekitToken"))
                .get("video", Map.class);
        assertThat(guestVideo.get("roomAdmin")).isNull();
        assertThat(guestVideo.get("canPublishData")).isEqualTo(false);
    }

    @Test
    void joinTooEarlyForbiddenExceptHost() throws Exception {
        String code = createMeeting(hostToken, """
                {"title":"Future","scheduledStartAt":"2030-01-01T00:00:00Z"}""");

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_STARTED"));

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk());
    }

    @Test
    void joinCancelledConflict() throws Exception {
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Bye"}"""))
                .andReturn().getResponse().getContentAsString();
        String code = read(created, "$.code");
        String id = read(created, "$.id");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_ENDED"));
    }

    private Claims parseLivekit(String jwt) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(LIVEKIT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();
    }
}
