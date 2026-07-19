package com.meetly.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Origin được phép gọi REST và mở WebSocket (spec 6.5).
 * Bắt buộc có giá trị: thiếu nó thì WS sẽ từ chối mọi origin một cách âm thầm,
 * nên fail ngay lúc khởi động thay vì để phòng họp không vào được trên production.
 */
@ConfigurationProperties(prefix = "meetly.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "Missing configuration meetly.cors.allowed-origins (env MEETLY_CORS_ALLOWED_ORIGINS)");
        }
    }
}
