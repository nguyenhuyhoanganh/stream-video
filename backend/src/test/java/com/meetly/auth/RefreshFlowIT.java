package com.meetly.auth;

import com.meetly.TestcontainersConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.jayway.jsonpath.JsonPath.read;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RefreshFlowIT {
    @Autowired MockMvc mvc;

    @Test
    void refreshRotationAndMe() throws Exception {
        MvcResult reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"rot@meetly.dev","password":"secret123","fullName":"Rot"}"""))
                .andExpect(status().isOk()).andReturn();
        Cookie refresh1 = reg.getResponse().getCookie("meetly_refresh");
        String access = read(reg.getResponse().getContentAsString(), "$.accessToken");

        // /users/me with the access token
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("rot@meetly.dev"));

        // no token → 401
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());

        // refresh → new cookie, new access token
        MvcResult ref = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();
        Cookie refresh2 = ref.getResponse().getCookie("meetly_refresh");

        // the old token was revoked by rotation → 401
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // logout with the new token → 204; reusing it → 401
        mvc.perform(post("/api/v1/auth/logout").cookie(refresh2))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh2))
                .andExpect(status().isUnauthorized());
    }
}
