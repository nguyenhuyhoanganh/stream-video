# Meetly Phase 3 (Recording) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Host bấm Record trong phòng → LiveKit Egress ghi RoomComposite ra MP4 lên S3/MinIO → webhook cập nhật trạng thái → trang "Bản ghi" xem lại qua presigned URL.

**Architecture:** Thêm 2 container dev (MinIO, LiveKit Egress — nối LiveKit qua Redis). BE thêm module `recording` (gọi Egress API, quản lý state machine `STARTING→ACTIVE→COMPLETED/FAILED`, presigned URL qua AWS SDK v2). FE thêm nút Record (host) + trang Recordings. (Spec mục 4.6, D-bảng phase 3.)

**Tech Stack:** Như Phase 2 + `software.amazon.awssdk:s3` 2.x, MinIO (dev), `livekit/egress` container.

**Prerequisite:** Phase 2 hoàn thành, test xanh. Nếu interface thực tế lệch plan, cập nhật plan trước khi chạy.

## Global Constraints

- Kế thừa Global Constraints Phase 1–2.
- Chỉ `HOST` được start/stop recording; meeting phải `allow_recording=true` (409 `RECORDING_NOT_ALLOWED` nếu false); 1 recording active/phòng (409 `RECORDING_ALREADY_ACTIVE`).
- File path S3: `recordings/{meetingCode}/{yyyyMMdd-HHmmss}.mp4`; bucket dev `meetly-recordings`.
- Playback qua **presigned GET URL, TTL 1 giờ**, chỉ cấp cho host/member của meeting (spec 4.6).
- Dev S3 = MinIO `localhost:9000`, console 9001, credentials `minio / minio12345`, path-style access.
- `ErrorCode` mới: `RECORDING_NOT_ALLOWED`, `RECORDING_ALREADY_ACTIVE`, `RECORDING_NOT_FOUND`, `RECORDING_NOT_READY`.

---

### Task 1: Compose — MinIO + Egress

**Files:**
- Modify: `ops/compose/docker-compose.dev.yml`, `ops/compose/livekit.yaml`
- Create: `ops/compose/egress.yaml`

**Interfaces:**
- Produces: MinIO tại `http://localhost:9000` (bucket `meetly-recordings` tự tạo), Egress container nối LiveKit qua Redis. LiveKit server và Egress **bắt buộc chung Redis** để giao tiếp.

- [ ] **Step 1: Thêm redis vào `ops/compose/livekit.yaml`** (cuối file)

```yaml
redis:
  address: redis:6379
```

và sửa service `livekit` trong compose: thêm `depends_on: [redis]`.

- [ ] **Step 2: Viết `ops/compose/egress.yaml`**

```yaml
api_key: devkey
api_secret: meetly_dev_secret_0123456789abcdef
ws_url: ws://livekit:7880
redis:
  address: redis:6379
insecure: true
logging:
  level: info
```

- [ ] **Step 3: Thêm services vào `docker-compose.dev.yml`**

```yaml
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: minio12345
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 3s
      retries: 10

  createbucket:
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 minio minio12345 &&
      mc mb -p local/meetly-recordings || true
      "

  egress:
    image: livekit/egress:v1.9
    depends_on: [redis, livekit]
    environment:
      EGRESS_CONFIG_FILE: /etc/egress.yaml
    volumes:
      - ./egress.yaml:/etc/egress.yaml:ro
    # egress cần shm lớn để chạy chromium composite
    shm_size: 2gb
```

và thêm `miniodata:` vào khối `volumes:` cuối file.

- [ ] **Step 4: Verify + commit**

Run: `docker compose -f ops/compose/docker-compose.dev.yml up -d && sleep 10 && docker compose -f ops/compose/docker-compose.dev.yml ps`
Expected: minio healthy, egress running, createbucket exited (0).

Run: `docker compose -f ops/compose/docker-compose.dev.yml logs egress | head -20`
Expected: log "connected" / không có error redis.

```bash
git add ops/
git commit -m "chore(ops): add minio + livekit egress to dev compose"
```

---

