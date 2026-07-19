package com.meetly;

import com.meetly.common.AuthProperties;
import com.meetly.common.CorsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class,
        com.meetly.livekit.LiveKitProperties.class,
        com.meetly.recording.StorageProperties.class})
public class MeetlyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetlyApplication.class, args);
    }
}
