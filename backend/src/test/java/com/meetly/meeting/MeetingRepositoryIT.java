package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import com.meetly.user.User;
import com.meetly.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MeetingRepositoryIT {
    @Autowired MeetingRepository meetings;
    @Autowired UserRepository users;

    @Test
    void saveAndFindByCode() {
        User host = new User();
        host.setEmail("host@meetly.dev");
        host.setPasswordHash("x");
        host.setFullName("Host");
        users.save(host);

        Meeting m = new Meeting();
        m.setCode("abc-defg-hij");
        m.setTitle("Daily standup");
        m.setHostId(host.getId());
        m.setScheduledStartAt(Instant.now());
        meetings.save(m);

        assertThat(meetings.findByCode("abc-defg-hij")).isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
                    assertThat(found.getRoomType()).isEqualTo(RoomType.MEETING);
                });
        assertThat(meetings.findByHostIdOrderByScheduledStartAtDesc(host.getId())).hasSize(1);
    }
}