### Task 2: Migration V3 + Recording entity

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__recordings.sql`
- Create: `backend/src/main/java/com/meetly/recording/Recording.java`, `RecordingStatus.java`, `RecordingRepository.java`
- Test: gộp vào IT của Task 4 (entity đơn giản, không cần IT riêng)

**Interfaces:**
- Produces: `RecordingStatus { STARTING, ACTIVE, COMPLETED, FAILED }`; `RecordingRepository.findByMeetingIdOrderByStartedAtDesc(UUID): List<Recording>`, `findByEgressId(String): Optional<Recording>`, `existsByMeetingIdAndStatusIn(UUID, Collection<RecordingStatus>): boolean`.

- [ ] **Step 1: Viết `V3__recordings.sql`**

```sql
CREATE TABLE recordings (
    id               uuid PRIMARY KEY,
    meeting_id       uuid NOT NULL REFERENCES meetings (id),
    egress_id        varchar(100) NOT NULL UNIQUE,
    status           varchar(20) NOT NULL DEFAULT 'STARTING',
    s3_key           varchar(500),
    duration_seconds bigint,
    size_bytes       bigint,
    started_by       uuid REFERENCES users (id),
    started_at       timestamptz NOT NULL DEFAULT now(),
    ended_at         timestamptz
);
CREATE INDEX idx_recordings_meeting ON recordings (meeting_id, started_at DESC);
```

- [ ] **Step 2: Entity + enum + repo**

`com/meetly/recording/RecordingStatus.java`:

```java
package com.meetly.recording;

public enum RecordingStatus { STARTING, ACTIVE, COMPLETED, FAILED }
```

`com/meetly/recording/Recording.java`:

```java
package com.meetly.recording;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recordings")
@Getter @Setter @NoArgsConstructor
public class Recording {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "egress_id", nullable = false, unique = true, length = 100)
    private String egressId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingStatus status = RecordingStatus.STARTING;

    @Column(name = "s3_key", length = 500)
    private String s3Key;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;
}
```

`com/meetly/recording/RecordingRepository.java`:

```java
package com.meetly.recording;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingRepository extends JpaRepository<Recording, UUID> {
    List<Recording> findByMeetingIdOrderByStartedAtDesc(UUID meetingId);
    Optional<Recording> findByEgressId(String egressId);
    boolean existsByMeetingIdAndStatusIn(UUID meetingId, Collection<RecordingStatus> statuses);
}
```

- [ ] **Step 3: Chạy test toàn bộ (Flyway V3 chạy sạch), commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): V3 recordings table + entity"
```

---

### Task 3: Storage service (S3/MinIO presign)

**Files:**
- Modify: `backend/pom.xml` (AWS SDK), `backend/src/main/resources/application.yml`, `application-test.yml`, `MeetlyApplication.java` (enable props)
- Create: `backend/src/main/java/com/meetly/recording/StorageProperties.java`, `StorageService.java`
- Test: `backend/src/test/java/com/meetly/recording/StorageServiceTest.java`

**Interfaces:**
- Produces: `StorageService.presignGetUrl(String key, Duration ttl): String`; `StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey)` prefix `meetly.storage`. Task 4 dùng `props` cho S3Upload của Egress; Task 5–6 dùng presign.

- [ ] **Step 1: Thêm dependency `pom.xml`**

```xml
    <dependency><groupId>software.amazon.awssdk</groupId><artifactId>s3</artifactId><version>2.31.11</version></dependency>
```

- [ ] **Step 2: Config** — thêm vào `meetly:` trong cả `application.yml` và `application-test.yml`:

```yaml
  storage:
    endpoint: http://localhost:9000
    region: us-east-1
    bucket: meetly-recordings
    access-key: minio
    secret-key: minio12345
```

- [ ] **Step 3: Test fail**

`backend/src/test/java/com/meetly/recording/StorageServiceTest.java`:

```java
package com.meetly.recording;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StorageServiceTest {
    private final StorageService service = new StorageService(new StorageProperties(
            "http://localhost:9000", "us-east-1", "meetly-recordings", "minio", "minio12345"));

    @Test
    void presignedUrlContainsBucketKeyAndSignature() {
        String url = service.presignGetUrl("recordings/abc/1.mp4", Duration.ofHours(1));
        assertThat(url)
                .contains("meetly-recordings")
                .contains("recordings/abc/1.mp4")
                .contains("X-Amz-Signature=");
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=StorageServiceTest` → FAIL.

- [ ] **Step 4: Implement**

