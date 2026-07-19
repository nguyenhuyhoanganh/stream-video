package com.meetly.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Every sign-in and refresh inserts a refresh_tokens row; without a purge the table
 * grows without bound. Only expired rows are deleted — a revoked row that has not
 * expired yet must stay so AuthService.rotate can still detect that the token was
 * replayed.
 *
 * Several pods running this job concurrently is harmless: a conditional, idempotent DELETE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository refreshTokens;

    @Scheduled(cron = "0 30 3 * * *")   // 03:30 daily, off-peak
    @Transactional
    public void purgeExpired() {
        int removed = refreshTokens.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) log.info("Purged {} expired refresh tokens", removed);
    }
}
