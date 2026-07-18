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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MeetingApiIT {
    @Autowired MockMvc mvc;
    private String hostToken;
    private String otherToken;

    @BeforeEach
    void users() throws Exception {
        hostToken = registerAndGetToken("host+" + System.nanoTime() + "@meetly.dev");
        otherToken = registerAndGetToken("other+" + System.nanoTime() + "@meetly.dev");
    }

    private String registerAndGetToken(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void crudFlow() throws Exception {
        // create
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Sprint review","scheduledStartAt":"2026-07-18T09:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        String code = read(created, "$.code");
        String id = read(created, "$.id");

        // get by code (người khác cũng xem được — cần cho join)
        mvc.perform(get("/api/v1/meetings/" + code)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sprint review"));

        // list mine — host thấy 1, other thấy 0
        mvc.perform(get("/api/v1/meetings").header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/meetings").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // patch — chỉ host
        mvc.perform(patch("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Hacked"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));
        mvc.perform(patch("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Sprint review v2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sprint review v2"));

        // delete → CANCELLED
        mvc.perform(delete("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + code)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 404
        mvc.perform(get("/api/v1/meetings/zzz-zzzz-zzz")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
    }
}
