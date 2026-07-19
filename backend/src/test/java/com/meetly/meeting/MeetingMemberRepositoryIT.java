package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import com.meetly.user.User;
import com.meetly.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MeetingMemberRepositoryIT {
    @Autowired MeetingRepository meetings;
    @Autowired MeetingMemberRepository members;
    @Autowired UserRepository users;

    @Test
    void memberLookupByUserAndEmail() {
        User host = new User();
        host.setEmail("h@meetly.dev"); host.setPasswordHash("x"); host.setFullName("H");
        users.save(host);
        Meeting m = new Meeting();
        m.setCode("aaa-bbbb-ccc"); m.setTitle("t"); m.setHostId(host.getId());
        m.setScheduledStartAt(Instant.now());
        meetings.save(m);

        MeetingMember byEmail = new MeetingMember();
        byEmail.setMeetingId(m.getId());
        byEmail.setInvitedEmail("guest@x.vn");
        byEmail.setRole(MeetingRole.SPEAKER);
        members.save(byEmail);

        assertThat(members.findByMeetingIdAndInvitedEmail(m.getId(), "guest@x.vn"))
                .isPresent()
                .hasValueSatisfying(mm -> assertThat(mm.getRole()).isEqualTo(MeetingRole.SPEAKER));
        assertThat(members.findByMeetingIdAndUserId(m.getId(), host.getId())).isEmpty();
    }
}