`com/meetly/recording/StorageProperties.java`:

```java
package com.meetly.recording;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meetly.storage")
public record StorageProperties(String endpoint, String region, String bucket,
                                String accessKey, String secretKey) {}
```

`com/meetly/recording/StorageService.java`:

```java
package com.meetly.recording;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

@Service
public class StorageService {
    private final S3Presigner presigner;
    private final StorageProperties props;

    public StorageService(StorageProperties props) {
        this.props = props;
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // bắt buộc cho MinIO
                        .build())
                .build();
    }

    public String presignGetUrl(String key, Duration ttl) {
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(props.bucket()).key(key).build())
                .build();
        return presigner.presignGetObject(req).url().toString();
    }
}
```

Thêm `StorageProperties.class` vào `@EnableConfigurationProperties` trong `MeetlyApplication`.

- [ ] **Step 5: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test -Dtest=StorageServiceTest` → PASS.

```bash
git add backend/
git commit -m "feat(be): s3 presign storage service (minio-compatible)"
```

---

### Task 4: Recording service + REST start/stop/list/playback

**Files:**
- Create: `backend/src/main/java/com/meetly/recording/EgressClient.java`, `RecordingService.java`, `RecordingController.java`, `RecordingDtos.java`
- Modify: `backend/src/main/java/com/meetly/common/ErrorCode.java`
- Test: `backend/src/test/java/com/meetly/recording/RecordingApiIT.java`

**Interfaces:**
- Produces: `EgressClient` (bọc SDK để mock được): `startRoomComposite(String roomCode, String s3Key): String /*egressId*/`, `stop(String egressId)`. REST: `POST /api/v1/meetings/{id}/recordings/start` → 201 `RecordingDto`; `POST .../recordings/stop` → 204; `GET /api/v1/meetings/{id}/recordings` → list (host/member); `GET /api/v1/recordings/{id}/playback-url` → `{url}` (401→login; 403 nếu không thuộc meeting; 409 `RECORDING_NOT_READY` nếu chưa COMPLETED). `RecordingDto(UUID id, String status, Instant startedAt, Instant endedAt, Long durationSeconds)`.

- [ ] **Step 1: Viết test fail** (mock `EgressClient`)

`backend/src/test/java/com/meetly/recording/RecordingApiIT.java`:

```java
package com.meetly.recording;

