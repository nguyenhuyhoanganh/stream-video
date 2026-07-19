package com.meetly.recording;

import com.meetly.TestcontainersConfig;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RecordingApiIT {
    @Autowired MockMvc mvc;
    @MockitoBean EgressClient egressClient;
    @Autowired RecordingRepository recordings;

    private String hostToken;
    private String otherToken;
    private String meetingId;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("rh+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("ro+" + System.nanoTime() + "@meetly.dev");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Rec","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
        code = read(created, "$.code");
        when(egressClient.startRoomComposite(anyString(), startsWith("recordings/")))
                .thenReturn("EG_" + System.nanoTime());
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void startStopFlow() throws Exception {
        // start
        String started = mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STARTING"))
                .andReturn().getResponse().getContentAsString();

        // start lần 2 khi đang active → 409
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ALREADY_ACTIVE"));

        // non-host → 403
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        // stop → 204
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/stop")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        // list
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // playback khi chưa COMPLETED → 409
        String recId = read(started, "$.id");
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));

        // giả lập webhook đã hoàn tất → playback OK
        Recording rec = recordings.findById(java.util.UUID.fromString(recId)).orElseThrow();
        rec.setStatus(RecordingStatus.COMPLETED);
        rec.setS3Key("recordings/" + code + "/x.mp4");
        recordings.save(rec);
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());

        // người ngoài meeting xem playback → 403 (spec 4.6)
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
    }
}
