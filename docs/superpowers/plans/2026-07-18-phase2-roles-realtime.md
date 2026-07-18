# Meetly Phase 2 (Roles & Realtime) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Webinar hoạt động đúng phân quyền: ATTENDEE không publish được (chặn từ token), guest join bằng link, host promote/mute/kick runtime, chat STOMP đồng bộ đa pod qua Redis kèm moderation + giơ tay, webhook LiveKit cập nhật trạng thái phòng và điểm danh.

**Architecture:** Mở rộng `meetly-api` (thêm Redis, WebSocket/STOMP, webhook receiver, RoomService client) và `meetly-web` (room UI tự ghép thay `VideoConference`, ChatPanel, ParticipantList, guest flow). Không thêm service mới.

**Tech Stack:** Như Phase 1 + `spring-boot-starter-data-redis`, `spring-boot-starter-websocket`, `@stomp/stompjs` v7.

**Prerequisite:** Phase 1 hoàn thành, toàn bộ test xanh. Nếu implementation Phase 1 có lệch interface so với plan Phase 1 (tên class/method), cập nhật plan này cho khớp TRƯỚC khi chạy.

## Global Constraints

- Kế thừa toàn bộ Global Constraints của Phase 1 (`2026-07-18-phase1-skeleton.md`).
- Grants theo role (spec D3): `HOST` = canPublish + roomAdmin; `SPEAKER` = canPublish; `ATTENDEE` = canPublish **false**. Tất cả: canSubscribe=true, `canPublishData=false`.
- Guest (spec D8): chỉ được vào phòng `WEBINAR`, luôn là `ATTENDEE`, identity dạng `guest:{uuid}`, nhận `chatToken` (JWT scoped: claim `typ=guest`, `mtg=<meetingId>`, `name=<displayName>`).
- Chat qua STOMP endpoint `/ws`; client SEND `/app/meetings/{id}/chat`, SUBSCRIBE `/topic/meetings/{id}/chat`; đồng bộ đa pod qua Redis channel `chat:{meetingId}` (spec D4).
- Webhook `POST /api/v1/livekit/webhook` verify chữ ký, idempotent theo event id (Redis `SETNX`, TTL 24h) (spec 4.5).
- `ErrorCode` mới: `NOT_A_MEMBER`, `DISPLAY_NAME_REQUIRED`, `GUEST_MEETING_FORBIDDEN`, `PARTICIPANT_NOT_FOUND`, `MESSAGE_NOT_FOUND`.
- Meeting `MEETING` (họp kín): chỉ host + `meeting_members` được join. `WEBINAR`: ai có link cũng join được (mặc định ATTENDEE).

---

### Task 1: Migration V2 + entities (members, sessions, chat)

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__members_sessions_chat.sql`
- Create: `backend/src/main/java/com/meetly/meeting/MeetingMember.java`, `MeetingRole.java`, `MeetingMemberRepository.java`
- Create: `backend/src/main/java/com/meetly/meeting/ParticipantSession.java`, `ParticipantSessionRepository.java`
- Create: `backend/src/main/java/com/meetly/chat/ChatMessage.java`, `ChatMessageType.java`, `ChatMessageRepository.java`
- Test: `backend/src/test/java/com/meetly/meeting/MeetingMemberRepositoryIT.java`

**Interfaces:**
- Produces: enum `MeetingRole { HOST, SPEAKER, ATTENDEE }`; `MeetingMemberRepository.findByMeetingIdAndUserId(UUID, UUID): Optional<MeetingMember>`, `findByMeetingIdAndInvitedEmail(UUID, String): Optional<MeetingMember>`, `findByMeetingId(UUID): List<MeetingMember>`; `ParticipantSessionRepository.findFirstByMeetingIdAndIdentityAndLeftAtIsNullOrderByJoinedAtDesc(UUID, String): Optional<ParticipantSession>`, `findByMeetingIdAndLeftAtIsNull(UUID): List<ParticipantSession>`; `ChatMessageRepository.findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID, Instant, Pageable): List<ChatMessage>`, `findByMeetingIdAndIdGreaterThanEqualOrderByCreatedAtAsc(...)` không cần — bù tin dùng `before`/`after` theo `createdAt` (thêm `findByMeetingIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID, Instant): List<ChatMessage>`).

- [ ] **Step 1: Viết `V2__members_sessions_chat.sql`**

```sql
CREATE TABLE meeting_members (
    id            uuid PRIMARY KEY,
    meeting_id    uuid NOT NULL REFERENCES meetings (id),
    user_id       uuid REFERENCES users (id),
    invited_email varchar(255),
    role          varchar(20) NOT NULL,
    invited_by    uuid REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_meeting_member_user UNIQUE (meeting_id, user_id),
    CONSTRAINT chk_member_target CHECK (user_id IS NOT NULL OR invited_email IS NOT NULL)
);
CREATE INDEX idx_members_meeting ON meeting_members (meeting_id);

CREATE TABLE participant_sessions (
    id           uuid PRIMARY KEY,
    meeting_id   uuid NOT NULL REFERENCES meetings (id),
    identity     varchar(100) NOT NULL,
    display_name varchar(255),
    joined_at    timestamptz NOT NULL,
    left_at      timestamptz
);
CREATE INDEX idx_sessions_meeting ON participant_sessions (meeting_id, joined_at);

CREATE TABLE chat_messages (
    id                  uuid PRIMARY KEY,
    meeting_id          uuid NOT NULL REFERENCES meetings (id),
    sender_identity     varchar(100) NOT NULL,
    sender_display_name varchar(255) NOT NULL,
    content             text NOT NULL,
    type                varchar(20) NOT NULL DEFAULT 'TEXT',
    deleted_at          timestamptz,
    deleted_by          varchar(100),
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_meeting_time ON chat_messages (meeting_id, created_at);
```

- [ ] **Step 2: Viết entities + enums + repos**

`com/meetly/meeting/MeetingRole.java`:

```java
package com.meetly.meeting;

public enum MeetingRole { HOST, SPEAKER, ATTENDEE }
```

`com/meetly/meeting/MeetingMember.java`:

```java
package com.meetly.meeting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meeting_members")
@Getter @Setter @NoArgsConstructor
public class MeetingMember {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "invited_email")
    private String invitedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingRole role;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

`com/meetly/meeting/MeetingMemberRepository.java`:

```java
package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingMemberRepository extends JpaRepository<MeetingMember, UUID> {
    Optional<MeetingMember> findByMeetingIdAndUserId(UUID meetingId, UUID userId);
    Optional<MeetingMember> findByMeetingIdAndInvitedEmail(UUID meetingId, String invitedEmail);
    List<MeetingMember> findByMeetingId(UUID meetingId);
}
```

`com/meetly/meeting/ParticipantSession.java`:

```java
package com.meetly.meeting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "participant_sessions")
@Getter @Setter @NoArgsConstructor
public class ParticipantSession {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(nullable = false, length = 100)
    private String identity;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;
}
```

`com/meetly/meeting/ParticipantSessionRepository.java`:

```java
package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantSessionRepository extends JpaRepository<ParticipantSession, UUID> {
    Optional<ParticipantSession> findFirstByMeetingIdAndIdentityAndLeftAtIsNullOrderByJoinedAtDesc(
            UUID meetingId, String identity);
    List<ParticipantSession> findByMeetingIdAndLeftAtIsNull(UUID meetingId);
}
```

`com/meetly/chat/ChatMessageType.java`:

```java
package com.meetly.chat;

public enum ChatMessageType { TEXT, SYSTEM, RAISE_HAND }
```

`com/meetly/chat/ChatMessage.java`:

```java
package com.meetly.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
@Getter @Setter @NoArgsConstructor
public class ChatMessage {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "sender_identity", nullable = false, length = 100)
    private String senderIdentity;

    @Column(name = "sender_display_name", nullable = false)
    private String senderDisplayName;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageType type = ChatMessageType.TEXT;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

`com/meetly/chat/ChatMessageRepository.java`:

```java
package com.meetly.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID meetingId, Instant before, Pageable pageable);
    List<ChatMessage> findByMeetingIdAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID meetingId, Instant after);
}
```

- [ ] **Step 3: Viết test repo**

`backend/src/test/java/com/meetly/meeting/MeetingMemberRepositoryIT.java`:

```java
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
```

- [ ] **Step 4: Chạy test**

Run: `cd backend && ./mvnw -q test -Dtest=MeetingMemberRepositoryIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(be): V2 migration + member/session/chat entities"
```

---

### Task 2: Redis vào backend

**Files:**
- Modify: `backend/pom.xml` (thêm dependency), `backend/src/main/resources/application.yml`, `backend/src/test/java/com/meetly/TestcontainersConfig.java`

**Interfaces:**
- Produces: `StringRedisTemplate` sẵn dùng cho task 5 (webhook dedupe) và task 7 (chat relay); profile test có Redis container qua `@ServiceConnection`.

- [ ] **Step 1: Thêm dependency vào `pom.xml`** (khối `<dependencies>`)

```xml
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
```

(`starter-websocket` dùng ở Task 7, thêm luôn một lần.)

- [ ] **Step 2: Thêm config vào `application.yml`** (dưới khối `spring:`)

```yaml
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 3: Thêm Redis container vào `TestcontainersConfig.java`**

```java
package com.meetly;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
```

- [ ] **Step 4: Chạy toàn bộ test (đảm bảo không vỡ), commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ.

```bash
git add backend/pom.xml backend/src
git commit -m "feat(be): wire redis + websocket starters"
```

---

### Task 3: roomType khi tạo meeting + Members API

**Files:**
- Modify: `backend/src/main/java/com/meetly/meeting/MeetingDtos.java`, `MeetingService.java`
- Create: `backend/src/main/java/com/meetly/meeting/MemberController.java`, `MemberService.java`
- Modify: `backend/src/main/java/com/meetly/common/ErrorCode.java`
- Test: `backend/src/test/java/com/meetly/meeting/MemberApiIT.java`