import com.meetly.TestcontainersConfig;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RecordingApiIT {
    @Autowired MockMvc mvc;
    @MockitoBean EgressClient egressClient;
    @Autowired RecordingRepository recordings;

    private String hostToken;
    private String otherToken;
    private String meetingId;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("rh+" + System.nanoTime() + "@meetly.dev");
        otherToken = register("ro+" + System.nanoTime() + "@meetly.dev");
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Rec","roomType":"WEBINAR"}"""))
                .andReturn().getResponse().getContentAsString();
        meetingId = read(created, "$.id");
        code = read(created, "$.code");
        when(egressClient.startRoomComposite(anyString(), startsWith("recordings/")))
                .thenReturn("EG_" + System.nanoTime());
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void startStopFlow() throws Exception {
        // start
        String started = mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STARTING"))
                .andReturn().getResponse().getContentAsString();

        // start lần 2 khi đang active → 409
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ALREADY_ACTIVE"));

        // non-host → 403
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/start")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        // stop → 204
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/stop")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        // list
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // playback khi chưa COMPLETED → 409
        String recId = read(started, "$.id");
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));

        // giả lập webhook đã hoàn tất → playback OK
        Recording rec = recordings.findById(java.util.UUID.fromString(recId)).orElseThrow();
        rec.setStatus(RecordingStatus.COMPLETED);
        rec.setS3Key("recordings/" + code + "/x.mp4");
        recordings.save(rec);
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());

        // người ngoài meeting xem playback → 403 (spec 4.6)
        mvc.perform(get("/api/v1/recordings/" + recId + "/playback-url")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=RecordingApiIT` → FAIL.

Thêm vào `ErrorCode` (trước `INTERNAL_ERROR`):

```java
    RECORDING_NOT_ALLOWED,
    RECORDING_ALREADY_ACTIVE,
    RECORDING_NOT_FOUND,
    RECORDING_NOT_READY,
```

`com/meetly/recording/EgressClient.java`:

```java
package com.meetly.recording;

import com.meetly.livekit.LiveKitProperties;
import io.livekit.server.EgressServiceClient;
import livekit.LivekitEgress;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class EgressClient {
    private final EgressServiceClient client;
    private final StorageProperties storage;

    public EgressClient(LiveKitProperties livekit, StorageProperties storage) {
        this.client = EgressServiceClient.createClient(
                livekit.httpUrl(), livekit.apiKey(), livekit.apiSecret());
        this.storage = storage;
    }

    /** Bắt đầu ghi RoomComposite → MP4 → S3. Trả về egressId. */
    public String startRoomComposite(String roomCode, String s3Key) {
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.MP4)
                .setFilepath(s3Key)
                .setS3(LivekitEgress.S3Upload.newBuilder()
                        .setEndpoint(storage.endpoint())
                        .setAccessKey(storage.accessKey())
                        .setSecret(storage.secretKey())
                        .setRegion(storage.region())
                        .setBucket(storage.bucket())
                        .setForcePathStyle(true)
                        .build())
                .build();
        try {
            LivekitEgress.EgressInfo info = client
                    .startRoomCompositeEgress(roomCode, output, "grid")
                    .execute().body();
            if (info == null) throw new IllegalStateException("Egress trả về rỗng");
            return info.getEgressId();
        } catch (IOException e) {
            throw new IllegalStateException("Không start được egress", e);
        }
    }

    public void stop(String egressId) {
        try {
            client.stopEgress(egressId).execute();
        } catch (IOException e) {
            throw new IllegalStateException("Không stop được egress", e);
        }
    }
}
```

*Ghi chú: nếu SDK khác chữ ký (`startRoomCompositeEgress` overloads/tham số layout), chỉnh theo SDK — hợp đồng giữ nguyên: ghi cả phòng layout grid, output MP4 lên S3 config như trên.*

`com/meetly/recording/RecordingDtos.java`:

```java
package com.meetly.recording;

import java.time.Instant;
import java.util.UUID;

public class RecordingDtos {
    public record RecordingDto(UUID id, String status, Instant startedAt, Instant endedAt,
                               Long durationSeconds) {
        static RecordingDto from(Recording r) {
            return new RecordingDto(r.getId(), r.getStatus().name(), r.getStartedAt(),
                    r.getEndedAt(), r.getDurationSeconds());
        }
    }

    public record PlaybackUrlDto(String url) {}
}
```

`com/meetly/recording/RecordingService.java`:

```java
package com.meetly.recording;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.Meeting;
import com.meetly.meeting.MeetingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordingService {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final RecordingRepository recordings;
    private final MeetingRepository meetings;
    private final com.meetly.meeting.MeetingMemberRepository members;
    private final EgressClient egressClient;
    private final StorageService storageService;

    @Transactional
    public Recording start(UUID meetingId, UUID actorId) {
        Meeting m = requireHost(meetingId, actorId);
        if (!m.isAllowRecording()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_NOT_ALLOWED,
                    "Phòng họp này không cho phép ghi hình");
        }
        if (recordings.existsByMeetingIdAndStatusIn(meetingId,
                List.of(RecordingStatus.STARTING, RecordingStatus.ACTIVE))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_ALREADY_ACTIVE,
                    "Đang có bản ghi chạy");
        }
        String s3Key = "recordings/%s/%s.mp4".formatted(m.getCode(), TS.format(Instant.now()));
        String egressId = egressClient.startRoomComposite(m.getCode(), s3Key);

        Recording rec = new Recording();
        rec.setMeetingId(meetingId);
        rec.setEgressId(egressId);
        rec.setS3Key(s3Key);
        rec.setStartedBy(actorId);
        return recordings.save(rec);
    }

    @Transactional
    public void stop(UUID meetingId, UUID actorId) {
        requireHost(meetingId, actorId);
        recordings.findByMeetingIdOrderByStartedAtDesc(meetingId).stream()
                .filter(r -> r.getStatus() == RecordingStatus.STARTING
                        || r.getStatus() == RecordingStatus.ACTIVE)
                .findFirst()
                .ifPresent(r -> egressClient.stop(r.getEgressId()));
    }

    @Transactional(readOnly = true)
    public List<Recording> list(UUID meetingId, UUID actorId) {
        Meeting m = meetings.findById(meetingId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
        requireHostOrMember(m, actorId);   // spec 4.6: chỉ host/member của meeting
        return recordings.findByMeetingIdOrderByStartedAtDesc(meetingId);
    }

    @Transactional(readOnly = true)
    public String playbackUrl(UUID recordingId, UUID actorId) {
        Recording rec = recordings.findById(recordingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.RECORDING_NOT_FOUND, "Không tìm thấy bản ghi"));
        Meeting m = meetings.findById(rec.getMeetingId()).orElseThrow();
        requireHostOrMember(m, actorId);   // spec 4.6: chỉ host/member của meeting
        if (rec.getStatus() != RecordingStatus.COMPLETED || rec.getS3Key() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORDING_NOT_READY,
                    "Bản ghi chưa sẵn sàng");
        }
        return storageService.presignGetUrl(rec.getS3Key(), Duration.ofHours(1));
    }

    private void requireHostOrMember(Meeting m, UUID actorId) {
        boolean allowed = m.getHostId().equals(actorId)
                || members.findByMeetingIdAndUserId(m.getId(), actorId).isPresent();
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                    "Bạn không thuộc phòng họp này");
        }
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

