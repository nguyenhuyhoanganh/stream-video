package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import com.meetly.livekit.RoomControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.read;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ControlApiIT {
    @Autowired MockMvc mvc;
    @MockitoBean RoomControlService roomControl;

    private String hostToken;
    private String otherToken;
    private String meetingId;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("ch+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("co+" + System.nanoTime() + "@meetly.dev");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Webinar","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
        code = read(created, "$.code");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void hostPromotesAndRoleSticksOnRejoin() throws Exception {
        // other join webinar → ATTENDEE
        String otherId = read(mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + otherToken))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.role").value("ATTENDEE"));

        // host promote
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/participants/" + otherId + "/promote")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        verify(roomControl).setRole(eq(code), eq(otherId), eq(MeetingRole.SPEAKER));

        // re-join → SPEAKER (meeting_members was upserted)
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.role").value("SPEAKER"));
    }

    @Test
    void nonHostForbidden() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/participants/any/mute")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));
    }

    @Test
    void endMeeting() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        verify(roomControl).endRoom(eq(code));
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict());
    }
}
