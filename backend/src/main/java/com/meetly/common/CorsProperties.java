package com.meetly.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Origins allowed to call the REST API and open a WebSocket (spec 6.5).
 * Required: without it the WebSocket endpoint silently rejects every origin, so we
 * fail at startup rather than ship a build where nobody can join a meeting.
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
