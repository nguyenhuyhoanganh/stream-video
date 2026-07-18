package com.meetly.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "meetly.auth")
public record AuthProperties(String jwtSecret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {}
