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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RefreshTokenReuseIT {
    @Autowired MockMvc mvc;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RefreshTokenCleanupJob cleanupJob;

    private MvcResult register() throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"reuse+%d@meetly.dev","password":"secret123","fullName":"R"}"""
                                .formatted(System.nanoTime())))
                .andExpect(status().isOk()).andReturn();
    }

    /**
     * Replaying a rotated token signals theft: every session of the user must be revoked,
     * not just that one call — otherwise the thief keeps the fresh token and carries on
     * while the victim only notices they were signed out.
     */
    @Test
    void reusingRotatedTokenRevokesEverySession() throws Exception {
        MvcResult reg = register();
        UUID userId = UUID.fromString(read(reg.getResponse().getContentAsString(), "$.user.id"));
        Cookie stolen = reg.getResponse().getCookie("meetly_refresh");

        // the victim refreshes normally → the old token rotates out, a new one is issued
        MvcResult ok = mvc.perform(post("/api/v1/auth/refresh").cookie(stolen))
                .andExpect(status().isOk()).andReturn();
        Cookie fresh = ok.getResponse().getCookie("meetly_refresh");
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);

        // the thief replays the old copy → rejected
        mvc.perform(post("/api/v1/auth/refresh").cookie(stolen))
                .andExpect(status().isUnauthorized());

        // and every session is revoked: the victim's fresh token stops working too
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        mvc.perform(post("/api/v1/auth/refresh").cookie(fresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupRemovesOnlyExpiredTokens() throws Exception {
        // refresh_tokens.user_id is a foreign key, so a real user is required
        UUID userId = UUID.fromString(read(register().getResponse().getContentAsString(), "$.user.id"));

        RefreshToken expired = new RefreshToken();
        expired.setUserId(userId);
        expired.setTokenHash("hash-expired-" + System.nanoTime());
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        refreshTokens.save(expired);

        // revoked but not expired → must be kept so reuse detection still works
        RefreshToken revokedButLive = new RefreshToken();
        revokedButLive.setUserId(userId);
        revokedButLive.setTokenHash("hash-revoked-" + System.nanoTime());
        revokedButLive.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        revokedButLive.setRevokedAt(Instant.now());
        refreshTokens.save(revokedButLive);

        cleanupJob.purgeExpired();   // go through the real job so it runs in its transaction

        assertThat(refreshTokens.findByTokenHash(expired.getTokenHash())).isEmpty();
        assertThat(refreshTokens.findByTokenHash(revokedButLive.getTokenHash())).isPresent();
    }
}
