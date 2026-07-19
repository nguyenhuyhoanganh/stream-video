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
class MemberApiIT {
    @Autowired MockMvc mvc;
    private String hostToken;
    private String otherToken;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("mh+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("mo+" + System.nanoTime() + "@meetly.dev");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void webinarCreationAndMemberCrud() throws Exception {
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Town hall","roomType":"WEBINAR"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomType").value("WEBINAR"))
                .andReturn().getResponse().getContentAsString();
        String id = read(created, "$.id");

        // host thêm speaker theo email
        mvc.perform(post("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"email":"diengia@x.vn","role":"SPEAKER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("SPEAKER"));

        // list
        String list = mvc.perform(get("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String memberId = read(list, "$[0].id");

        // không phải host → 403
        mvc.perform(post("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"email":"x@x.vn","role":"ATTENDEE"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));

        // xóa member
        mvc.perform(delete("/api/v1/meetings/" + id + "/members/" + memberId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
