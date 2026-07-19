package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.read;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GuestJoinIT {
    @Autowired MockMvc mvc;
    private String hostToken;

    @BeforeEach
    void setUp() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"gh+%d@meetly.dev","password":"secret123","fullName":"H"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        hostToken = read(body, "$.accessToken");
    }

    private String createMeeting(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.code");
    }

    @Test
    void guestJoinsWebinarAsAttendee() throws Exception {
        String code = createMeeting("""
                {"title":"Public webinar","roomType":"WEBINAR"}""");
        String res = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .contentType(APPLICATION_JSON).content("""
                                {"displayName":"Khách A"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ATTENDEE"))
                .andExpect(jsonPath("$.chatToken").isNotEmpty())
                .andExpect(jsonPath("$.meetingId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        // identity trong livekit token là guest:*
        io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "meetly_dev_secret_0123456789abcdef".getBytes()))
                .build().parseSignedClaims((String) read(res, "$.livekitToken")).getPayload();
        org.assertj.core.api.Assertions.assertThat(claims.getSubject()).startsWith("guest:");
    }

    @Test
    void guestNeedsDisplayName() throws Exception {
        String code = createMeeting("""
                {"title":"Public","roomType":"WEBINAR"}""");
        mvc.perform(post("/api/v1/meetings/" + code + "/join"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_REQUIRED"));
    }

    @Test
    void guestBlockedFromPrivateMeeting() throws Exception {
        String code = createMeeting("""
                {"title":"Private","roomType":"MEETING"}""");
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .contentType(APPLICATION_JSON).content("""
                                {"displayName":"Khách"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUEST_MEETING_FORBIDDEN"));
    }
}