**Interfaces:**
- Produces: `CreateMeetingRequest` thêm field `roomType` (`"MEETING"`|`"WEBINAR"`, default MEETING); REST `GET/POST /api/v1/meetings/{id}/members`, `DELETE /api/v1/meetings/{id}/members/{memberId}` (host-only); `MemberDtos`: `AddMemberRequest(String email, MeetingRole role)` (role chỉ SPEAKER/ATTENDEE), `MemberResponse(UUID id, String email, MeetingRole role)`; `MemberService.resolveRole(Meeting m, UUID userId, String email): Optional<MeetingRole>` — host → HOST; member theo userId hoặc email (backfill userId khi match email); empty nếu không phải member. Task 4 dùng `resolveRole`.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/meeting/MemberApiIT.java`:

```java
package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.read;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MemberApiIT {
    @Autowired MockMvc mvc;
    private String hostToken;
    private String otherToken;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("mh+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("mo+" + System.nanoTime() + "@meetly.dev");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void webinarCreationAndMemberCrud() throws Exception {
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Town hall","roomType":"WEBINAR"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomType").value("WEBINAR"))
                .andReturn().getResponse().getContentAsString();
        String id = read(created, "$.id");

        // host thêm speaker theo email
        mvc.perform(post("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"email":"diengia@x.vn","role":"SPEAKER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("SPEAKER"));

        // list
        String list = mvc.perform(get("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String memberId = read(list, "$[0].id");

        // không phải host → 403
        mvc.perform(post("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"email":"x@x.vn","role":"ATTENDEE"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));

        // xóa member
        mvc.perform(delete("/api/v1/meetings/" + id + "/members/" + memberId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + id + "/members")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=MemberApiIT` → FAIL.

Sửa `MeetingDtos.CreateMeetingRequest` (thay record cũ):

```java
    public record CreateMeetingRequest(@NotBlank @Size(max = 255) String title,
                                       String description,
                                       Instant scheduledStartAt,
                                       Instant scheduledEndAt,
                                       RoomType roomType) {}
```

Trong `MeetingService.create`, sau `m.setScheduledEndAt(...)` thêm:

```java
        m.setRoomType(req.roomType() != null ? req.roomType() : RoomType.MEETING);
```

Thêm 2 record vào `MeetingDtos`:

```java
    public record AddMemberRequest(@NotBlank @Email String email, MeetingRole role) {}
    public record MemberResponse(UUID id, String email, MeetingRole role) {}
```

(import `jakarta.validation.constraints.Email`.)

`com/meetly/meeting/MemberService.java`:

```java
package com.meetly.meeting;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MeetingRepository meetings;
    private final MeetingMemberRepository members;
    private final UserRepository users;

    /** HOST nếu là chủ phòng; role member nếu có trong meeting_members; empty nếu người lạ. */
    @Transactional
    public Optional<MeetingRole> resolveRole(Meeting meeting, UUID userId, String email) {
        if (meeting.getHostId().equals(userId)) return Optional.of(MeetingRole.HOST);
        Optional<MeetingMember> byUser = members.findByMeetingIdAndUserId(meeting.getId(), userId);
        if (byUser.isPresent()) return byUser.map(MeetingMember::getRole);
        Optional<MeetingMember> byEmail = members.findByMeetingIdAndInvitedEmail(meeting.getId(), email);
        byEmail.ifPresent(mm -> mm.setUserId(userId)); // backfill lần đầu join
        return byEmail.map(MeetingMember::getRole);
    }

    @Transactional
    public MeetingMember add(UUID meetingId, UUID actorId, String email, MeetingRole role) {
        Meeting m = requireHost(meetingId, actorId);
        if (role == MeetingRole.HOST) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Không thể gán role HOST cho member");
        }
        MeetingMember mm = new MeetingMember();
        mm.setMeetingId(m.getId());
        mm.setInvitedEmail(email);
        users.findByEmail(email).ifPresent(u -> mm.setUserId(u.getId()));
        mm.setRole(role != null ? role : MeetingRole.ATTENDEE);
        mm.setInvitedBy(actorId);
        return members.save(mm);
    }

    @Transactional(readOnly = true)
    public List<MeetingMember> list(UUID meetingId, UUID actorId) {
        requireHost(meetingId, actorId);
        return members.findByMeetingId(meetingId);
    }

    @Transactional
    public void remove(UUID meetingId, UUID actorId, UUID memberId) {
        requireHost(meetingId, actorId);
        members.deleteById(memberId);
    }

    private Meeting requireHost(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (!m.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    ErrorCode.NOT_MEETING_HOST, "Chỉ host mới được thao tác");
        }
        return m;
    }
}
```

`com/meetly/meeting/MemberController.java`:

```java
package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.AddMemberRequest;
import com.meetly.meeting.MeetingDtos.MemberResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable UUID meetingId,
                              @Valid @RequestBody AddMemberRequest req) {
        MeetingMember mm = memberService.add(meetingId, user.id(), req.email(), req.role());
        return new MemberResponse(mm.getId(), mm.getInvitedEmail(), mm.getRole());
    }

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID meetingId) {
        return memberService.list(meetingId, user.id()).stream()
                .map(mm -> new MemberResponse(mm.getId(), mm.getInvitedEmail(), mm.getRole()))
                .toList();
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID meetingId, @PathVariable UUID memberId) {
        memberService.remove(meetingId, user.id(), memberId);
        return ResponseEntity.noContent().build();
    }
}
```

Thêm vào `ErrorCode` enum (trước `INTERNAL_ERROR`):

```java
    NOT_A_MEMBER,
    DISPLAY_NAME_REQUIRED,
    GUEST_MEETING_FORBIDDEN,
    PARTICIPANT_NOT_FOUND,
    MESSAGE_NOT_FOUND,
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ.

```bash
git add backend/src
git commit -m "feat(be): roomType on create + members api + role resolution"
```

---

### Task 4: Join theo role thật (member-based, grants theo role)

**Files:**
- Modify: `backend/src/main/java/com/meetly/livekit/LiveKitTokenService.java` (đổi signature), `backend/src/main/java/com/meetly/meeting/MeetingService.java` (join), `backend/src/test/java/com/meetly/livekit/LiveKitTokenServiceTest.java` (cập nhật theo signature mới)
- Test: Modify `backend/src/test/java/com/meetly/meeting/JoinApiIT.java` (thêm case member/attendee/người lạ)

**Interfaces:**
- Produces: `LiveKitTokenService.createToken(String roomCode, String identity, String displayName, MeetingRole role, Instant expiresAt): String` — grants theo bảng ở Global Constraints. `MeetingService.join(...)` trả `JoinResponse` với `role` là tên `MeetingRole`. FE nhận `role: 'HOST'|'SPEAKER'|'ATTENDEE'`.
- Consumes: `MemberService.resolveRole` (Task 3).

- [ ] **Step 1: Cập nhật `LiveKitTokenServiceTest` — thêm case ATTENDEE (fail)**

Thay 2 test cũ bằng (giữ nguyên phần setup class):

```java
    @Test
    @SuppressWarnings("unchecked")
    void speakerCanPublish() {
        String jwt = service.createToken("abc-defg-hij", "user-1", "Anh",
                com.meetly.meeting.MeetingRole.SPEAKER, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("roomAdmin")).isNull();
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void attendeeCannotPublish() {
        String jwt = service.createToken("abc-defg-hij", "guest:123", "Khách",
                com.meetly.meeting.MeetingRole.ATTENDEE, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(false);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostHasRoomAdmin() {
        String jwt = service.createToken("abc-defg-hij", "user-2", "Host",
                com.meetly.meeting.MeetingRole.HOST, Instant.now().plus(2, ChronoUnit.HOURS));
        Map<String, Object> video = parse(jwt);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("roomAdmin")).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String jwt) {
        return (Map<String, Object>) Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload().get("video", Map.class);
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=LiveKitTokenServiceTest` → FAIL (signature cũ).

- [ ] **Step 2: Sửa `LiveKitTokenService.createToken`**

```java
    public String createToken(String roomCode, String identity, String displayName,
                              com.meetly.meeting.MeetingRole role, Instant expiresAt) {
        AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
        token.setIdentity(identity);
        token.setName(displayName);
        token.setExpiration(Date.from(expiresAt));
        boolean canPublish = role != com.meetly.meeting.MeetingRole.ATTENDEE;
        token.addGrants(new RoomJoin(true), new RoomName(roomCode),
                new CanPublish(canPublish), new CanSubscribe(true),
                new CanPublishData(false));
        if (role == com.meetly.meeting.MeetingRole.HOST) token.addGrants(new RoomAdmin(true));
        return token.toJwt();
    }
```

- [ ] **Step 3: Sửa `MeetingService.join` dùng resolveRole** (thay toàn bộ method; inject thêm `MemberService memberService`)

```java
    @Transactional
    public MeetingDtos.JoinResponse join(String code, UUID userId) {
        Meeting m = getByCode(code);
        var user = users.findById(userId).orElseThrow();
        MeetingRole role = memberService.resolveRole(m, userId, user.getEmail())
                .orElseGet(() -> {
                    if (m.getRoomType() == RoomType.WEBINAR) return MeetingRole.ATTENDEE;
                    throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                            "Bạn không được mời vào phòng họp này");
                });
        validateJoinable(m, role == MeetingRole.HOST);
        String token = liveKitTokenService.createToken(
                m.getCode(), userId.toString(), user.getFullName(), role, tokenExpiry(m));
        return new MeetingDtos.JoinResponse(liveKitTokenService.wsUrl(), token, role.name());
    }

    void validateJoinable(Meeting m, boolean isHost) {
        if (m.getStatus() == MeetingStatus.ENDED || m.getStatus() == MeetingStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.MEETING_ENDED,
                    "Phòng họp đã kết thúc hoặc bị hủy");
        }
        Instant earliestJoin = m.getScheduledStartAt().minus(15, ChronoUnit.MINUTES);
        if (!isHost && Instant.now().isBefore(earliestJoin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.MEETING_NOT_STARTED,
                    "Phòng họp chưa bắt đầu (được vào sớm tối đa 15 phút)");
        }
    }

    Instant tokenExpiry(Meeting m) {
        return (m.getScheduledEndAt() != null
                ? m.getScheduledEndAt() : Instant.now().plus(4, ChronoUnit.HOURS))
                .plus(2, ChronoUnit.HOURS);
    }
```

- [ ] **Step 4: Cập nhật `JoinApiIT`** — case `hostAndParticipantJoin` giờ tạo meeting `WEBINAR` (người lạ → ATTENDEE) và thêm case MEETING chặn người lạ:

Trong `hostAndParticipantJoin`, đổi phần tạo meeting và assert của "người khác":

```java
        String code = createMeeting(hostToken, """
                {"title":"Now meeting","roomType":"WEBINAR"}""");
        // ... host join giữ nguyên assertions ...

        // người lạ join WEBINAR → ATTENDEE, không publish được
        String guestJoin = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ATTENDEE"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> guestVideo = parseLivekit(read(guestJoin, "$.livekitToken"))
                .get("video", Map.class);
        assertThat(guestVideo.get("canPublish")).isEqualTo(false);
```

Thêm test mới:

```java
    @Test
    void strangerCannotJoinPrivateMeeting() throws Exception {
        String code = createMeeting(hostToken, """
                {"title":"Private","roomType":"MEETING"}""");
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
    }
```

- [ ] **Step 5: Chạy toàn bộ test → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): role-based join with member resolution"
```

---

### Task 5: Guest join + guest JWT

**Files:**
- Modify: `backend/src/main/java/com/meetly/auth/JwtService.java` (guest token), `JwtAuthFilter.java` (nhận cả 2 loại), `backend/src/main/java/com/meetly/common/SecurityConfig.java` (join permitAll), `backend/src/main/java/com/meetly/meeting/JoinController.java`, `MeetingService.java`, `MeetingDtos.java`
- Create: `backend/src/main/java/com/meetly/auth/GuestUser.java`
- Test: `backend/src/test/java/com/meetly/meeting/GuestJoinIT.java`

**Interfaces:**
- Produces: `JwtService.generateGuestToken(UUID meetingId, String identity, String displayName, Instant expiresAt): String` (claims `typ=guest`, `mtg`, `name`); `JwtService.parsePrincipal(String token): Object` trả `AuthenticatedUser` hoặc `GuestUser(String identity, String displayName, UUID meetingId)`; `JoinRequest(String displayName)` (body optional); `JoinResponse` thêm field `chatToken` (null với user thường) → **FE type cập nhật ở Task 10**. Guest có authority `ROLE_GUEST`.
- `POST /meetings/{code}/join` permitAll — logic: có principal → flow Task 4; anonymous → yêu cầu `displayName` (400 `DISPLAY_NAME_REQUIRED`), chỉ WEBINAR (403 `GUEST_MEETING_FORBIDDEN`), role ATTENDEE, identity `guest:{uuid}`, trả kèm `chatToken`.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/meeting/GuestJoinIT.java`:

