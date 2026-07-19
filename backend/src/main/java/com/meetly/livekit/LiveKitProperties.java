package com.meetly.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meetly.livekit")
public record LiveKitProperties(String apiKey, String apiSecret, String wsUrl) {}
