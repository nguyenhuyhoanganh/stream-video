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
     * Token đã xoay vòng mà bị dùng lại là dấu hiệu bị đánh cắp: phải thu hồi toàn bộ
     * phiên của user chứ không chỉ từ chối riêng lần gọi đó — nếu không, kẻ trộm giữ
     * được token mới và dùng tiếp trong khi nạn nhân chỉ thấy mình bị đăng xuất.
     */
    @Test
    void reusingRotatedTokenRevokesEverySession() throws Exception {
        MvcResult reg = register();
        UUID userId = UUID.fromString(read(reg.getResponse().getContentAsString(), "$.user.id"));
        Cookie stolen = reg.getResponse().getCookie("meetly_refresh");

        // nạn nhân refresh bình thường → token cũ bị xoay vòng, nhận token mới
        MvcResult ok = mvc.perform(post("/api/v1/auth/refresh").cookie(stolen))
                .andExpect(status().isOk()).andReturn();
        Cookie fresh = ok.getResponse().getCookie("meetly_refresh");
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);

        // kẻ trộm dùng lại bản sao cũ → bị từ chối
        mvc.perform(post("/api/v1/auth/refresh").cookie(stolen))
                .andExpect(status().isUnauthorized());

        // và toàn bộ phiên bị thu hồi: token mới của nạn nhân cũng hết dùng được
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        mvc.perform(post("/api/v1/auth/refresh").cookie(fresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupRemovesOnlyExpiredTokens() throws Exception {
        // refresh_tokens.user_id có khoá ngoại → phải dùng user thật
        UUID userId = UUID.fromString(read(register().getResponse().getContentAsString(), "$.user.id"));

        RefreshToken expired = new RefreshToken();
        expired.setUserId(userId);
        expired.setTokenHash("hash-expired-" + System.nanoTime());
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        refreshTokens.save(expired);

        // đã thu hồi nhưng chưa hết hạn → phải giữ để còn phát hiện tái sử dụng
        RefreshToken revokedButLive = new RefreshToken();
        revokedButLive.setUserId(userId);
        revokedButLive.setTokenHash("hash-revoked-" + System.nanoTime());
        revokedButLive.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        revokedButLive.setRevokedAt(Instant.now());
        refreshTokens.save(revokedButLive);

        cleanupJob.purgeExpired();   // gọi qua job thật để chạy trong transaction của nó

        assertThat(refreshTokens.findByTokenHash(expired.getTokenHash())).isEmpty();
        assertThat(refreshTokens.findByTokenHash(revokedButLive.getTokenHash())).isPresent();
    }
}