```java
package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.read;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GuestJoinIT {
    @Autowired MockMvc mvc;
    private String hostToken;

    @BeforeEach
    void setUp() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"gh+%d@meetly.dev","password":"secret123","fullName":"H"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        hostToken = read(body, "$.accessToken");
    }

    private String createMeeting(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.code");
    }

    @Test
    void guestJoinsWebinarAsAttendee() throws Exception {
        String code = createMeeting("""
                {"title":"Public webinar","roomType":"WEBINAR"}""");
        String res = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .contentType(APPLICATION_JSON).content("""
                                {"displayName":"Khách A"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ATTENDEE"))
                .andExpect(jsonPath("$.chatToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        // identity trong livekit token là guest:*
        io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "meetly_dev_secret_0123456789abcdef".getBytes()))
                .build().parseSignedClaims((String) read(res, "$.livekitToken")).getPayload();
        org.assertj.core.api.Assertions.assertThat(claims.getSubject()).startsWith("guest:");
    }

    @Test
    void guestNeedsDisplayName() throws Exception {
        String code = createMeeting("""
                {"title":"Public","roomType":"WEBINAR"}""");
        mvc.perform(post("/api/v1/meetings/" + code + "/join"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_REQUIRED"));
    }

    @Test
    void guestBlockedFromPrivateMeeting() throws Exception {
        String code = createMeeting("""
                {"title":"Private","roomType":"MEETING"}""");
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .contentType(APPLICATION_JSON).content("""
                                {"displayName":"Khách"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUEST_MEETING_FORBIDDEN"));
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=GuestJoinIT` → FAIL.

`com/meetly/auth/GuestUser.java`:

```java
package com.meetly.auth;

import java.util.UUID;

public record GuestUser(String identity, String displayName, UUID meetingId) {}
```

Thêm vào `JwtService.java`:

```java
    public String generateGuestToken(UUID meetingId, String identity, String displayName,
                                     Instant expiresAt) {
        return Jwts.builder()
                .subject(identity)
                .claim("typ", "guest")
                .claim("mtg", meetingId.toString())
                .claim("name", displayName)
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** Trả AuthenticatedUser (access token) hoặc GuestUser (guest token). */
    public Object parsePrincipal(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        if ("guest".equals(c.get("typ", String.class))) {
            return new GuestUser(c.getSubject(), c.get("name", String.class),
                    UUID.fromString(c.get("mtg", String.class)));
        }
        return new AuthenticatedUser(UUID.fromString(c.getSubject()), c.get("email", String.class));
    }
```

Sửa `JwtAuthFilter.doFilterInternal` — thay khối try:

```java
            try {
                Object principal = jwtService.parsePrincipal(header.substring(7));
                String role = principal instanceof GuestUser ? "ROLE_GUEST" : "ROLE_USER";
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
            }
```

Sửa `SecurityConfig` — thêm vào permitAll matchers:

```java
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/meetings/*/join").permitAll()
```

Thêm vào `MeetingDtos`:

```java
    public record JoinRequest(String displayName) {}
```

và thay `JoinResponse`:

```java
    public record JoinResponse(String livekitUrl, String livekitToken, String role,
                               String chatToken) {}
```

(Cập nhật chỗ tạo `JoinResponse` trong `join(...)` của Task 4: thêm đối số `null`.)

Thêm vào `MeetingService`:

```java
    @Transactional(readOnly = true)
    public MeetingDtos.JoinResponse joinAsGuest(String code, String displayName) {
        Meeting m = getByCode(code);
        if (displayName == null || displayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.DISPLAY_NAME_REQUIRED,
                    "Vui lòng nhập tên hiển thị");
        }
        if (m.getRoomType() != RoomType.WEBINAR) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.GUEST_MEETING_FORBIDDEN,
                    "Phòng họp này yêu cầu đăng nhập");
        }
        validateJoinable(m, false);
        String identity = "guest:" + UUID.randomUUID();
        Instant expiresAt = tokenExpiry(m);
        String lkToken = liveKitTokenService.createToken(
                m.getCode(), identity, displayName, MeetingRole.ATTENDEE, expiresAt);
        String chatToken = jwtService.generateGuestToken(m.getId(), identity, displayName, expiresAt);
        return new MeetingDtos.JoinResponse(liveKitTokenService.wsUrl(), lkToken,
                MeetingRole.ATTENDEE.name(), chatToken);
    }
```

(inject thêm `JwtService jwtService` vào `MeetingService`.)

Sửa `JoinController.join`:

```java
    @PostMapping("/{code}/join")
    public JoinResponse join(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable String code,
                             @RequestBody(required = false) MeetingDtos.JoinRequest body) {
        if (user != null) return meetingService.join(code, user.id());
        return meetingService.joinAsGuest(code, body != null ? body.displayName() : null);
    }
```

(import `org.springframework.web.bind.annotation.RequestBody`.)

- [ ] **Step 3: Chạy toàn bộ → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): guest join for webinars with scoped guest jwt"
```

---

### Task 6: Webhook LiveKit (signature, idempotent, room/participant events)

**Files:**
- Create: `backend/src/main/java/com/meetly/livekit/WebhookController.java`, `WebhookHandler.java`
- Modify: `backend/src/main/java/com/meetly/common/SecurityConfig.java` (permitAll webhook)
- Test: `backend/src/test/java/com/meetly/livekit/WebhookHandlerIT.java`

**Interfaces:**
- Produces: `POST /api/v1/livekit/webhook` (content-type `application/webhook+json`, header `Authorization` chứa JWT LiveKit) — verify bằng `io.livekit.server.WebhookReceiver(apiKey, apiSecret).receive(body, authHeader)`. `WebhookHandler.handle(LivekitWebhook.WebhookEvent)`: `room_started`→LIVE, `room_finished`→ENDED + đóng session mở, `participant_joined`→insert session, `participant_left`→set left_at. Dedupe: `StringRedisTemplate.opsForValue().setIfAbsent("webhook:evt:"+id, "1", Duration.ofHours(24))`.
- Controller trả 200 kể cả event không xử lý (LiveKit không retry vô hạn); 401 nếu chữ ký sai.

- [ ] **Step 1: Viết test fail** (test handler trực tiếp — không cần ký JWT thật; controller test chữ ký sai → 401)

`backend/src/test/java/com/meetly/livekit/WebhookHandlerIT.java`:

```java
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
        handler.handle(event("room_started", "e1"));
        assertThat(meetings.findByCode(meeting.getCode()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.LIVE);

        handler.handle(event("participant_joined", "e2"));
        assertThat(sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())).hasSize(1);

        handler.handle(event("participant_left", "e3"));
        assertThat(sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())).isEmpty();

        handler.handle(event("room_finished", "e4"));
        assertThat(meetings.findByCode(meeting.getCode()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.ENDED);
    }

    @Test
    void duplicateEventIgnored() {
        handler.handle(event("participant_joined", "dup-1"));
        handler.handle(event("participant_joined", "dup-1"));
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
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=WebhookHandlerIT` → FAIL.

`com/meetly/livekit/WebhookHandler.java`:

```java
package com.meetly.livekit;

import com.meetly.meeting.*;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookHandler {
    private final MeetingRepository meetings;
    private final ParticipantSessionRepository sessions;
    private final StringRedisTemplate redis;

    @Transactional
    public void handle(LivekitWebhook.WebhookEvent event) {
        Boolean first = redis.opsForValue()
                .setIfAbsent("webhook:evt:" + event.getId(), "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(first)) {
            log.debug("Duplicate webhook event {} ignored", event.getId());
            return;
        }
        String roomName = event.getRoom().getName();
        Meeting meeting = meetings.findByCode(roomName).orElse(null);
        if (meeting == null) {
            log.warn("Webhook for unknown room {}", roomName);
            return;
        }
        switch (event.getEvent()) {
            case "room_started" -> {
                if (meeting.getStatus() == MeetingStatus.SCHEDULED) {
                    meeting.setStatus(MeetingStatus.LIVE);
                }
            }
            case "room_finished" -> {
                meeting.setStatus(MeetingStatus.ENDED);
                sessions.findByMeetingIdAndLeftAtIsNull(meeting.getId())
                        .forEach(s -> s.setLeftAt(Instant.now()));
            }
            case "participant_joined" -> {
                ParticipantSession s = new ParticipantSession();
                s.setMeetingId(meeting.getId());
                s.setIdentity(event.getParticipant().getIdentity());
                s.setDisplayName(event.getParticipant().getName());
                s.setJoinedAt(Instant.now());
                sessions.save(s);
            }
            case "participant_left" -> sessions
                    .findFirstByMeetingIdAndIdentityAndLeftAtIsNullOrderByJoinedAtDesc(
                            meeting.getId(), event.getParticipant().getIdentity())
                    .ifPresent(s -> s.setLeftAt(Instant.now()));
            default -> log.debug("Unhandled webhook event {}", event.getEvent());
        }
        meeting.setUpdatedAt(Instant.now());
    }
}
```

*Ghi chú thiết kế: SETNX đánh dấu TRƯỚC khi xử lý → nếu transaction sau đó fail thì event bị bỏ qua vĩnh viễn (at-most-once). Chấp nhận cho MVP vì các event sau tự đưa hệ về đúng trạng thái (`room_finished` đóng mọi session còn mở, status là dữ liệu hội tụ). Nếu sau này cần at-least-once: đổi thành check-tồn-tại trước, ghi dấu SAU khi transaction commit (`TransactionSynchronization.afterCommit`).*

`com/meetly/livekit/WebhookController.java`:

```java
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
```

Thêm vào `SecurityConfig` permitAll matchers:

```java
                .requestMatchers("/api/v1/livekit/webhook").permitAll()
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): livekit webhook receiver with idempotent handlers"
```

---

### Task 7: In-room controls (mute / promote / demote / kick / end)

**Files:**
- Create: `backend/src/main/java/com/meetly/livekit/RoomControlService.java`, `backend/src/main/java/com/meetly/meeting/ControlController.java`
- Modify: `backend/src/main/resources/application.yml` + `application-test.yml` (thêm `meetly.livekit.http-url: http://localhost:7880`), `LiveKitProperties.java` (thêm field `httpUrl`)
- Test: `backend/src/test/java/com/meetly/meeting/ControlApiIT.java`

