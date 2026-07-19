package com.meetly.recording;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StorageServiceTest {
    private final StorageService service = new StorageService(new StorageProperties(
            "http://localhost:9000", "http://minio:9000",
            "us-east-1", "meetly-recordings", "minio", "minio12345"));

    @Test
    void presignedUrlContainsBucketKeyAndSignature() {
        String url = service.presignGetUrl("recordings/abc/1.mp4", Duration.ofHours(1));
        assertThat(url)
                .contains("meetly-recordings")
                .contains("recordings/abc/1.mp4")
                .contains("X-Amz-Signature=");
    }
}