`com/meetly/recording/RecordingController.java`:

```java
package com.meetly.recording;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.recording.RecordingDtos.PlaybackUrlDto;
import com.meetly.recording.RecordingDtos.RecordingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RecordingController {
    private final RecordingService recordingService;

    @PostMapping("/meetings/{meetingId}/recordings/start")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordingDto start(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable UUID meetingId) {
        return RecordingDto.from(recordingService.start(meetingId, user.id()));
    }

    @PostMapping("/meetings/{meetingId}/recordings/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal AuthenticatedUser user,
                     @PathVariable UUID meetingId) {
        recordingService.stop(meetingId, user.id());
    }

    @GetMapping("/meetings/{meetingId}/recordings")
    public List<RecordingDto> list(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID meetingId) {
        return recordingService.list(meetingId, user.id()).stream()
                .map(RecordingDto::from).toList();
    }

    @GetMapping("/recordings/{recordingId}/playback-url")
    public PlaybackUrlDto playbackUrl(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID recordingId) {
        return new PlaybackUrlDto(recordingService.playbackUrl(recordingId, user.id()));
    }
}
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): recording start/stop/list/playback via egress"
```

---

### Task 5: Webhook egress_ended

**Files:**
- Modify: `backend/src/main/java/com/meetly/livekit/WebhookHandler.java`
- Test: thêm case vào `backend/src/test/java/com/meetly/livekit/WebhookHandlerIT.java`

**Interfaces:**
- Consumes: `RecordingRepository.findByEgressId` (Task 2).
- Produces: event `egress_ended` → recording `COMPLETED` (status EgressInfo = `EGRESS_COMPLETE`) hoặc `FAILED`; set `ended_at`, `duration_seconds` (từ file result nếu có), giữ `s3_key` đã đặt lúc start. Event `egress_started`/`egress_updated` active → status `ACTIVE`.

- [ ] **Step 1: Thêm test (fail)** — vào `WebhookHandlerIT`:

```java
    @Autowired com.meetly.recording.RecordingRepository recordings;

    @Test
    void egressEndedCompletesRecording() {
        com.meetly.recording.Recording rec = new com.meetly.recording.Recording();
        rec.setMeetingId(meeting.getId());
        rec.setEgressId("EG_TEST_1");
        rec.setS3Key("recordings/x/y.mp4");
        recordings.save(rec);

        livekit.LivekitEgress.EgressInfo info = livekit.LivekitEgress.EgressInfo.newBuilder()
                .setEgressId("EG_TEST_1")
                .setStatus(livekit.LivekitEgress.EgressStatus.EGRESS_COMPLETE)
                .build();
        LivekitWebhook.WebhookEvent ev = LivekitWebhook.WebhookEvent.newBuilder()
                .setEvent("egress_ended").setId("eg-e1")
                .setEgressInfo(info)
                .setRoom(LivekitModels.Room.newBuilder().setName(meeting.getCode()))
                .build();
        handler.handle(ev);

        assertThat(recordings.findByEgressId("EG_TEST_1").orElseThrow().getStatus())
                .isEqualTo(com.meetly.recording.RecordingStatus.COMPLETED);
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=WebhookHandlerIT` → FAIL (case mới).