**Interfaces:**
- Produces: REST host-only `POST /api/v1/meetings/{id}/participants/{identity}/mute|promote|demote|kick`, `POST /api/v1/meetings/{id}/end`. `RoomControlService` bọc `io.livekit.server.RoomServiceClient` (tạo bằng `RoomServiceClient.createClient(httpUrl, apiKey, apiSecret)`): `muteAllAudio(room, identity)`, `setRole(room, identity, MeetingRole)` (updateParticipant permission `canPublish`), `kick(room, identity)`, `endRoom(room)`. Promote/demote đồng thời upsert `meeting_members.role` (SPEAKER/ATTENDEE) để join lại vẫn giữ quyền.
- Test: mock `RoomControlService` bằng `@MockitoBean` (LiveKit server không chạy trong IT) — verify authorization + gọi đúng method; hành vi runtime thật verify thủ công/e2e Task 13.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/meeting/ControlApiIT.java`:

```java
package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import com.meetly.livekit.RoomControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.read;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ControlApiIT {
    @Autowired MockMvc mvc;
    @MockitoBean RoomControlService roomControl;

    private String hostToken;
    private String otherToken;
    private String meetingId;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("ch+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("co+" + System.nanoTime() + "@meetly.dev");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Webinar","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
        code = read(created, "$.code");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void hostPromotesAndRoleSticksOnRejoin() throws Exception {
        // other join webinar → ATTENDEE
        String otherId = read(mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + otherToken))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.role").value("ATTENDEE"));

        // host promote
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/participants/" + otherId + "/promote")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        verify(roomControl).setRole(eq(code), eq(otherId), eq(MeetingRole.SPEAKER));

        // join lại → SPEAKER (đã upsert meeting_members)
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.role").value("SPEAKER"));
    }

    @Test
    void nonHostForbidden() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/participants/any/mute")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));
    }

    @Test
    void endMeeting() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        verify(roomControl).endRoom(eq(code));
        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=ControlApiIT` → FAIL.

Sửa `LiveKitProperties`:

```java
public record LiveKitProperties(String apiKey, String apiSecret, String wsUrl, String httpUrl) {}
```

Thêm `http-url: http://localhost:7880` vào `meetly.livekit` trong cả `application.yml` và `application-test.yml`.

`com/meetly/livekit/RoomControlService.java`:

```java
package com.meetly.livekit;

import com.meetly.meeting.MeetingRole;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
public class RoomControlService {
    private final RoomServiceClient client;

    public RoomControlService(LiveKitProperties props) {
        this.client = RoomServiceClient.createClient(props.httpUrl(), props.apiKey(), props.apiSecret());
    }

    /** Mute mọi audio track đang publish của participant. */
    public void muteAllAudio(String room, String identity) {
        try {
            LivekitModels.ParticipantInfo info =
                    client.getParticipant(room, identity).execute().body();
            if (info == null) return;
            for (LivekitModels.TrackInfo track : info.getTracksList()) {
                if (track.getType() == LivekitModels.TrackType.AUDIO) {
                    client.mutePublishedTrack(room, identity, track.getSid(), true).execute();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit mute failed", e);
        }
    }

    /** Cấp lại grants runtime: SPEAKER → canPublish, ATTENDEE → không. */
    public void setRole(String room, String identity, MeetingRole role) {
        try {
            LivekitModels.ParticipantPermission permission =
                    LivekitModels.ParticipantPermission.newBuilder()
                            .setCanSubscribe(true)
                            .setCanPublish(role != MeetingRole.ATTENDEE)
                            .setCanPublishData(false)
                            .build();
            client.updateParticipant(room, identity, null, null, permission).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit updateParticipant failed", e);
        }
    }

    public void kick(String room, String identity) {
        try {
            client.removeParticipant(room, identity).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit removeParticipant failed", e);
        }
    }

    public void endRoom(String room) {
        try {
            client.deleteRoom(room).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit deleteRoom failed", e);
        }
    }
}
```

*Ghi chú: nếu API SDK khác tên/chữ ký (`updateParticipant` overloads), chỉnh implementation theo SDK, giữ hành vi: đổi `canPublish` runtime, không rejoin.*

`com/meetly/meeting/ControlController.java`:

```java
package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.livekit.RoomControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
@RequiredArgsConstructor
public class ControlController {
    private final MeetingRepository meetings;
    private final MeetingMemberRepository members;
    private final RoomControlService roomControl;

    @PostMapping("/participants/{identity}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId, @PathVariable String identity) {
        Meeting m = requireHost(meetingId, user.id());
        roomControl.muteAllAudio(m.getCode(), identity);
    }

    @PostMapping("/participants/{identity}/promote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void promote(@AuthenticationPrincipal AuthenticatedUser user,
                        @PathVariable UUID meetingId, @PathVariable String identity) {
        changeRole(user, meetingId, identity, MeetingRole.SPEAKER);
    }

    @PostMapping("/participants/{identity}/demote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void demote(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID meetingId, @PathVariable String identity) {
        changeRole(user, meetingId, identity, MeetingRole.ATTENDEE);
    }

    @PostMapping("/participants/{identity}/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId, @PathVariable String identity) {
        Meeting m = requireHost(meetingId, user.id());
        roomControl.kick(m.getCode(), identity);
    }

    @PostMapping("/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void end(@AuthenticationPrincipal AuthenticatedUser user,
                    @PathVariable UUID meetingId) {
        Meeting m = requireHost(meetingId, user.id());
        m.setStatus(MeetingStatus.ENDED);
        m.setUpdatedAt(Instant.now());
        roomControl.endRoom(m.getCode());
    }

    private void changeRole(AuthenticatedUser user, UUID meetingId, String identity,
                            MeetingRole newRole) {
        Meeting m = requireHost(meetingId, user.id());
        // identity của user đăng nhập = userId; guest (guest:*) không promote được
        if (!identity.startsWith("guest:")) {
            UUID targetUserId = UUID.fromString(identity);
            MeetingMember mm = members.findByMeetingIdAndUserId(meetingId, targetUserId)
                    .orElseGet(() -> {
                        MeetingMember fresh = new MeetingMember();
                        fresh.setMeetingId(meetingId);
                        fresh.setUserId(targetUserId);
                        fresh.setInvitedEmail("(promoted-in-room)");
                        fresh.setInvitedBy(user.id());
                        return fresh;
                    });
            mm.setRole(newRole);
            members.save(mm);
        }
        roomControl.setRole(m.getCode(), identity, newRole);
    }

    private Meeting requireHost(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (!m.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    ErrorCode.NOT_MEETING_HOST, "Chỉ host mới được thao tác");
        }
        return m;
    }
}
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): host controls mute/promote/demote/kick/end via roomservice"
```

---

### Task 8: Chat STOMP — send/persist/relay qua Redis

**Files:**
- Create: `backend/src/main/java/com/meetly/chat/WebSocketConfig.java`, `StompAuthChannelInterceptor.java`, `ChatAccessGuard.java`, `ChatController.java`, `ChatService.java`, `ChatDtos.java`, `RedisChatRelay.java`
- Modify: `backend/src/main/java/com/meetly/common/SecurityConfig.java` (permitAll `/ws/**`)
- Test: `backend/src/test/java/com/meetly/chat/ChatStompIT.java`

**Interfaces:**
- Produces: WS endpoint `/ws` (auth = header STOMP CONNECT `Authorization: Bearer <access|guest token>`); SEND `/app/meetings/{meetingId}/chat` body `SendChatRequest(String content, ChatMessageType type)` (type chỉ TEXT/RAISE_HAND); SUBSCRIBE `/topic/meetings/{meetingId}/chat` nhận `ChatEvent(String kind, ChatMessageDto message, UUID messageId)` — kind `MESSAGE` | `MESSAGE_DELETED`. `ChatMessageDto(UUID id, UUID meetingId, String senderIdentity, String senderDisplayName, String content, String type, Instant createdAt)`.
- **`ChatAccessGuard.check(Object principal, UUID meetingId): Meeting`** — nguồn sự thật duy nhất cho quyền chat, dùng ở CẢ 3 chỗ: interceptor khi SUBSCRIBE (chặn nghe lén topic phòng khác), `ChatService.saveAndPublish` khi gửi, `ChatRestController` khi đọc history (Task 9). Luật: guest chỉ đúng phòng trong token; user phải là host/member hoặc phòng WEBINAR; sai → 403 `NOT_A_MEMBER`.
- Luồng: handler → `ChatService.saveAndPublish` (guard check) → lưu Postgres → `StringRedisTemplate.convertAndSend("chat:"+meetingId, json(ChatEvent))` → `RedisChatRelay` (MessageListener pattern `chat:*`) → `SimpMessagingTemplate.convertAndSend("/topic/meetings/{id}/chat", event)`. Nhờ đó nhiều pod cùng nhận (spec D4).

- [ ] **Step 1: Viết test fail** (STOMP client thật, full stack)

`backend/src/test/java/com/meetly/chat/ChatStompIT.java`:

```java
package com.meetly.chat;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ChatStompIT {
    @LocalServerPort int port;
    @Autowired MockMvc mvc;

    @Test
    void sendReceivePersistViaStomp() throws Exception {
        // chuẩn bị: user + webinar
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"chat+%d@meetly.dev","password":"secret123","fullName":"Chatter"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        String access = read(reg, "$.accessToken");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + access)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Chat room","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        String meetingId = read(created, "$.id");

        // STOMP connect
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + access);
        StompSession session = stomp.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);

        BlockingQueue<Map<String, Object>> received = new ArrayBlockingQueue<>(4);
        session.subscribe("/topic/meetings/" + meetingId + "/chat", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Map<String, Object>) payload);
            }
        });
        Thread.sleep(500); // chờ subscribe ổn định

        session.send("/app/meetings/" + meetingId + "/chat",
                Map.of("content", "Xin chào webinar", "type", "TEXT"));

        Map<String, Object> event = received.poll(10, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("kind")).isEqualTo("MESSAGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) event.get("message");
        assertThat(msg.get("content")).isEqualTo("Xin chào webinar");
        assertThat(msg.get("senderDisplayName")).isEqualTo("Chatter");

        session.disconnect();
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=ChatStompIT` → FAIL.

`com/meetly/chat/ChatDtos.java`:

```java
package com.meetly.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class ChatDtos {
    public record SendChatRequest(@NotBlank @Size(max = 2000) String content,
                                  ChatMessageType type) {}

    public record ChatMessageDto(UUID id, UUID meetingId, String senderIdentity,
                                 String senderDisplayName, String content, String type,
                                 Instant createdAt) {
        static ChatMessageDto from(ChatMessage m) {
            return new ChatMessageDto(m.getId(), m.getMeetingId(), m.getSenderIdentity(),
                    m.getSenderDisplayName(), m.getContent(), m.getType().name(), m.getCreatedAt());
        }
    }

    public record ChatEvent(String kind, ChatMessageDto message, UUID messageId) {
        public static ChatEvent message(ChatMessageDto dto) { return new ChatEvent("MESSAGE", dto, null); }
        public static ChatEvent deleted(UUID id) { return new ChatEvent("MESSAGE_DELETED", null, id); }
    }
}
```

`com/meetly/chat/WebSocketConfig.java`:

```java
package com.meetly.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompAuthChannelInterceptor authInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
```

