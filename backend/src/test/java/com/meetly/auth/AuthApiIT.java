package com.meetly.auth;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AuthApiIT {
    @Autowired MockMvc mvc;

    private static final String ANH = """
            {"email":"anh@meetly.dev","password":"secret123","fullName":"Anh"}""";

    @Test
    void registerLoginFlow() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON).content(ANH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("anh@meetly.dev"))
                .andExpect(cookie().httpOnly("meetly_refresh", true));

        // duplicate email → 409 EMAIL_TAKEN
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON).content(ANH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        // correct login
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"anh@meetly.dev","password":"secret123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // login sai pass → 401 INVALID_CREDENTIALS
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"anh@meetly.dev","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // body without email → 400 VALIDATION_FAILED
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"password":"secret123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
