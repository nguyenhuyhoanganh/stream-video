package com.meetly.livekit;

import com.meetly.TestcontainersConfig;
import com.meetly.meeting.*;
import com.meetly.user.User;
import com.meetly.user.UserRepository;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class WebhookHandlerIT {
    @Autowired WebhookHandler handler;
    @Autowired MeetingRepository meetings;
    @Autowired ParticipantSessionRepository sessions;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    private Meeting meeting;

    @BeforeEach
    void setUp() {
        User host = new User();
        host.setEmail("wh+" + System.nanoTime() + "@meetly.dev");
        host.setPasswordHash("x"); host.setFullName("H");
        users.save(host);
        meeting = new Meeting();
        meeting.setCode("whk-" + System.nanoTime() % 10000 + "-abc");
        meeting.setTitle("t"); meeting.setHostId(host.getId());
        meeting.setScheduledStartAt(Instant.now());
        meetings.save(meeting);
    }

    private LivekitWebhook.WebhookEvent event(String type, String id) {
        return LivekitWebhook.WebhookEvent.newBuilder()
                .setEvent(type).setId(id)
                .setRoom(LivekitModels.Room.newBuilder().setName(meeting.getCode()))
                .setParticipant(LivekitModels.ParticipantInfo.newBuilder()
                        .setIdentity("user-1").setName("Anh"))
                .build();
    }

    @Test
    void roomLifecycleAndAttendance() {
        handler.handle(event("room_started", "e1-" + System.nanoTime()));
        assertThat(meetings.findByCode(meeting.getCode()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.LIVE);

        handler.handle(event("participant_joined", "e2-" + System.nanoTime()));
        assertThat(sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())).hasSize(1);

        handler.handle(event("participant_left", "e3-" + System.nanoTime()));
        assertThat(sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())).isEmpty();

        handler.handle(event("room_finished", "e4-" + System.nanoTime()));
        assertThat(meetings.findByCode(meeting.getCode()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.ENDED);
    }

    @Test
    void duplicateEventIgnored() {
        String id = "dup-" + System.nanoTime();
        handler.handle(event("participant_joined", id));
        handler.handle(event("participant_joined", id));
        assertThat(sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())).hasSize(1);
    }

    @Test
    void invalidSignatureRejected() throws Exception {
        mvc.perform(post("/api/v1/livekit/webhook")
                        .contentType("application/webhook+json")
                        .header("Authorization", "invalid-token")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