- [ ] **Step 2: Implement** — trong `WebhookHandler` inject thêm `RecordingRepository recordings` và thêm case vào switch (trước `default`):

```java
            case "egress_started", "egress_updated" -> recordings
                    .findByEgressId(event.getEgressInfo().getEgressId())
                    .ifPresent(r -> {
                        if (r.getStatus() == com.meetly.recording.RecordingStatus.STARTING) {
                            r.setStatus(com.meetly.recording.RecordingStatus.ACTIVE);
                        }
                    });
            case "egress_ended" -> recordings
                    .findByEgressId(event.getEgressInfo().getEgressId())
                    .ifPresent(r -> {
                        boolean ok = event.getEgressInfo().getStatus()
                                == livekit.LivekitEgress.EgressStatus.EGRESS_COMPLETE;
                        r.setStatus(ok ? com.meetly.recording.RecordingStatus.COMPLETED
                                : com.meetly.recording.RecordingStatus.FAILED);
                        r.setEndedAt(Instant.now());
                        if (event.getEgressInfo().getFileResultsCount() > 0) {
                            var file = event.getEgressInfo().getFileResults(0);
                            r.setDurationSeconds(file.getDuration() / 1_000_000_000L); // ns → s
                            r.setSizeBytes(file.getSize());
                        }
                    });
```

Lưu ý: `egress_*` event có thể đến khi room đã đóng — di chuyển đoạn lookup `Meeting` sao cho các case `egress_*` xử lý TRƯỚC và `return`, không phụ thuộc meeting tồn tại theo room name:

```java
        // egress events xử lý theo egressId, không cần meeting lookup
        if (event.getEvent().startsWith("egress_")) {
            handleEgress(event);   // tách 2 case trên vào method riêng
            return;
        }
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): egress webhook completes recordings"
```

---

### Task 6: FE — nút Record + trang Bản ghi

**Files:**
- Create: `frontend/src/features/recordings/recordingApi.ts`, `RecordingsPage.tsx`
- Modify: `frontend/src/features/room/ControlBar.tsx` (nút Record cho HOST), `RoomLayout.tsx` (truyền meetingId xuống ControlBar), `frontend/src/App.tsx` (route `/recordings/:meetingId`), `frontend/src/features/meetings/DashboardPage.tsx` (link "Bản ghi" mỗi meeting đã ENDED)

**Interfaces:**
- Produces: `useRecordings(meetingId)`, `useStartRecording(meetingId)`, `useStopRecording(meetingId)`, `usePlaybackUrl()`; ControlBar (HOST) nút "⏺ Ghi hình"/"⏹ Dừng ghi" với trạng thái đang ghi = list có recording STARTING/ACTIVE (refetch 10s); trang `/recordings/:meetingId` list bản ghi + bấm "Xem" → fetch playback-url → `<video controls src={url}>`.

- [ ] **Step 1: `recordingApi.ts`**

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';

export type RecordingDto = {
  id: string;
  status: 'STARTING' | 'ACTIVE' | 'COMPLETED' | 'FAILED';
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
};

export function useRecordings(meetingId: string, opts?: { poll?: boolean }) {
  return useQuery({
    queryKey: ['recordings', meetingId],
    queryFn: async () =>
      (await api.get<RecordingDto[]>(`/meetings/${meetingId}/recordings`)).data,
    refetchInterval: opts?.poll ? 10_000 : false,
  });
}

export function useStartRecording(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => api.post(`/meetings/${meetingId}/recordings/start`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['recordings', meetingId] }),
  });
}

export function useStopRecording(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => api.post(`/meetings/${meetingId}/recordings/stop`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['recordings', meetingId] }),
  });
}

export async function fetchPlaybackUrl(recordingId: string): Promise<string> {
  return (await api.get<{ url: string }>(`/recordings/${recordingId}/playback-url`)).data.url;
}
```

- [ ] **Step 2: ControlBar thêm Record (HOST)** — thêm props `meetingId: string`; thêm import `import { useRecordings, useStartRecording, useStopRecording } from '../recordings/recordingApi';`; trong component:

```tsx
  const { data: recs } = useRecordings(meetingId, { poll: role === 'HOST' });
  const startRec = useStartRecording(meetingId);
  const stopRec = useStopRecording(meetingId);
  const recActive = recs?.some((r) => r.status === 'STARTING' || r.status === 'ACTIVE');
