package com.meetly.livekit;

import io.livekit.server.WebhookReceiver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/livekit")
@RequiredArgsConstructor
public class WebhookController {
    private final LiveKitProperties props;
    private final WebhookHandler handler;
    private WebhookReceiver receiver;

    @PostConstruct
    void init() {
        receiver = new WebhookReceiver(props.apiKey(), props.apiSecret());
    }

    @PostMapping(value = "/webhook", consumes = {"application/webhook+json", "application/json"})
    public ResponseEntity<Void> receive(@RequestBody String body,
                                        @RequestHeader("Authorization") String authHeader) {
        try {
            handler.handle(receiver.receive(body, authHeader));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