`com/meetly/chat/ChatAccessGuard.java` (nguồn sự thật duy nhất cho quyền chat — vá lỗ hổng nghe lén/đọc trộm phòng khác):

```java
package com.meetly.chat;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.auth.GuestUser;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import com.meetly.meeting.MemberService;
import com.meetly.meeting.RoomType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChatAccessGuard {
    private final MeetingRepository meetings;
    private final MemberService memberService;

    /**
     * Quyền chat của một principal với một meeting — dùng cho SUBSCRIBE, gửi tin, đọc history.
     * Guest: chỉ phòng trong token. User: host/member, hoặc phòng WEBINAR (mở).
     * @throws ApiException 404 nếu meeting không tồn tại; 403 nếu không có quyền.
     */
    @Transactional
    public Meeting check(Object principal, UUID meetingId) {
        Meeting meeting = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (principal instanceof GuestUser g) {
            if (!g.meetingId().equals(meetingId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "Guest token không thuộc phòng này");
            }
            return meeting;
        }
        if (principal instanceof AuthenticatedUser u) {
            boolean allowed = meeting.getRoomType() == RoomType.WEBINAR
                    || memberService.resolveRole(meeting, u.id(), u.email()).isPresent();
            if (!allowed) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "Bạn không thuộc phòng họp này");
            }
            return meeting;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                "Không xác thực được");
    }
}
```

`com/meetly/chat/StompAuthChannelInterceptor.java` — xác thực ở CONNECT **và kiểm tra quyền ở SUBSCRIBE** (không có check này, guest phòng A subscribe được topic phòng B):

```java
package com.meetly.chat;

import com.meetly.auth.GuestUser;
import com.meetly.auth.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private static final Pattern CHAT_TOPIC =
            Pattern.compile("^/topic/meetings/([0-9a-fA-F-]{36})/chat$");

    private final JwtService jwtService;
    private final ChatAccessGuard accessGuard;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Thiếu Authorization header khi CONNECT");
            }
            Object principal = jwtService.parsePrincipal(header.substring(7));
            String role = principal instanceof GuestUser ? "ROLE_GUEST" : "ROLE_USER";
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(role))));
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            Matcher m = destination != null ? CHAT_TOPIC.matcher(destination) : null;
            if (m == null || !m.matches()) {
                throw new IllegalArgumentException("Destination không hợp lệ: " + destination);
            }
            var auth = (UsernamePasswordAuthenticationToken) accessor.getUser();
            if (auth == null) throw new IllegalArgumentException("Chưa xác thực khi SUBSCRIBE");
            accessGuard.check(auth.getPrincipal(), UUID.fromString(m.group(1)));
        }
        return message;
    }
}
```

`com/meetly/chat/ChatService.java`:

```java
package com.meetly.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetly.auth.AuthenticatedUser;
import com.meetly.auth.GuestUser;
import com.meetly.chat.ChatDtos.ChatEvent;
import com.meetly.chat.ChatDtos.ChatMessageDto;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatMessageRepository chatMessages;
    private final MeetingRepository meetings;
    private final UserRepository users;
    private final ChatAccessGuard accessGuard;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveAndPublish(UUID meetingId, Object principal, String content,
                               ChatMessageType type) {
        accessGuard.check(principal, meetingId);   // 404/403 nếu không thuộc phòng
        String identity;
        String displayName;
        if (principal instanceof GuestUser g) {
            identity = g.identity();
            displayName = g.displayName();
        } else {
            AuthenticatedUser u = (AuthenticatedUser) principal;
            identity = u.id().toString();
            displayName = users.findById(u.id()).orElseThrow().getFullName();
        }

        ChatMessage msg = new ChatMessage();
        msg.setMeetingId(meetingId);
        msg.setSenderIdentity(identity);
        msg.setSenderDisplayName(displayName);
        msg.setContent(content);
        msg.setType(type != null ? type : ChatMessageType.TEXT);
        chatMessages.save(msg);

        publish(meetingId, ChatEvent.message(ChatMessageDto.from(msg)));
    }

    @Transactional
    public void deleteMessage(UUID meetingId, UUID messageId, UUID actorId) {
        Meeting meeting = meetings.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        if (!meeting.getHostId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_MEETING_HOST,
                    "Chỉ host mới được xóa tin nhắn");
        }
        ChatMessage msg = chatMessages.findById(messageId)
                .filter(m -> m.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn"));
        msg.setDeletedAt(Instant.now());
        msg.setDeletedBy(actorId.toString());
        publish(meetingId, ChatEvent.deleted(messageId));
    }

    void publish(UUID meetingId, ChatEvent event) {
        try {
            redis.convertAndSend("chat:" + meetingId, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`com/meetly/chat/RedisChatRelay.java`:

```java
package com.meetly.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetly.chat.ChatDtos.ChatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisChatRelay {
    private final SimpMessagingTemplate simp;
    private final ObjectMapper objectMapper;

    @Bean
    RedisMessageListenerContainer chatListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(chatListener(), new PatternTopic("chat:*"));
        return container;
    }

    MessageListener chatListener() {
        return (Message message, byte[] pattern) -> {
            try {
                String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                String meetingId = channel.substring("chat:".length());
                ChatEvent event = objectMapper.readValue(message.getBody(), ChatEvent.class);
                simp.convertAndSend("/topic/meetings/" + meetingId + "/chat", event);
            } catch (Exception e) {
                log.error("Relay chat event failed", e);
            }
        };
    }
}
```

`com/meetly/chat/ChatController.java` (phần STOMP):

```java
package com.meetly.chat;

import com.meetly.chat.ChatDtos.SendChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;

    @MessageMapping("/meetings/{meetingId}/chat")
    public void send(@DestinationVariable UUID meetingId,
                     @Payload SendChatRequest req,
                     Principal principal) {
        Object user = ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        ChatMessageType type = req.type() == ChatMessageType.RAISE_HAND
                ? ChatMessageType.RAISE_HAND : ChatMessageType.TEXT;
        chatService.saveAndPublish(meetingId, user, req.content(), type);
    }
}
```

Thêm vào `SecurityConfig` permitAll matchers:

```java
                .requestMatchers("/ws/**").permitAll()
```

(Handshake mở; xác thực thật nằm ở STOMP CONNECT interceptor.)

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test -Dtest=ChatStompIT`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): stomp chat with redis relay and persistence"
```

---

### Task 9: Chat history + moderation REST

**Files:**
- Create: `backend/src/main/java/com/meetly/chat/ChatRestController.java`
- Test: `backend/src/test/java/com/meetly/chat/ChatRestIT.java`

**Interfaces:**
- Produces: `GET /api/v1/meetings/{id}/messages?before=<ISO>&after=<ISO>&limit=50` → `List<ChatMessageDto>` tăng dần theo createdAt, loại tin đã xóa; guest gọi được (đúng meeting); `DELETE /api/v1/meetings/{id}/messages/{msgId}` (host) → 204 + broadcast `MESSAGE_DELETED` (qua `ChatService.deleteMessage` Task 8).

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/chat/ChatRestIT.java`:

```java
package com.meetly.chat;

import com.meetly.TestcontainersConfig;
import com.meetly.chat.ChatDtos.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.jayway.jsonpath.JsonPath.read;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ChatRestIT {
    @Autowired MockMvc mvc;
    @Autowired ChatService chatService;
    @Autowired com.meetly.auth.JwtService jwtService;

    private String hostToken;
    private UUID hostId;
    private String meetingId;

    @BeforeEach
    void setUp() throws Exception {
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"cr+%d@meetly.dev","password":"secret123","fullName":"H"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        hostToken = read(reg, "$.accessToken");
        hostId = UUID.fromString(read(reg, "$.user.id"));
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"History","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
    }

    @Test
    void historyDeleteAndGuestAccess() throws Exception {
        var principal = new com.meetly.auth.AuthenticatedUser(hostId, "x@y.z");
        chatService.saveAndPublish(UUID.fromString(meetingId), principal, "msg 1", ChatMessageType.TEXT);
        chatService.saveAndPublish(UUID.fromString(meetingId), principal, "msg 2", ChatMessageType.TEXT);

        // history
        String list = mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String msg1Id = read(list, "$[0].id");

        // host xóa msg 1 → còn 1
        mvc.perform(delete("/api/v1/meetings/" + meetingId + "/messages/" + msg1Id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("msg 2"));

        // guest token đọc được history phòng mình
        String guestJwt = jwtService.generateGuestToken(UUID.fromString(meetingId),
                "guest:abc", "Khách", java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + guestJwt))
                .andExpect(status().isOk());

        // guest token thuộc phòng KHÁC (meetingId ngẫu nhiên) gọi vào phòng này → 403
        String otherGuest = jwtService.generateGuestToken(UUID.randomUUID(),
                "guest:zzz", "Khách", java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/messages")
                        .header("Authorization", "Bearer " + otherGuest))
                .andExpect(status().isForbidden());
    }

    @Test
    void strangerCannotReadPrivateMeetingHistory() throws Exception {
        // phòng KÍN (MEETING) — user đăng nhập nhưng không phải member → 403
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Private","roomType":"MEETING"}"""))
                .andReturn().getResponse().getContentAsString();
        String privateId = read(created, "$.id");

        String otherReg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"cs+%d@meetly.dev","password":"secret123","fullName":"S"}"""
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        String strangerToken = read(otherReg, "$.accessToken");

        mvc.perform(get("/api/v1/meetings/" + privateId + "/messages")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));

        // guest token của phòng KHÁC gọi vào phòng này → 403
        String foreignGuest = jwtService.generateGuestToken(
                UUID.fromString(meetingId), "guest:zzz", "Khách",
                java.time.Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/meetings/" + privateId + "/messages")
                        .header("Authorization", "Bearer " + foreignGuest))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=ChatRestIT` → FAIL.

`com/meetly/chat/ChatRestController.java`:

