package com.meetly.recording;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meetly.storage")
public record StorageProperties(String endpoint, String uploadEndpoint, String region,
                                String bucket, String accessKey, String secretKey) {}
