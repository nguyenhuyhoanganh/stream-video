package com.meetly.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Mỗi lần đăng nhập hoặc refresh đều sinh thêm một dòng refresh_tokens; không dọn thì
 * bảng phình vô hạn theo thời gian sống của hệ thống. Chỉ xoá dòng đã hết hạn — dòng
 * bị thu hồi nhưng chưa hết hạn phải giữ để AuthService.rotate còn phát hiện được
 * việc token bị dùng lại.
 *
 * Nhiều pod cùng chạy job này là vô hại: DELETE theo điều kiện, idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository refreshTokens;

    @Scheduled(cron = "0 30 3 * * *")   // 03:30 hằng ngày, giờ thấp điểm
    @Transactional
    public void purgeExpired() {
        int removed = refreshTokens.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) log.info("Đã dọn {} refresh token hết hạn", removed);
    }
}