```java
package com.meetly.chat;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.chat.ChatDtos.ChatMessageDto;
import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/messages")
@RequiredArgsConstructor
public class ChatRestController {
    private final ChatMessageRepository chatMessages;
    private final ChatService chatService;
    private final ChatAccessGuard accessGuard;

    @GetMapping
    public List<ChatMessageDto> history(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID meetingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant after,
            @RequestParam(defaultValue = "50") int limit) {
        accessGuard.check(principal, meetingId);   // cùng luật với SUBSCRIBE/gửi tin
        List<ChatMessage> page;
        if (after != null) {
            page = chatMessages.findByMeetingIdAndCreatedAtAfterOrderByCreatedAtAsc(meetingId, after);
        } else {
            page = chatMessages.findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                    meetingId, before != null ? before : Instant.now(),
                    PageRequest.of(0, Math.min(limit, 200)));
        }
        return page.stream()
                .filter(m -> m.getDeletedAt() == null)
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .map(ChatMessageDto::from)
                .toList();
    }

    @DeleteMapping("/{msgId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Object principal,
                                       @PathVariable UUID meetingId, @PathVariable UUID msgId) {
        if (!(principal instanceof AuthenticatedUser user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_MEETING_HOST,
                    "Chỉ host mới được xóa tin nhắn");
        }
        chatService.deleteMessage(meetingId, msgId, user.id());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ.

```bash
git add backend/src
git commit -m "feat(be): chat history + host moderation"
```

---

### Task 10: FE — guest flow + types cập nhật

**Files:**
- Modify: `frontend/src/api/types.ts`, `frontend/src/App.tsx`, `frontend/src/features/room/PreJoinPage.tsx`, `frontend/src/features/room/RoomPage.tsx`, `frontend/src/features/room/roomApi.ts`
- Create: `frontend/src/stores/roomStore.ts`
- Test: `frontend/src/stores/roomStore.test.ts`

**Interfaces:**
- Produces: `JoinResponse` type thêm `chatToken: string | null`, `role: 'HOST'|'SPEAKER'|'ATTENDEE'`; `useRoomStore` giữ `{ join: JoinResponse | null, displayName: string | null, setJoin, clear }`; routes `/m/:code` + `/m/:code/room` chuyển RA NGOÀI `ProtectedRoute` — user chưa đăng nhập vẫn mở được, PreJoin yêu cầu nhập tên (guest), join gửi `{displayName}` không kèm auth header.
- `useJoinMeeting` đổi thành `mutationFn: ({code, displayName?}) => JoinResponse`.

- [ ] **Step 1: Viết test roomStore (fail)**

`frontend/src/stores/roomStore.test.ts`:

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { useRoomStore } from './roomStore';

describe('roomStore', () => {
  beforeEach(() => useRoomStore.getState().clear());

  it('lưu join info', () => {
    useRoomStore.getState().setJoin({
      livekitUrl: 'ws://x', livekitToken: 't', role: 'ATTENDEE', chatToken: 'ct',
    });
    expect(useRoomStore.getState().join?.role).toBe('ATTENDEE');
  });

  it('clear reset', () => {
    useRoomStore.getState().setJoin({
      livekitUrl: 'ws://x', livekitToken: 't', role: 'HOST', chatToken: null,
    });
    useRoomStore.getState().clear();
    expect(useRoomStore.getState().join).toBeNull();
  });
});
```

Run: `cd frontend && npm test` → FAIL.

- [ ] **Step 2: Implement**

Sửa `frontend/src/api/types.ts` — thay `JoinResponse`:

```ts
export type MeetingRole = 'HOST' | 'SPEAKER' | 'ATTENDEE';

export type JoinResponse = {
  livekitUrl: string;
  livekitToken: string;
  role: MeetingRole;
  chatToken: string | null;
};

export type ChatMessageDto = {
  id: string;
  meetingId: string;
  senderIdentity: string;
  senderDisplayName: string;
  content: string;
  type: 'TEXT' | 'SYSTEM' | 'RAISE_HAND';
  createdAt: string;
};

export type ChatEvent =
  | { kind: 'MESSAGE'; message: ChatMessageDto; messageId: null }
  | { kind: 'MESSAGE_DELETED'; message: null; messageId: string };
```

`frontend/src/stores/roomStore.ts`:

```ts
import { create } from 'zustand';
import type { JoinResponse } from '../api/types';

type RoomState = {
  join: JoinResponse | null;
  displayName: string | null;
  setJoin: (join: JoinResponse) => void;
  setDisplayName: (name: string) => void;
  clear: () => void;
};

export const useRoomStore = create<RoomState>((set) => ({
  join: null,
  displayName: null,
  setJoin: (join) => set({ join }),
  setDisplayName: (displayName) => set({ displayName }),
  clear: () => set({ join: null, displayName: null }),
}));
```

Sửa `frontend/src/features/room/roomApi.ts`:

```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { JoinResponse } from '../../api/types';

export function useJoinMeeting() {
  return useMutation({
    mutationFn: async (input: { code: string; displayName?: string }) =>
      (await api.post<JoinResponse>(`/meetings/${input.code}/join`,
        input.displayName ? { displayName: input.displayName } : undefined)).data,
  });
}
```

Sửa `frontend/src/App.tsx` — chuyển 2 route room ra ngoài `ProtectedRoute`:

```tsx
          <Route path="/m/:code" element={<PreJoinPage />} />
          <Route path="/m/:code/room" element={<RoomPage />} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<DashboardPage />} />
          </Route>
```

Sửa `PreJoinPage.tsx` — guest nhập tên (dùng chính field username của `PreJoin`), lưu vào roomStore:

```tsx
import { PreJoin } from '@livekit/components-react';
import '@livekit/components-styles';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';

export function PreJoinPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const setDisplayName = useRoomStore((s) => s.setDisplayName);

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center" data-lk-theme="default">
      <div className="w-full max-w-2xl">
        <h1 className="text-white text-center text-xl mb-4">Phòng {code}</h1>
        {!user && (
          <p className="text-gray-300 text-center text-sm mb-2">
            Bạn đang vào với tư cách khách — nhập tên hiển thị bên dưới
          </p>
        )}
        <PreJoin
          defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
          joinLabel="Vào phòng"
          micLabel="Micro"
          camLabel="Camera"
          onSubmit={(choices) => {
            if (!user && !choices.username.trim()) return;
            setDisplayName(choices.username.trim());
            navigate(`/m/${code}/room`, {
              state: {
                videoEnabled: choices.videoEnabled,
                audioEnabled: choices.audioEnabled,
              },
            });
          }}
        />
      </div>
    </div>
  );
}
```

Sửa `RoomPage.tsx` — join có displayName khi là guest, lưu join vào roomStore (ChatPanel Task 12 cần):

```tsx
import { useEffect } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import '@livekit/components-styles';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useJoinMeeting } from './roomApi';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';

type PreJoinChoices = { videoEnabled?: boolean; audioEnabled?: boolean };

export function RoomPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const choices = (useLocation().state ?? {}) as PreJoinChoices;
  const user = useAuthStore((s) => s.user);
  const { displayName, setJoin, clear } = useRoomStore();
  const join = useJoinMeeting();

  useEffect(() => {
    if (!code) return;
    join.mutate(
      { code, displayName: user ? undefined : (displayName ?? undefined) },
      { onSuccess: (data) => setJoin(data) },
    );
    return () => clear();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  if (join.isError) {
    const errCode = isAxiosError(join.error) ? join.error.response?.data?.code : null;
    const detail =
      errCode === 'MEETING_NOT_STARTED' ? 'Phòng họp chưa bắt đầu. Quay lại sau nhé.'
      : errCode === 'MEETING_ENDED' ? 'Phòng họp đã kết thúc hoặc bị hủy.'
      : errCode === 'NOT_A_MEMBER' ? 'Bạn không được mời vào phòng họp này.'
      : errCode === 'GUEST_MEETING_FORBIDDEN' ? 'Phòng này yêu cầu đăng nhập.'
      : errCode === 'DISPLAY_NAME_REQUIRED' ? 'Vui lòng quay lại nhập tên hiển thị.'
      : 'Không vào được phòng họp.';
    return (
      <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center gap-4 text-white">
        <p>{detail}</p>
        <button onClick={() => navigate(user ? '/' : `/m/${code}`)}
                className="bg-blue-600 rounded-lg px-4 py-2">
          Quay lại
        </button>
      </div>
    );
  }

  if (!join.data) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center text-white">
        Đang kết nối...
      </div>
    );
  }

  return (
    <div className="h-screen" data-lk-theme="default">
      <LiveKitRoom
        serverUrl={join.data.livekitUrl}
        token={join.data.livekitToken}
        connect
        video={join.data.role !== 'ATTENDEE' && (choices.videoEnabled ?? true)}
        audio={join.data.role !== 'ATTENDEE' && (choices.audioEnabled ?? true)}
        onDisconnected={() => navigate(user ? '/' : `/m/${code}`)}
      >
        <VideoConference />
      </LiveKitRoom>
    </div>
  );
}
```

(`VideoConference` sẽ được thay bằng room UI tự ghép ở Task 11.)

- [ ] **Step 3: Chạy test + verify, commit**

Run: `cd frontend && npm test` → PASS.
Manual: cửa sổ ẩn danh (không đăng nhập) mở `/m/<code>` của 1 WEBINAR → nhập tên → vào phòng xem được, không bật được cam.

```bash
git add frontend/
git commit -m "feat(fe): guest join flow + room store"
```

---

### Task 11: FE — Room UI tự ghép theo role (layout, control bar, participant list)

**Files:**
- Create: `frontend/src/features/room/RoomLayout.tsx`, `ControlBar.tsx`, `ParticipantList.tsx`, `controlApi.ts`
- Modify: `frontend/src/features/room/RoomPage.tsx` (thay `VideoConference`)

**Interfaces:**
- Consumes: LiveKit hooks `useTracks`, `useParticipants`, `useLocalParticipant`, `RoomEvent.ParticipantPermissionsChanged`; REST controls Task 7; `useRoomStore.join.role`.
- Produces: `<RoomLayout meetingId code role>` gồm: vùng video (GridLayout; FocusLayout khi có screen share), `<ControlBar role>` (ATTENDEE không có nút mic/cam/share; có nút giơ tay + rời phòng; HOST thêm End), `<ParticipantList meetingId>` (host thấy nút Mute/Promote/Demote/Kick từng người); `useControlActions(meetingId)` → `{mute(identity), promote(identity), demote(identity), kick(identity), end()}`. Khi permission đổi (được promote), local user thấy toast + nút publish xuất hiện.

- [ ] **Step 1: Implement `controlApi.ts`**

```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';

export function useControlActions(meetingId: string) {
  const act = (action: string) =>
    useMutation({
      mutationFn: async (identity: string) =>
        api.post(`/meetings/${meetingId}/participants/${identity}/${action}`),
    });
  // eslint-disable-next-line react-hooks/rules-of-hooks
  const mute = act('mute'); const promote = act('promote');
  // eslint-disable-next-line react-hooks/rules-of-hooks
  const demote = act('demote'); const kick = act('kick');
  const end = useMutation({ mutationFn: async () => api.post(`/meetings/${meetingId}/end`) });
  return { mute, promote, demote, kick, end };
}
```

*Lưu ý reviewer: 4 lần gọi `act(...)` là 4 hook cố định mỗi render — thứ tự không đổi nên hợp lệ, nhưng nếu lint gắt thì viết thẳng 4 `useMutation` không qua helper.*

- [ ] **Step 2: Implement `ControlBar.tsx`**

```tsx
import { TrackToggle, DisconnectButton, useLocalParticipant } from '@livekit/components-react';
import { Track } from 'livekit-client';
import type { MeetingRole } from '../../api/types';

type Props = {
  role: MeetingRole;
  onRaiseHand: () => void;
  onEnd?: () => void;
};

export function ControlBar({ role, onRaiseHand, onEnd }: Props) {
  const { localParticipant } = useLocalParticipant();
  // quyền thật đến từ server (có thể vừa được promote runtime)
  const canPublish = localParticipant.permissions?.canPublish ?? role !== 'ATTENDEE';

  return (
    <div className="flex items-center justify-center gap-3 bg-gray-800 px-4 py-3">
      {canPublish && (
        <>
          <TrackToggle source={Track.Source.Microphone} className="lk-button" />
          <TrackToggle source={Track.Source.Camera} className="lk-button" />
          <TrackToggle source={Track.Source.ScreenShare} className="lk-button" />
        </>
      )}
      <button onClick={onRaiseHand}
              className="bg-yellow-500 text-black rounded-lg px-3 py-2 text-sm font-medium">
        ✋ Giơ tay
      </button>
      {role === 'HOST' && onEnd && (
        <button onClick={onEnd}
                className="bg-red-700 text-white rounded-lg px-3 py-2 text-sm font-medium">
          Kết thúc họp
        </button>
      )}
      <DisconnectButton className="lk-button">Rời phòng</DisconnectButton>
    </div>
  );
}
```