```

và trong JSX (chỉ HOST):

```tsx
      {role === 'HOST' && (
        recActive ? (
          <button onClick={() => stopRec.mutate()}
                  className="bg-red-600 text-white rounded-lg px-3 py-2 text-sm animate-pulse">
            ⏹ Dừng ghi
          </button>
        ) : (
          <button onClick={() => startRec.mutate()}
                  className="bg-gray-700 text-white rounded-lg px-3 py-2 text-sm">
            ⏺ Ghi hình
          </button>
        )
      )}
```

(cập nhật `RoomLayout` truyền `meetingId` xuống `<ControlBar meetingId={meetingId} .../>`.)

- [ ] **Step 3: `RecordingsPage.tsx`**

```tsx
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchPlaybackUrl, useRecordings } from './recordingApi';

export function RecordingsPage() {
  const { meetingId } = useParams<{ meetingId: string }>();
  const { data: recordings, isLoading } = useRecordings(meetingId!);
  const [playing, setPlaying] = useState<string | null>(null);

  async function play(recordingId: string) {
    setPlaying(await fetchPlaybackUrl(recordingId));
  }

  return (
    <div className="min-h-screen bg-gray-50 p-6 max-w-3xl mx-auto space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Bản ghi cuộc họp</h1>
        <Link to="/" className="text-blue-600 text-sm">← Về trang chính</Link>
      </div>
      {playing && (
        <video controls autoPlay src={playing} className="w-full rounded-xl shadow bg-black" />
      )}
      <section className="bg-white rounded-xl shadow divide-y">
        {isLoading && <p className="px-4 py-3 text-gray-500">Đang tải...</p>}
        {recordings?.length === 0 && (
          <p className="px-4 py-3 text-gray-500">Chưa có bản ghi nào</p>
        )}
        {recordings?.map((r) => (
          <div key={r.id} className="px-4 py-3 flex items-center justify-between text-sm">
            <div>
              <p>{new Date(r.startedAt).toLocaleString('vi-VN')}</p>
              <p className="text-gray-500">
                {r.status}{r.durationSeconds ? ` · ${Math.round(r.durationSeconds / 60)} phút` : ''}
              </p>
            </div>
            {r.status === 'COMPLETED' && (
              <button onClick={() => void play(r.id)}
                      className="text-blue-600 font-medium hover:underline">
                ▶ Xem
              </button>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}
```

Route trong `App.tsx` (trong `ProtectedRoute`): `<Route path="/recordings/:meetingId" element={<RecordingsPage />} />`. Dashboard: meeting `ENDED` hiện link `Bản ghi` → `/recordings/${m.id}`.

- [ ] **Step 4: Verify end-to-end thủ công (DoD Phase 3)**

Stack đầy đủ (`compose up` gồm egress+minio, BE, FE):
1. Host vào phòng → "⏺ Ghi hình" → nút chuyển "⏹ Dừng ghi" (nháy đỏ).
2. Nói/bật cam ~30 giây → "⏹ Dừng ghi" → chờ ~10–20s egress upload.
3. Kiểm chứng file: mở MinIO console `http://localhost:9001` (minio/minio12345) → bucket `meetly-recordings` có file `.mp4`.
4. FE `/recordings/<meetingId>` → bản ghi COMPLETED → "▶ Xem" phát được video.

- [ ] **Step 5: Chạy test + commit**

Run: `cd frontend && npm test && cd ../backend && ./mvnw -q test`
Expected: PASS.

```bash
git add frontend/
git commit -m "feat(fe): record button + recordings playback page"
```

---

## Definition of Done — Phase 3 (khớp spec mục 8)

- [ ] Host record → dừng → file MP4 nằm trong MinIO → trang Bản ghi phát lại được qua presigned URL.
- [ ] Recording tự dừng khi phòng kết thúc (egress tự stop khi room đóng — verify: host End meeting khi đang ghi, bản ghi vẫn COMPLETED).
- [ ] Toàn bộ test BE/FE xanh.
