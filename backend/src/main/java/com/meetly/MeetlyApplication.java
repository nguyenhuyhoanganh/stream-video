package com.meetly;

import com.meetly.common.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class MeetlyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetlyApplication.class, args);
    }
}