- [ ] **Step 3: Implement `ParticipantList.tsx`**

```tsx
import { useParticipants } from '@livekit/components-react';
import type { MeetingRole } from '../../api/types';
import { useControlActions } from './controlApi';

type Props = { meetingId: string; role: MeetingRole };

export function ParticipantList({ meetingId, role }: Props) {
  const participants = useParticipants();
  const { mute, promote, demote, kick } = useControlActions(meetingId);
  const isHost = role === 'HOST';

  return (
    <div className="h-full overflow-y-auto">
      <h3 className="px-3 py-2 text-sm font-semibold text-gray-300">
        Người tham gia ({participants.length})
      </h3>
      {participants.map((p) => (
        <div key={p.identity}
             className="px-3 py-2 flex items-center justify-between text-sm text-gray-100">
          <span>
            {p.name || p.identity}
            {p.isLocal && ' (bạn)'}
            {p.permissions?.canPublish === false && ' 👁'}
          </span>
          {isHost && !p.isLocal && (
            <span className="flex gap-1">
              <button title="Mute" onClick={() => mute.mutate(p.identity)}
                      className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">🔇</button>
              {p.permissions?.canPublish === false ? (
                <button title="Cho phát biểu" onClick={() => promote.mutate(p.identity)}
                        className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">🎤</button>
              ) : (
                <button title="Hạ xuống khán giả" onClick={() => demote.mutate(p.identity)}
                        className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">⬇️</button>
              )}
              <button title="Mời ra" onClick={() => kick.mutate(p.identity)}
                      className="px-1.5 rounded bg-red-900 hover:bg-red-800">✖</button>
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Implement `RoomLayout.tsx` + thay vào `RoomPage`**

`RoomLayout.tsx`:

```tsx
import { useEffect, useState } from 'react';
import {
  GridLayout, FocusLayout, ParticipantTile, RoomAudioRenderer,
  useTracks, useRoomContext, useConnectionState,
} from '@livekit/components-react';
import { ConnectionState, RoomEvent, Track } from 'livekit-client';
import type { MeetingRole } from '../../api/types';
import { ControlBar } from './ControlBar';
import { ParticipantList } from './ParticipantList';
import { useControlActions } from './controlApi';

type Props = { meetingId: string; role: MeetingRole };

export function RoomLayout({ meetingId, role }: Props) {
  const room = useRoomContext();
  const connectionState = useConnectionState();
  const [promotedToast, setPromotedToast] = useState(false);
  const { end } = useControlActions(meetingId);

  const tracks = useTracks(
    [
      { source: Track.Source.Camera, withPlaceholder: true },
      { source: Track.Source.ScreenShare, withPlaceholder: false },
    ],
    { onlySubscribed: false },
  );
  const screenShare = tracks.find((t) => t.source === Track.Source.ScreenShare);

  useEffect(() => {
    const onPermChanged = () => {
      if (room.localParticipant.permissions?.canPublish) {
        setPromotedToast(true);
        setTimeout(() => setPromotedToast(false), 5000);
      }
    };
    room.on(RoomEvent.ParticipantPermissionsChanged, onPermChanged);
    return () => { room.off(RoomEvent.ParticipantPermissionsChanged, onPermChanged); };
  }, [room]);

  return (
    <div className="h-screen flex flex-col bg-gray-900">
      {connectionState === ConnectionState.Reconnecting && (
        <div className="bg-yellow-600 text-black text-center text-sm py-1">
          Đang kết nối lại...
        </div>
      )}
      {promotedToast && (
        <div className="bg-green-600 text-white text-center text-sm py-1">
          Bạn đã được cấp quyền phát biểu 🎤
        </div>
      )}
      <div className="flex-1 flex min-h-0">
        <div className="flex-1 min-w-0">
          {screenShare ? (
            <FocusLayout trackRef={screenShare} className="h-full" />
          ) : (
            <GridLayout tracks={tracks} className="h-full">
              <ParticipantTile />
            </GridLayout>
          )}
        </div>
        <aside className="w-72 bg-gray-850 border-l border-gray-700 flex flex-col">
          <ParticipantList meetingId={meetingId} role={role} />
        </aside>
      </div>
      <ControlBar role={role} onRaiseHand={() => { /* nối ChatPanel ở Task 12 */ }}
                  onEnd={role === 'HOST' ? () => end.mutate() : undefined} />
      <RoomAudioRenderer />
    </div>
  );
}
```

Trong `RoomPage.tsx`, thay `<VideoConference />` bằng:

```tsx
        <RoomLayout meetingId={join.data.meetingId} role={join.data.role} />
```

`meetingId` chưa có trong `JoinResponse` — **sửa BE nhỏ trong task này**: thêm field `meetingId` vào `JoinResponse` (BE `MeetingDtos.JoinResponse(UUID meetingId, String livekitUrl, String livekitToken, String role, String chatToken)`, set trong cả `join` và `joinAsGuest`); FE type `JoinResponse` thêm `meetingId: string`. Cập nhật test `JoinApiIT` assert `$.meetingId` không rỗng.

- [ ] **Step 5: Chạy test + commit**

Run: `cd frontend && npm test && cd ../backend && ./mvnw -q test`
Expected: PASS cả hai.

```bash
git add frontend/ backend/
git commit -m "feat(fe): custom room layout with role-aware controls"
```

---

### Task 12: FE — ChatPanel (STOMP client)

**Files:**
- Create: `frontend/src/features/room/useChatSocket.ts`, `frontend/src/features/room/ChatPanel.tsx`
- Modify: `frontend/src/features/room/RoomLayout.tsx` (nối raise hand), `frontend/vite.config.ts` (proxy `/ws`)
- Test: `frontend/src/features/room/useChatSocket.test.ts`

**Interfaces:**
- Produces: `useChatSocket(meetingId, token)` → `{ messages: ChatMessageDto[], connected: boolean, send(content, type?), removeLocal(messageId) }` — connect STOMP `ws(s)://<origin>/ws` (`@stomp/stompjs` Client, `connectHeaders: {Authorization}`, `reconnectDelay: 3000`); khi (re)connect: fetch history REST (`after=<createdAt tin cuối>` nếu đã có tin) rồi merge; sự kiện `MESSAGE_DELETED` → xóa khỏi list. Token = guest `chatToken` hoặc access token (lấy `useAuthStore.accessToken`).
- `ChatPanel({meetingId, role, registerRaiseHand?})` — hiển thị list + input; tin `RAISE_HAND` render nổi bật "✋ <tên> giơ tay"; host hover tin → nút xóa (REST DELETE).

- [ ] **Step 1: Viết test merge logic (fail)** — tách hàm thuần `mergeMessages` để test không cần WS:

`frontend/src/features/room/useChatSocket.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { mergeMessages } from './useChatSocket';
import type { ChatMessageDto } from '../../api/types';

const msg = (id: string, at: string): ChatMessageDto => ({
  id, meetingId: 'm', senderIdentity: 's', senderDisplayName: 'S',
  content: id, type: 'TEXT', createdAt: at,
});

describe('mergeMessages', () => {
  it('gộp không trùng id, sort theo createdAt', () => {
    const current = [msg('a', '2026-07-18T10:00:00Z'), msg('b', '2026-07-18T10:01:00Z')];
    const incoming = [msg('b', '2026-07-18T10:01:00Z'), msg('c', '2026-07-18T10:00:30Z')];
    const out = mergeMessages(current, incoming);
    expect(out.map((m) => m.id)).toEqual(['a', 'c', 'b']);
  });
});
```

Run: `cd frontend && npm test` → FAIL.

- [ ] **Step 2: Implement `useChatSocket.ts`**

```ts
import { useCallback, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api } from '../../api/client';
import type { ChatEvent, ChatMessageDto } from '../../api/types';

export function mergeMessages(
  current: ChatMessageDto[], incoming: ChatMessageDto[],
): ChatMessageDto[] {
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
  );
}

export function useChatSocket(meetingId: string, token: string | null) {
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const lastCreatedAtRef = useRef<string | null>(null);

  useEffect(() => {
    lastCreatedAtRef.current = messages.length
      ? messages[messages.length - 1].createdAt : null;
  }, [messages]);

  useEffect(() => {
    if (!token) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/meetings/${meetingId}/chat`, (frame) => {
          const event = JSON.parse(frame.body) as ChatEvent;
          if (event.kind === 'MESSAGE' && event.message) {
            setMessages((cur) => mergeMessages(cur, [event.message!]));
          } else if (event.kind === 'MESSAGE_DELETED' && event.messageId) {
            setMessages((cur) => cur.filter((m) => m.id !== event.messageId));
          }
        });
        // bù tin nhắn bị lỡ (lần đầu: 50 tin gần nhất)
        const params = lastCreatedAtRef.current
          ? { after: lastCreatedAtRef.current } : { limit: 50 };
        void api
          .get<ChatMessageDto[]>(`/meetings/${meetingId}/messages`, {
            params,
            headers: { Authorization: `Bearer ${token}` },
          })
          .then(({ data }) => setMessages((cur) => mergeMessages(cur, data)));
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;
    return () => { void client.deactivate(); clientRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [meetingId, token]);

  const send = useCallback(
    (content: string, type: 'TEXT' | 'RAISE_HAND' = 'TEXT') => {
      clientRef.current?.publish({
        destination: `/app/meetings/${meetingId}/chat`,
        body: JSON.stringify({ content, type }),
      });
    },
    [meetingId],
  );

  const removeLocal = useCallback((messageId: string) => {
    setMessages((cur) => cur.filter((m) => m.id !== messageId));
  }, []);

  return { messages, connected, send, removeLocal };
}
```

Ghi chú: header `Authorization` truyền tường minh trong GET history để guest (không có access token trong authStore) vẫn đọc được bằng `chatToken`.

- [ ] **Step 3: Implement `ChatPanel.tsx`**

```tsx
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { MeetingRole } from '../../api/types';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';
import { useChatSocket } from './useChatSocket';

type Props = {
  meetingId: string;
  role: MeetingRole;
  registerRaiseHand?: (fn: () => void) => void;
};

