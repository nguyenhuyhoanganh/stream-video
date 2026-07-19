package com.meetly.common;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input hỏng từ client phải là 4xx, không phải 5xx: 5xx sai hợp đồng API (spec 4.8)
 * và làm alert MeetlyApiHigh5xxRate báo động giả mỗi khi có bot quét lung tung.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ClientErrorStatusIT {
    @Autowired MockMvc mvc;
    private String token;
    private String meetingId;

    @BeforeEach
    void setUp() throws Exception {
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"err+%d@meetly.dev","password":"secret123","fullName":"E"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        token = read(reg, "$.accessToken");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Err","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
    }

    @Test
    void malformedJsonBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content("{hỏng"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonUuidPathVariableIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/meetings/khong-phai-uuid/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedTimestampParamIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages?before=abc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonPositiveLimitIsClampedNotServerError() throws Exception {
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages?limit=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void garbageParticipantIdentityIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/participants/rac/promote")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/api/v1/auth/login").content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