export function ChatPanel({ meetingId, role, registerRaiseHand }: Props) {
  const accessToken = useAuthStore((s) => s.accessToken);
  const chatToken = useRoomStore((s) => s.join?.chatToken ?? null);
  const token = chatToken ?? accessToken;
  const { messages, connected, send, removeLocal } = useChatSocket(meetingId, token);
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    registerRaiseHand?.(() => send('giơ tay', 'RAISE_HAND'));
  }, [registerRaiseHand, send]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!draft.trim()) return;
    send(draft.trim());
    setDraft('');
  }

  async function onDelete(messageId: string) {
    await api.delete(`/meetings/${meetingId}/messages/${messageId}`);
    removeLocal(messageId);
  }

  return (
    <div className="h-full flex flex-col">
      <h3 className="px-3 py-2 text-sm font-semibold text-gray-300">
        Chat {connected ? '' : '(đang kết nối...)'}
      </h3>
      <div className="flex-1 overflow-y-auto px-3 space-y-1">
        {messages.map((m) =>
          m.type === 'RAISE_HAND' ? (
            <p key={m.id} className="text-yellow-400 text-sm">
              ✋ <b>{m.senderDisplayName}</b> giơ tay
            </p>
          ) : (
            <p key={m.id} className="text-sm text-gray-100 group">
              <b className="text-gray-400">{m.senderDisplayName}:</b> {m.content}
              {role === 'HOST' && (
                <button onClick={() => void onDelete(m.id)}
                        className="ml-2 hidden group-hover:inline text-red-400 text-xs">
                  xóa
                </button>
              )}
            </p>
          ),
        )}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={onSubmit} className="p-2 flex gap-2">
        <input className="flex-1 bg-gray-700 text-gray-100 rounded px-2 py-1 text-sm"
               value={draft} onChange={(e) => setDraft(e.target.value)}
               placeholder="Nhắn tin..." />
        <button className="bg-blue-600 text-white rounded px-3 text-sm" type="submit">
          Gửi
        </button>
      </form>
    </div>
  );
}
```

Cài thêm dependency: `cd frontend && npm install @stomp/stompjs`.

Nối `ChatPanel` + raise hand vào `RoomLayout.tsx` (sửa file Task 11 tạo):

```tsx
// thêm imports:
import { useRef } from 'react';
import { ChatPanel } from './ChatPanel';

// trong component, thêm:
  const raiseHandRef = useRef<(() => void) | null>(null);

// thay <aside> chỉ có ParticipantList bằng aside chia đôi:
        <aside className="w-72 bg-gray-850 border-l border-gray-700 flex flex-col">
          <div className="flex-1 min-h-0 border-b border-gray-700">
            <ParticipantList meetingId={meetingId} role={role} />
          </div>
          <div className="flex-1 min-h-0">
            <ChatPanel meetingId={meetingId} role={role}
                       registerRaiseHand={(fn) => (raiseHandRef.current = fn)} />
          </div>
        </aside>

// thay onRaiseHand no-op của ControlBar bằng:
      <ControlBar role={role} onRaiseHand={() => raiseHandRef.current?.()}
                  onEnd={role === 'HOST' ? () => end.mutate() : undefined} />
```

Thêm proxy WS vào `frontend/vite.config.ts` (trong `server.proxy`):

```ts
      '/ws': { target: 'ws://localhost:8080', ws: true },
```

- [ ] **Step 4: Chạy test + verify 2 cửa sổ chat, commit**

Run: `cd frontend && npm test` → PASS.
Manual: 2 cửa sổ cùng phòng → chat qua lại realtime; host xóa tin → biến mất cả 2 bên; bấm giơ tay → hiện "✋ ... giơ tay".

```bash
git add frontend/
git commit -m "feat(fe): chat panel over stomp with raise hand + moderation"
```

---

### Task 13: FE — form roomType + members UI + e2e webinar

**Files:**
- Modify: `frontend/src/features/meetings/DashboardPage.tsx` (select roomType), `frontend/src/features/meetings/meetingApi.ts` (input thêm roomType; thêm hooks members)
- Create: `frontend/src/features/meetings/MembersDialog.tsx`
- Create: `frontend/e2e/webinar-roles.spec.ts`

**Interfaces:**
- Consumes: Members API Task 3.
- Produces: form đặt lịch có select `Loại phòng: Họp kín / Webinar`; nút "Thành viên" mỗi meeting trong list mở `MembersDialog` (thêm email + role, xóa); e2e webinar flow đầy đủ.

- [ ] **Step 1: Sửa `meetingApi.ts`** — `CreateMeetingInput` thêm `roomType?: 'MEETING' | 'WEBINAR'`; thêm:

```ts
export type MemberDto = { id: string; email: string; role: 'SPEAKER' | 'ATTENDEE' };

export function useMembers(meetingId: string | null) {
  return useQuery({
    queryKey: ['members', meetingId],
    enabled: !!meetingId,
    queryFn: async () => (await api.get<MemberDto[]>(`/meetings/${meetingId}/members`)).data,
  });
}

export function useAddMember(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { email: string; role: 'SPEAKER' | 'ATTENDEE' }) =>
      (await api.post(`/meetings/${meetingId}/members`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['members', meetingId] }),
  });
}

export function useRemoveMember(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (memberId: string) =>
      api.delete(`/meetings/${meetingId}/members/${memberId}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['members', meetingId] }),
  });
}
```

- [ ] **Step 2: `MembersDialog.tsx`**

```tsx
import { useState, type FormEvent } from 'react';
import { useAddMember, useMembers, useRemoveMember } from './meetingApi';

type Props = { meetingId: string; onClose: () => void };

export function MembersDialog({ meetingId, onClose }: Props) {
  const { data: members } = useMembers(meetingId);
  const addMember = useAddMember(meetingId);
  const removeMember = useRemoveMember(meetingId);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<'SPEAKER' | 'ATTENDEE'>('ATTENDEE');

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    await addMember.mutateAsync({ email, role });
    setEmail('');
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center"
         onClick={onClose}>
      <div className="bg-white rounded-xl p-5 w-[28rem]" onClick={(e) => e.stopPropagation()}>
        <h3 className="font-semibold mb-3">Thành viên được mời</h3>
        <form onSubmit={onAdd} className="flex gap-2 mb-3">
          <input className="flex-1 border rounded px-2 py-1" type="email" required
                 placeholder="email@congty.vn" value={email}
                 onChange={(e) => setEmail(e.target.value)} />
          <select className="border rounded px-2 py-1" value={role}
                  onChange={(e) => setRole(e.target.value as 'SPEAKER' | 'ATTENDEE')}>
            <option value="ATTENDEE">Khán giả</option>
            <option value="SPEAKER">Diễn giả</option>
          </select>
          <button className="bg-blue-600 text-white rounded px-3" type="submit">Thêm</button>
        </form>
        <ul className="divide-y max-h-64 overflow-y-auto">
          {members?.map((m) => (
            <li key={m.id} className="py-2 flex justify-between text-sm">
              <span>{m.email} — {m.role === 'SPEAKER' ? 'Diễn giả' : 'Khán giả'}</span>
              <button onClick={() => removeMember.mutate(m.id)}
                      className="text-red-600">Xóa</button>
            </li>
          ))}
          {members?.length === 0 && <li className="py-2 text-sm text-gray-500">Chưa mời ai</li>}
        </ul>
        <button onClick={onClose} className="mt-3 w-full border rounded py-1.5">Đóng</button>
      </div>
    </div>
  );
}
```

Trong `DashboardPage`: thêm state `membersFor: string | null`; select roomType trong form đặt lịch:

```tsx
          <label className="text-sm">
            Loại phòng
            <select className="mt-1 border rounded-lg px-3 py-2" value={roomType}
                    onChange={(e) => setRoomType(e.target.value as 'MEETING' | 'WEBINAR')}>
              <option value="MEETING">Họp kín</option>
              <option value="WEBINAR">Webinar</option>
            </select>
          </label>
```

(khai báo `const [roomType, setRoomType] = useState<'MEETING' | 'WEBINAR'>('MEETING');`, truyền vào `createMeeting.mutateAsync({..., roomType})`); mỗi meeting row thêm nút `Thành viên` set `membersFor(m.id)`; render `{membersFor && <MembersDialog meetingId={membersFor} onClose={() => setMembersFor(null)} />}`.

- [ ] **Step 3: e2e webinar**

`frontend/e2e/webinar-roles.spec.ts`:

```ts
import { expect, test, type Page } from '@playwright/test';

async function registerAndLogin(page: Page, name: string) {
  const email = `${name}-${Date.now()}@e2e.meetly.dev`;
  await page.goto('/register');
  await page.getByPlaceholder('Họ tên').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Mật khẩu (≥ 8 ký tự)').fill('secret123');
  await page.getByRole('button', { name: 'Đăng ký' }).click();
  await expect(page.getByRole('button', { name: 'Họp ngay' })).toBeVisible();
}

test('webinar: guest là khán giả, host promote, chat hoạt động', async ({ browser }) => {
  const host = await (await browser.newContext()).newPage();
  const guest = await (await browser.newContext()).newPage();

  // Host tạo webinar qua form đặt lịch
  await registerAndLogin(host, 'Host');
  await host.getByPlaceholder('Họp team tuần').fill('Webinar e2e');
  await host.locator('select').first().selectOption('WEBINAR');
  await host.getByRole('button', { name: 'Đặt lịch' }).click();
  await host.getByRole('button', { name: 'Vào phòng' }).first().click();
  await host.waitForURL(/\/m\//);
  const code = host.url().split('/m/')[1];
  await host.getByRole('button', { name: 'Vào phòng' }).click();
  await expect(host.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // Guest (không đăng nhập) vào bằng link, nhập tên
  await guest.goto(`/m/${code}`);
  await guest.locator('input#username, input[name="username"]').fill('Khách Duy');
  await guest.getByRole('button', { name: 'Vào phòng' }).click();

  // Guest là ATTENDEE: không thấy toggle camera
  await expect(guest.getByText('Giơ tay')).toBeVisible({ timeout: 20_000 });
  await expect(guest.locator('button.lk-button', { hasText: /camera/i })).toHaveCount(0);

  // Chat 2 chiều
  await guest.getByPlaceholder('Nhắn tin...').fill('Xin chào từ khách');
  await guest.getByPlaceholder('Nhắn tin...').press('Enter');
  await expect(host.getByText('Xin chào từ khách')).toBeVisible({ timeout: 10_000 });

  // Host promote guest → guest thấy toast quyền phát biểu
  await host.getByTitle('Cho phát biểu').first().click();
  await expect(guest.getByText('Bạn đã được cấp quyền phát biểu 🎤'))
      .toBeVisible({ timeout: 10_000 });

  await host.context().close();
  await guest.context().close();
});
```

- [ ] **Step 4: Chạy toàn bộ, commit**

Run: `cd frontend && npm test && npm run e2e` (stack đang chạy đủ)
Expected: PASS (2 e2e specs).

```bash
git add frontend/
git commit -m "feat(fe): roomType select, members dialog, webinar e2e"
```

---

## Definition of Done — Phase 2 (khớp spec mục 8)

- [ ] Webinar demo: guest/attendee **không publish được** (token không có canPublish), host **promote** → phát biểu được ngay không rejoin.
- [ ] Chat đồng bộ khi API chạy **2 pod**: verify thủ công `SERVER_PORT=8081 ./mvnw spring-boot:run` chạy instance 2, cửa sổ A nối pod 1, cửa sổ B nối pod 2 (sửa proxy tạm), chat vẫn thấy nhau — hoặc tin tưởng ChatStompIT + kiến trúc relay Redis.
- [ ] Toàn bộ test BE/FE + 2 e2e specs xanh.
