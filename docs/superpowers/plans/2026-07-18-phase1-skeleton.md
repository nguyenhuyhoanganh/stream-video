# Meetly Phase 1 (Skeleton) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Monorepo chạy được: đăng ký/đăng nhập, tạo meeting, 2 người join cùng phòng qua UI và thấy video của nhau, toàn bộ hạ tầng dev từ `docker compose up`.

**Architecture:** Spring Boot (control plane: auth JWT + refresh rotation, meeting CRUD, cấp LiveKit token) + React SPA (LiveKit components) + LiveKit dev mode trong Docker. Media đi thẳng Browser ↔ LiveKit; BE không chạm media. Spec: `docs/superpowers/specs/2026-07-18-meetly-video-conferencing-design.md` (Phase 1 — mục 8; roles ATTENDEE/guest/chat/recording là Phase 2–3, KHÔNG làm ở đây).

**Tech Stack:** Java 21, Spring Boot 3.5.x (spec yêu cầu ≥3.3), Maven, PostgreSQL 16, Flyway, jjwt 0.12.6, `io.livekit:livekit-server` 0.10.2, React 18 + Vite + TypeScript, TanStack Query v5, Zustand v5, `@livekit/components-react` v2, Tailwind CSS v4, Testcontainers, Vitest, Playwright.

## Global Constraints

- Java 21; Spring Boot ≥ 3.3 (dùng 3.5.x); React 18 + TypeScript strict.
- API prefix: `/api/v1`. Lỗi trả về RFC 7807 ProblemDetail + property `code` (enum `ErrorCode`).
- JWT access TTL **15 phút**; refresh **14 ngày**, rotation, lưu SHA-256 hash trong DB, cookie httpOnly tên `meetly_refresh`, path `/api/v1/auth`, SameSite=Lax.
- LiveKit token: `canPublishData=false` luôn (chat đi qua BE — spec D4). Phase 1: mọi người join đều `canPublish=true` (HOST/SPEAKER); ATTENDEE là Phase 2.
- Dev credentials (chỉ dev): LiveKit key `devkey` / secret `meetly_dev_secret_0123456789abcdef`; JWT secret `meetly_dev_jwt_secret_min_32_chars_0123`; Postgres `meetly/meetly/meetly`.
- Ports dev: BE 8080, FE 5173 (proxy `/api`→8080), LiveKit ws 7880, Postgres 5432, Redis 6379.
- Mã phòng dạng `abc-defg-hij` (chữ thường, 3-4-3, SecureRandom).
- Commit message: conventional commits (`feat:`, `test:`, `chore:`, `docs:`).

---

### Task 1: Monorepo scaffold + Docker Compose dev

**Files:**
- Create: `.gitignore`, `README.md`
- Create: `ops/compose/docker-compose.dev.yml`, `ops/compose/livekit.yaml`

**Interfaces:**
- Produces: hạ tầng dev tại `localhost:5432` (postgres), `localhost:6379` (redis), `ws://localhost:7880` (LiveKit, key `devkey` / secret `meetly_dev_secret_0123456789abcdef`).

- [ ] **Step 1: Viết `.gitignore`**

```gitignore
# Java / Maven
backend/target/
*.class
# Node
frontend/node_modules/
frontend/dist/
frontend/playwright-report/
frontend/test-results/
# IDE / OS
.idea/
*.iml
.vscode/
.DS_Store
# Env
.env
.env.local
```

- [ ] **Step 2: Viết `ops/compose/livekit.yaml`**

```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 50060
  use_external_ip: false
keys:
  devkey: meetly_dev_secret_0123456789abcdef
logging:
  level: info
```

- [ ] **Step 3: Viết `ops/compose/docker-compose.dev.yml`**

```yaml
name: meetly-dev
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: meetly
      POSTGRES_USER: meetly
      POSTGRES_PASSWORD: meetly
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U meetly"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  livekit:
    image: livekit/livekit-server:v1.9.1
    command: --config /etc/livekit.yaml --node-ip 127.0.0.1
    volumes:
      - ./livekit.yaml:/etc/livekit.yaml:ro
    ports:
      - "7880:7880"
      - "7881:7881"
      - "50000-50060:50000-50060/udp"

volumes:
  pgdata:
```

- [ ] **Step 4: Viết `README.md`**

```markdown
# Meetly

Hệ thống họp video (BE Spring Boot · FE React · LiveKit SFU).
Spec: `docs/superpowers/specs/2026-07-18-meetly-video-conferencing-design.md`

## Dev quickstart

```bash
# 1. Hạ tầng (Postgres, Redis, LiveKit)
docker compose -f ops/compose/docker-compose.dev.yml up -d

# 2. Backend (http://localhost:8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

Mở http://localhost:5173 bằng 2 cửa sổ trình duyệt (1 thường + 1 ẩn danh),
đăng ký 2 tài khoản, một bên bấm "Họp ngay" rồi gửi link `/m/<code>` cho bên kia.
```

- [ ] **Step 5: Chạy và verify hạ tầng**

Run: `docker compose -f ops/compose/docker-compose.dev.yml up -d && sleep 8 && docker compose -f ops/compose/docker-compose.dev.yml ps`
Expected: 3 services `running`, postgres và redis `healthy`.

Run: `curl -s http://localhost:7880`
Expected: `OK` (LiveKit trả OK ở root).

- [ ] **Step 6: Commit**

```bash
git add .gitignore README.md ops/
git commit -m "chore: monorepo scaffold + docker compose dev (postgres, redis, livekit)"
```

---

### Task 2: Backend bootstrap (Spring Boot + Testcontainers)

**Files:**
- Create: `backend/pom.xml`, `backend/.mvn/wrapper/*` (qua `mvn wrapper:wrapper`)
- Create: `backend/src/main/java/com/meetly/MeetlyApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/meetly/TestcontainersConfig.java`
- Test: `backend/src/test/java/com/meetly/MeetlyApplicationTests.java`

**Interfaces:**
- Produces: app Spring Boot chạy port 8080, profile `test` dùng Testcontainers Postgres qua `@ServiceConnection`; các task sau kế thừa `TestcontainersConfig`.

- [ ] **Step 1: Viết `backend/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.4</version>
    <relativePath/>
  </parent>
  <groupId>com.meetly</groupId>
  <artifactId>meetly-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>${jjwt.version}</version></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
    <dependency><groupId>io.livekit</groupId><artifactId>livekit-server</artifactId><version>0.10.2</version></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>2.8.5</version></dependency>
    <!-- test -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
        <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Tạo Maven wrapper + main class + config**

Run: `cd backend && mvn -q wrapper:wrapper -Dmaven=3.9.9`

`backend/src/main/java/com/meetly/MeetlyApplication.java`:

```java
package com.meetly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MeetlyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetlyApplication.class, args);
    }
}
```

`backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: meetly-api
  datasource:
    url: jdbc:postgresql://localhost:5432/meetly
    username: meetly
    password: meetly
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus

meetly:
  auth:
    jwt-secret: meetly_dev_jwt_secret_min_32_chars_0123
    access-ttl: 15m
    refresh-ttl: 14d
    cookie-secure: false
  livekit:
    api-key: devkey
    api-secret: meetly_dev_secret_0123456789abcdef
    ws-url: ws://localhost:7880
  cors:
    allowed-origins: http://localhost:5173
```

`backend/src/test/resources/application-test.yml`:

```yaml
meetly:
  auth:
    jwt-secret: meetly_dev_jwt_secret_min_32_chars_0123
    access-ttl: 15m
    refresh-ttl: 14d
    cookie-secure: false
  livekit:
    api-key: devkey
    api-secret: meetly_dev_secret_0123456789abcdef
    ws-url: ws://localhost:7880
  cors:
    allowed-origins: http://localhost:5173
```

`backend/src/test/java/com/meetly/TestcontainersConfig.java`:

```java
package com.meetly;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
```

- [ ] **Step 3: Viết test khởi động**

`backend/src/test/java/com/meetly/MeetlyApplicationTests.java`:

```java
package com.meetly;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MeetlyApplicationTests {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 4: Chạy test**

Run: `cd backend && ./mvnw -q test`
Expected: `BUILD SUCCESS` (cần Docker đang chạy cho Testcontainers).

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "feat(be): spring boot bootstrap + testcontainers"
```

---

### Task 3: Flyway V1 + entities + repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/com/meetly/user/User.java`, `backend/src/main/java/com/meetly/user/UserRepository.java`
- Create: `backend/src/main/java/com/meetly/auth/RefreshToken.java`, `backend/src/main/java/com/meetly/auth/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/meetly/meeting/Meeting.java`, `MeetingStatus.java`, `RoomType.java`, `MeetingRepository.java` (cùng package `com.meetly.meeting`)
- Test: `backend/src/test/java/com/meetly/meeting/MeetingRepositoryIT.java`

**Interfaces:**
- Produces: entities `User`, `Meeting`, `RefreshToken`; repos `UserRepository.findByEmail(String): Optional<User>`, `MeetingRepository.findByCode(String): Optional<Meeting>`, `findByHostIdOrderByScheduledStartAtDesc(UUID): List<Meeting>`, `RefreshTokenRepository.findByTokenHash(String): Optional<RefreshToken>`.

- [ ] **Step 1: Viết `V1__init.sql`**

```sql
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    full_name     varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'USER',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE meetings (
    id                 uuid PRIMARY KEY,
    code               varchar(20)  NOT NULL UNIQUE,
    title              varchar(255) NOT NULL,
    description        text,
    host_id            uuid NOT NULL REFERENCES users (id),
    scheduled_start_at timestamptz  NOT NULL,
    scheduled_end_at   timestamptz,
    status             varchar(20)  NOT NULL DEFAULT 'SCHEDULED',
    room_type          varchar(20)  NOT NULL DEFAULT 'MEETING',
    allow_recording    boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_meetings_host ON meetings (host_id, scheduled_start_at DESC);
```

- [ ] **Step 2: Viết entities + enums + repos**

`com/meetly/user/User.java`:

```java
package com.meetly.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String role = "USER";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
```

`com/meetly/user/UserRepository.java`:

```java
package com.meetly.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

`com/meetly/auth/RefreshToken.java`:

```java
package com.meetly.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class RefreshToken {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
```

`com/meetly/auth/RefreshTokenRepository.java`:

```java
package com.meetly.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
```

`com/meetly/meeting/MeetingStatus.java` và `RoomType.java`:

```java
package com.meetly.meeting;

public enum MeetingStatus { SCHEDULED, LIVE, ENDED, CANCELLED }
```

```java
package com.meetly.meeting;

public enum RoomType { MEETING, WEBINAR }
```

`com/meetly/meeting/Meeting.java`:

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
@Table(name = "meetings")
@Getter @Setter @NoArgsConstructor
public class Meeting {
    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "scheduled_start_at", nullable = false)
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at")
    private Instant scheduledEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingStatus status = MeetingStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private RoomType roomType = RoomType.MEETING;

    @Column(name = "allow_recording", nullable = false)
    private boolean allowRecording = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
```

`com/meetly/meeting/MeetingRepository.java`:

```java
package com.meetly.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
    Optional<Meeting> findByCode(String code);
    List<Meeting> findByHostIdOrderByScheduledStartAtDesc(UUID hostId);
    boolean existsByCode(String code);
}
```

- [ ] **Step 3: Viết test repository (fail trước khi entities đúng)**

`backend/src/test/java/com/meetly/meeting/MeetingRepositoryIT.java`:

```java
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
```

- [ ] **Step 4: Chạy test**

Run: `cd backend && ./mvnw -q test -Dtest=MeetingRepositoryIT`
Expected: PASS (Flyway chạy V1, entity map đúng schema).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(be): flyway V1 + entities user/meeting/refresh_token"
```

---

### Task 4: JwtService (TDD thuần unit)

**Files:**
- Create: `backend/src/main/java/com/meetly/common/AuthProperties.java`
- Create: `backend/src/main/java/com/meetly/auth/JwtService.java`
- Test: `backend/src/test/java/com/meetly/auth/JwtServiceTest.java`

**Interfaces:**
- Produces: `JwtService.generateAccessToken(UUID userId, String email): String`; `JwtService.parse(String token): AccessTokenClaims` (record `AccessTokenClaims(UUID userId, String email)`, ném `JwtException` khi sai/hết hạn); `AuthProperties` bind prefix `meetly.auth` (fields: `jwtSecret`, `accessTtl: Duration`, `refreshTtl: Duration`, `cookieSecure: boolean`).

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/auth/JwtServiceTest.java`:

```java
package com.meetly.auth;

import com.meetly.common.AuthProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties(
                "meetly_dev_jwt_secret_min_32_chars_0123",
                Duration.ofMinutes(15), Duration.ofDays(14), false);
        jwtService = new JwtService(props);
    }

    @Test
    void roundTrip() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "a@b.c");
        JwtService.AccessTokenClaims claims = jwtService.parse(token);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.email()).isEqualTo("a@b.c");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongSignature() {
        AuthProperties other = new AuthProperties(
                "another_secret_that_is_long_enough_9999", 
                Duration.ofMinutes(15), Duration.ofDays(14), false);
        String forged = new JwtService(other).generateAccessToken(UUID.randomUUID(), "a@b.c");
        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: Chạy test → fail compile**

Run: `cd backend && ./mvnw -q test -Dtest=JwtServiceTest`
Expected: FAIL — `JwtService`, `AuthProperties` chưa tồn tại.

- [ ] **Step 3: Implement**

`com/meetly/common/AuthProperties.java`:

```java
package com.meetly.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "meetly.auth")
public record AuthProperties(String jwtSecret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {}
```

`com/meetly/auth/JwtService.java`:

```java
package com.meetly.auth;

import com.meetly.common.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    public record AccessTokenClaims(UUID userId, String email) {}

    private final SecretKey key;
    private final AuthProperties props;

    public JwtService(AuthProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException nếu token sai chữ ký / hết hạn / rác */
    public AccessTokenClaims parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new AccessTokenClaims(UUID.fromString(c.getSubject()), c.get("email", String.class));
    }
}
```

Đăng ký properties — sửa `MeetlyApplication.java` thành:

```java
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
```

- [ ] **Step 4: Chạy test → pass**

Run: `cd backend && ./mvnw -q test -Dtest=JwtServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(be): jwt service with hs256 access tokens"
```

---

### Task 5: Error handling RFC 7807 + register/login

**Files:**
- Create: `backend/src/main/java/com/meetly/common/ErrorCode.java`, `ApiException.java`, `GlobalExceptionHandler.java` (package `com.meetly.common`)
- Create: `backend/src/main/java/com/meetly/auth/AuthDtos.java`, `AuthService.java`, `AuthController.java`
- Create: `backend/src/main/java/com/meetly/common/SecurityConfig.java` (bản tối thiểu, Task 6 mở rộng)
- Test: `backend/src/test/java/com/meetly/auth/AuthApiIT.java`

**Interfaces:**
- Consumes: `JwtService`, `UserRepository`, `RefreshTokenRepository`, `AuthProperties` (Task 3–4).
- Produces: `POST /api/v1/auth/register`, `POST /api/v1/auth/login` — body `{email, password, fullName?}` → 200 `{accessToken, user:{id,email,fullName}}` + Set-Cookie `meetly_refresh`; lỗi ProblemDetail có property `code`. `AuthService.issueTokens(User): TokenPair` (record `TokenPair(String accessToken, String rawRefreshToken)`); `AuthService.sha256(String): String` (hex). `ErrorCode` enum: `VALIDATION_FAILED, EMAIL_TAKEN, INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, MEETING_NOT_FOUND, MEETING_ENDED, MEETING_NOT_STARTED, NOT_MEETING_HOST, INTERNAL_ERROR`.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/auth/AuthApiIT.java`:

```java
package com.meetly.auth;

import com.meetly.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AuthApiIT {
    @Autowired MockMvc mvc;

    private static final String ANH = """
            {"email":"anh@meetly.dev","password":"secret123","fullName":"Anh"}""";

    @Test
    void registerLoginFlow() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON).content(ANH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("anh@meetly.dev"))
                .andExpect(cookie().httpOnly("meetly_refresh", true));

        // email trùng → 409 EMAIL_TAKEN
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON).content(ANH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        // login đúng
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"anh@meetly.dev","password":"secret123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // login sai pass → 401 INVALID_CREDENTIALS
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"anh@meetly.dev","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // body thiếu email → 400 VALIDATION_FAILED
        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"password":"secret123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

- [ ] **Step 2: Chạy test → fail**

Run: `cd backend && ./mvnw -q test -Dtest=AuthApiIT`
Expected: FAIL (chưa có endpoint).

- [ ] **Step 3: Implement error handling**

`com/meetly/common/ErrorCode.java`:

```java
package com.meetly.common;

public enum ErrorCode {
    VALIDATION_FAILED,
    EMAIL_TAKEN,
    INVALID_CREDENTIALS,
    INVALID_REFRESH_TOKEN,
    MEETING_NOT_FOUND,
    MEETING_ENDED,
    MEETING_NOT_STARTED,
    NOT_MEETING_HOST,
    INTERNAL_ERROR
}
```

`com/meetly/common/ApiException.java`:

```java
package com.meetly.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
```

`com/meetly/common/GlobalExceptionHandler.java`:

```java
package com.meetly.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode().name());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleOther(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        pd.setProperty("code", ErrorCode.INTERNAL_ERROR.name());
        return pd;
    }
}
```

- [ ] **Step 4: Implement auth**

`com/meetly/auth/AuthDtos.java`:

```java
package com.meetly.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class AuthDtos {
    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank String fullName) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record UserDto(UUID id, String email, String fullName) {}

    public record AuthResponse(String accessToken, UserDto user) {}
}
```

`com/meetly/auth/AuthService.java`:

```java
package com.meetly.auth;

import com.meetly.common.ApiException;
import com.meetly.common.AuthProperties;
import com.meetly.common.ErrorCode;
import com.meetly.user.User;
import com.meetly.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {
    public record TokenPair(String accessToken, String rawRefreshToken) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public User register(String email, String password, String fullName) {
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_TAKEN, "Email đã được đăng ký");
        }
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setFullName(fullName);
        return users.save(u);
    }

    @Transactional(readOnly = true)
    public User authenticate(String email, String password) {
        return users.findByEmail(email)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_CREDENTIALS, "Email hoặc mật khẩu không đúng"));
    }

    @Transactional
    public TokenPair issueTokens(User user) {
        byte[] buf = new byte[48];
        random.nextBytes(buf);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256(raw));
        rt.setExpiresAt(Instant.now().plus(props.refreshTtl()));
        refreshTokens.save(rt);

        return new TokenPair(jwtService.generateAccessToken(user.getId(), user.getEmail()), raw);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`com/meetly/auth/AuthController.java`:

```java
package com.meetly.auth;

import com.meetly.auth.AuthDtos.*;
import com.meetly.common.AuthProperties;
import com.meetly.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    static final String REFRESH_COOKIE = "meetly_refresh";

    private final AuthService authService;
    private final AuthProperties props;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        User user = authService.register(req.email(), req.password(), req.fullName());
        return respondWithTokens(user);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        User user = authService.authenticate(req.email(), req.password());
        return respondWithTokens(user);
    }

    ResponseEntity<AuthResponse> respondWithTokens(User user) {
        AuthService.TokenPair pair = authService.issueTokens(user);
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, pair.rawRefreshToken())
                .httpOnly(true)
                .secure(props.cookieSecure())
                .path("/api/v1/auth")
                .maxAge(props.refreshTtl())
                .sameSite("Lax")
                .build();
        AuthResponse body = new AuthResponse(pair.accessToken(),
                new UserDto(user.getId(), user.getEmail(), user.getFullName()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }
}
```

`com/meetly/common/SecurityConfig.java` (tối thiểu — Task 6 thêm JWT filter):

```java
package com.meetly.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/health",
                        "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
```

- [ ] **Step 5: Chạy test → pass**

Run: `cd backend && ./mvnw -q test -Dtest=AuthApiIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(be): register/login + rfc7807 error handling"
```

---

### Task 6: JWT filter + refresh rotation + logout + /users/me

**Files:**
- Modify: `backend/src/main/java/com/meetly/common/SecurityConfig.java`
- Create: `backend/src/main/java/com/meetly/auth/JwtAuthFilter.java`, `backend/src/main/java/com/meetly/auth/AuthenticatedUser.java`
- Modify: `backend/src/main/java/com/meetly/auth/AuthService.java` (thêm `rotate`, `revoke`), `AuthController.java` (thêm `/refresh`, `/logout`)
- Create: `backend/src/main/java/com/meetly/user/UserController.java`
- Test: `backend/src/test/java/com/meetly/auth/RefreshFlowIT.java`

**Interfaces:**
- Produces: `POST /api/v1/auth/refresh` (đọc cookie, rotate → 200 AuthResponse + cookie mới; token đã revoke/expired → 401 `INVALID_REFRESH_TOKEN`); `POST /api/v1/auth/logout` (revoke + xóa cookie → 204); `GET /api/v1/users/me` → `UserDto`. Record `AuthenticatedUser(UUID id, String email)` là principal — controller các task sau lấy qua `@AuthenticationPrincipal AuthenticatedUser user`.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/auth/RefreshFlowIT.java`:

```java
package com.meetly.auth;

import com.meetly.TestcontainersConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.jayway.jsonpath.JsonPath.read;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RefreshFlowIT {
    @Autowired MockMvc mvc;

    @Test
    void refreshRotationAndMe() throws Exception {
        MvcResult reg = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"rot@meetly.dev","password":"secret123","fullName":"Rot"}"""))
                .andExpect(status().isOk()).andReturn();
        Cookie refresh1 = reg.getResponse().getCookie("meetly_refresh");
        String access = read(reg.getResponse().getContentAsString(), "$.accessToken");

        // /users/me với access token
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("rot@meetly.dev"));

        // không token → 401
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());

        // refresh → cookie mới, access mới
        MvcResult ref = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();
        Cookie refresh2 = ref.getResponse().getCookie("meetly_refresh");

        // token cũ đã bị revoke (rotation) → 401
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // logout với token mới → 204; dùng lại → 401
        mvc.perform(post("/api/v1/auth/logout").cookie(refresh2))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh2))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Chạy test → fail**

Run: `cd backend && ./mvnw -q test -Dtest=RefreshFlowIT`
Expected: FAIL.

- [ ] **Step 3: Implement**

`com/meetly/auth/AuthenticatedUser.java`:

```java
package com.meetly.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email) {}
```

`com/meetly/auth/JwtAuthFilter.java`:

```java
package com.meetly.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.AccessTokenClaims claims = jwtService.parse(header.substring(7));
                var principal = new AuthenticatedUser(claims.userId(), claims.email());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
                // token hỏng → đi tiếp không auth; entry point trả 401 nếu route cần auth
            }
        }
        chain.doFilter(req, res);
    }
}
```

Sửa `SecurityConfig.filterChain` — thêm filter + entry point 401 dạng ProblemDetail:

```java
package com.meetly.common;

import com.meetly.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/health",
                        "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(eh -> eh.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/problem+json");
                res.getWriter().write("""
                        {"status":401,"detail":"Unauthorized","code":"INVALID_CREDENTIALS"}""");
            }))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

Thêm vào `AuthService.java` (cuối class):

```java
    @Transactional
    public User rotate(String rawRefreshToken) {
        RefreshToken current = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .filter(RefreshToken::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token không hợp lệ"));
        current.setRevokedAt(Instant.now());
        return users.findById(current.getUserId()).orElseThrow();
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(rt -> rt.setRevokedAt(Instant.now()));
    }
```

Thêm vào `AuthController.java`:

```java
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshCookie) {
        if (refreshCookie == null) {
            throw new com.meetly.common.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    com.meetly.common.ErrorCode.INVALID_REFRESH_TOKEN, "Thiếu refresh token");
        }
        User user = authService.rotate(refreshCookie);
        return respondWithTokens(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshCookie) {
        if (refreshCookie != null) authService.revoke(refreshCookie);
        ResponseCookie cleared = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(props.cookieSecure())
                .path("/api/v1/auth").maxAge(0).sameSite("Lax").build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }
```

`com/meetly/user/UserController.java`:

```java
package com.meetly.user;

import com.meetly.auth.AuthDtos.UserDto;
import com.meetly.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new UserDto(user.id(), user.email(), null);
    }
}
```

Lưu ý: `fullName` trả `null` ở đây là thiếu — sửa `UserController` dùng repo:

```java
package com.meetly.user;

import com.meetly.auth.AuthDtos.UserDto;
import com.meetly.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository users;

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User u = users.findById(principal.id()).orElseThrow();
        return new UserDto(u.getId(), u.getEmail(), u.getFullName());
    }
}
```

- [ ] **Step 4: Chạy toàn bộ test BE**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ (Tasks 2–6).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(be): jwt filter, refresh rotation, logout, /users/me"
```

---

### Task 7: Meeting CRUD

**Files:**
- Create: `backend/src/main/java/com/meetly/meeting/MeetingDtos.java`, `MeetingCodeGenerator.java`, `MeetingService.java`, `MeetingController.java`
- Test: `backend/src/test/java/com/meetly/meeting/MeetingApiIT.java`, `backend/src/test/java/com/meetly/meeting/MeetingCodeGeneratorTest.java`

**Interfaces:**
- Consumes: `AuthenticatedUser` principal (Task 6), `MeetingRepository` (Task 3), `ApiException`/`ErrorCode` (Task 5).
- Produces: REST `/api/v1/meetings` (POST tạo, GET list mine, GET `/{code}`, PATCH `/{id}`, DELETE `/{id}` → set `CANCELLED`); `MeetingDtos.MeetingResponse(UUID id, String code, String title, String description, UUID hostId, Instant scheduledStartAt, Instant scheduledEndAt, String status, String roomType)`; `MeetingService.getByCode(String): Meeting` (404 `MEETING_NOT_FOUND`). Task 9 dùng `getByCode`.

- [ ] **Step 1: Viết test code generator (fail)**

`backend/src/test/java/com/meetly/meeting/MeetingCodeGeneratorTest.java`:

```java
package com.meetly.meeting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingCodeGeneratorTest {
    @Test
    void formatIsThreeFourThreeLowercase() {
        String code = new MeetingCodeGenerator().newCode();
        assertThat(code).matches("[a-z]{3}-[a-z]{4}-[a-z]{3}");
    }

    @Test
    void codesAreRandom() {
        MeetingCodeGenerator gen = new MeetingCodeGenerator();
        assertThat(gen.newCode()).isNotEqualTo(gen.newCode());
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement generator**

Run: `cd backend && ./mvnw -q test -Dtest=MeetingCodeGeneratorTest` → FAIL.

`com/meetly/meeting/MeetingCodeGenerator.java`:

```java
package com.meetly.meeting;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class MeetingCodeGenerator {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newCode() {
        return segment(3) + "-" + segment(4) + "-" + segment(3);
    }

    private String segment(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
```

Run lại: PASS.

- [ ] **Step 3: Viết test API (fail)**

`backend/src/test/java/com/meetly/meeting/MeetingApiIT.java`:

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
class MeetingApiIT {
    @Autowired MockMvc mvc;
    private String hostToken;
    private String otherToken;

    @BeforeEach
    void users() throws Exception {
        hostToken = registerAndGetToken("host+" + System.nanoTime() + "@meetly.dev");
        otherToken = registerAndGetToken("other+" + System.nanoTime() + "@meetly.dev");
    }

    private String registerAndGetToken(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    @Test
    void crudFlow() throws Exception {
        // create
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Sprint review","scheduledStartAt":"2026-07-18T09:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        String code = read(created, "$.code");
        String id = read(created, "$.id");

        // get by code (người khác cũng xem được — cần cho join)
        mvc.perform(get("/api/v1/meetings/" + code)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sprint review"));

        // list mine — host thấy 1, other thấy 0
        mvc.perform(get("/api/v1/meetings").header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/meetings").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // patch — chỉ host
        mvc.perform(patch("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Hacked"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEETING_HOST"));
        mvc.perform(patch("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Sprint review v2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sprint review v2"));

        // delete → CANCELLED
        mvc.perform(delete("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + code)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 404
        mvc.perform(get("/api/v1/meetings/zzz-zzzz-zzz")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
    }
}
```

- [ ] **Step 4: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=MeetingApiIT` → FAIL.

`com/meetly/meeting/MeetingDtos.java`:

```java
package com.meetly.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class MeetingDtos {
    public record CreateMeetingRequest(@NotBlank @Size(max = 255) String title,
                                       String description,
                                       Instant scheduledStartAt,
                                       Instant scheduledEndAt) {}

    public record UpdateMeetingRequest(@Size(max = 255) String title,
                                       String description,
                                       Instant scheduledStartAt,
                                       Instant scheduledEndAt) {}

    public record MeetingResponse(UUID id, String code, String title, String description,
                                  UUID hostId, Instant scheduledStartAt, Instant scheduledEndAt,
                                  String status, String roomType) {
        static MeetingResponse from(Meeting m) {
            return new MeetingResponse(m.getId(), m.getCode(), m.getTitle(), m.getDescription(),
                    m.getHostId(), m.getScheduledStartAt(), m.getScheduledEndAt(),
                    m.getStatus().name(), m.getRoomType().name());
        }
    }
}
```

`com/meetly/meeting/MeetingService.java`:

```java
package com.meetly.meeting;

import com.meetly.common.ApiException;
import com.meetly.common.ErrorCode;
import com.meetly.meeting.MeetingDtos.CreateMeetingRequest;
import com.meetly.meeting.MeetingDtos.UpdateMeetingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MeetingService {
    private final MeetingRepository meetings;
    private final MeetingCodeGenerator codeGenerator;

    @Transactional
    public Meeting create(UUID hostId, CreateMeetingRequest req) {
        Meeting m = new Meeting();
        m.setTitle(req.title());
        m.setDescription(req.description());
        m.setHostId(hostId);
        m.setScheduledStartAt(req.scheduledStartAt() != null ? req.scheduledStartAt() : Instant.now());
        m.setScheduledEndAt(req.scheduledEndAt());
        m.setCode(uniqueCode());
        return meetings.save(m);
    }

    private String uniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = codeGenerator.newCode();
            if (!meetings.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Không sinh được mã phòng duy nhất sau 5 lần");
    }

    @Transactional(readOnly = true)
    public Meeting getByCode(String code) {
        return meetings.findByCode(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.MEETING_NOT_FOUND, "Không tìm thấy phòng họp"));
    }

    @Transactional(readOnly = true)
    public List<Meeting> listMine(UUID hostId) {
        return meetings.findByHostIdOrderByScheduledStartAtDesc(hostId);
    }

    @Transactional
    public Meeting update(UUID meetingId, UUID actorId, UpdateMeetingRequest req) {
        Meeting m = requireHost(meetingId, actorId);
        if (req.title() != null) m.setTitle(req.title());
        if (req.description() != null) m.setDescription(req.description());
        if (req.scheduledStartAt() != null) m.setScheduledStartAt(req.scheduledStartAt());
        if (req.scheduledEndAt() != null) m.setScheduledEndAt(req.scheduledEndAt());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    @Transactional
    public void cancel(UUID meetingId, UUID actorId) {
        Meeting m = requireHost(meetingId, actorId);
        m.setStatus(MeetingStatus.CANCELLED);
        m.setUpdatedAt(Instant.now());
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

`com/meetly/meeting/MeetingController.java`:

```java
package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {
    private final MeetingService meetingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody CreateMeetingRequest req) {
        return MeetingResponse.from(meetingService.create(user.id(), req));
    }

    @GetMapping
    public List<MeetingResponse> listMine(@AuthenticationPrincipal AuthenticatedUser user) {
        return meetingService.listMine(user.id()).stream().map(MeetingResponse::from).toList();
    }

    @GetMapping("/{code}")
    public MeetingResponse getByCode(@PathVariable String code) {
        return MeetingResponse.from(meetingService.getByCode(code));
    }

    @PatchMapping("/{id}")
    public MeetingResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody UpdateMeetingRequest req) {
        return MeetingResponse.from(meetingService.update(id, user.id(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID id) {
        meetingService.cancel(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 5: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ.

```bash
git add backend/src
git commit -m "feat(be): meeting crud with room codes"
```

---

### Task 8: LiveKitTokenService

**Files:**
- Create: `backend/src/main/java/com/meetly/livekit/LiveKitProperties.java`, `LiveKitTokenService.java`
- Modify: `backend/src/main/java/com/meetly/MeetlyApplication.java` (đăng ký thêm properties)
- Test: `backend/src/test/java/com/meetly/livekit/LiveKitTokenServiceTest.java`

**Interfaces:**
- Produces: `LiveKitTokenService.createToken(String roomCode, UUID userId, String displayName, boolean canPublish, boolean roomAdmin, Instant expiresAt): String` — JWT HS256 ký bằng LiveKit API secret, claims `video.room`, `video.roomJoin=true`, `video.canPublish`, `video.canSubscribe=true`, `video.canPublishData=false`, `video.roomAdmin`; `LiveKitProperties(String apiKey, String apiSecret, String wsUrl)` prefix `meetly.livekit`. Task 9 gọi `createToken`.

- [ ] **Step 1: Viết test fail** (decode JWT bằng jjwt, verify claims — không cần LiveKit server chạy)

`backend/src/test/java/com/meetly/livekit/LiveKitTokenServiceTest.java`:

```java
package com.meetly.livekit;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveKitTokenServiceTest {
    private static final String SECRET = "meetly_dev_secret_0123456789abcdef";
    private final LiveKitTokenService service = new LiveKitTokenService(
            new LiveKitProperties("devkey", SECRET, "ws://localhost:7880"));

    @Test
    @SuppressWarnings("unchecked")
    void speakerTokenGrants() {
        UUID userId = UUID.randomUUID();
        String jwt = service.createToken("abc-defg-hij", userId, "Anh",
                true, false, Instant.now().plus(2, ChronoUnit.HOURS));

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("devkey");
        Map<String, Object> video = claims.get("video", Map.class);
        assertThat(video.get("room")).isEqualTo("abc-defg-hij");
        assertThat(video.get("roomJoin")).isEqualTo(true);
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostTokenHasRoomAdmin() {
        String jwt = service.createToken("abc-defg-hij", UUID.randomUUID(), "Host",
                true, true, Instant.now().plus(2, ChronoUnit.HOURS));
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();
        Map<String, Object> video = claims.get("video", Map.class);
        assertThat(video.get("roomAdmin")).isEqualTo(true);
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=LiveKitTokenServiceTest` → FAIL.

`com/meetly/livekit/LiveKitProperties.java`:

```java
package com.meetly.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meetly.livekit")
public record LiveKitProperties(String apiKey, String apiSecret, String wsUrl) {}
```

`com/meetly/livekit/LiveKitTokenService.java` (dùng SDK LiveKit):

```java
package com.meetly.livekit;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomAdmin;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class LiveKitTokenService {
    private final LiveKitProperties props;

    public LiveKitTokenService(LiveKitProperties props) {
        this.props = props;
    }

    public String createToken(String roomCode, UUID userId, String displayName,
                              boolean canPublish, boolean roomAdmin, Instant expiresAt) {
        AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
        token.setIdentity(userId.toString());
        token.setName(displayName);
        token.setExpiration(Date.from(expiresAt));
        token.addGrants(new RoomJoin(true), new RoomName(roomCode),
                new CanPublish(canPublish), new CanSubscribe(true),
                new CanPublishData(false));
        if (roomAdmin) token.addGrants(new RoomAdmin(true));
        return token.toJwt();
    }

    public String wsUrl() {
        return props.wsUrl();
    }
}
```

Sửa annotation trong `MeetlyApplication.java`:

```java
@EnableConfigurationProperties({AuthProperties.class, com.meetly.livekit.LiveKitProperties.class})
```

- [ ] **Step 3: Chạy → pass, commit**

Run: `cd backend && ./mvnw -q test -Dtest=LiveKitTokenServiceTest`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(be): livekit token service with role-based grants"
```

*Ghi chú cho người thực hiện: nếu API của SDK `io.livekit:livekit-server` 0.10.2 khác (tên class grant / `setExpiration`), ưu tiên chỉnh implementation theo SDK — test claims ở trên là hợp đồng đúng theo [LiveKit token spec], giữ nguyên test.*

---

### Task 9: Join endpoint

**Files:**
- Create: `backend/src/main/java/com/meetly/meeting/JoinController.java`
- Modify: `backend/src/main/java/com/meetly/meeting/MeetingService.java` (thêm `join`), `MeetingDtos.java` (thêm `JoinResponse`)
- Test: `backend/src/test/java/com/meetly/meeting/JoinApiIT.java`

**Interfaces:**
- Consumes: `MeetingService.getByCode`, `LiveKitTokenService.createToken/wsUrl`, `UserRepository`.
- Produces: `POST /api/v1/meetings/{code}/join` → 200 `JoinResponse(String livekitUrl, String livekitToken, String role)` với role `"HOST"` | `"SPEAKER"`. Lỗi: 404 `MEETING_NOT_FOUND`; 409 `MEETING_ENDED` (status `ENDED`/`CANCELLED`); 403 `MEETING_NOT_STARTED` (trước giờ bắt đầu >15 phút, host được bỏ qua). FE (Task 13) tiêu thụ `JoinResponse`.

- [ ] **Step 1: Viết test fail**

`backend/src/test/java/com/meetly/meeting/JoinApiIT.java`:

```java
package com.meetly.meeting;

import com.meetly.TestcontainersConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class JoinApiIT {
    private static final String LIVEKIT_SECRET = "meetly_dev_secret_0123456789abcdef";

    @Autowired MockMvc mvc;
    private String hostToken;
    private String guestToken;

    @BeforeEach
    void setUp() throws Exception {
        hostToken = register("h+" + System.nanoTime() + "@meetly.dev");
        guestToken = register("g+" + System.nanoTime() + "@meetly.dev");
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123","fullName":"U"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.accessToken");
    }

    private String createMeeting(String bearer, String bodyJson) throws Exception {
        String body = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(bodyJson))
                .andReturn().getResponse().getContentAsString();
        return read(body, "$.code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostAndParticipantJoin() throws Exception {
        String code = createMeeting(hostToken, """
                {"title":"Now meeting"}""");

        // host join → HOST, token có roomAdmin
        String hostJoin = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HOST"))
                .andExpect(jsonPath("$.livekitUrl").value("ws://localhost:7880"))
                .andReturn().getResponse().getContentAsString();
        Claims hostClaims = parseLivekit(read(hostJoin, "$.livekitToken"));
        Map<String, Object> hostVideo = hostClaims.get("video", Map.class);
        assertThat(hostVideo.get("room")).isEqualTo(code);
        assertThat(hostVideo.get("roomAdmin")).isEqualTo(true);
        assertThat(hostVideo.get("canPublish")).isEqualTo(true);

        // người khác join → SPEAKER, không roomAdmin
        String guestJoin = mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SPEAKER"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> guestVideo = parseLivekit(read(guestJoin, "$.livekitToken"))
                .get("video", Map.class);
        assertThat(guestVideo.get("roomAdmin")).isNull();
        assertThat(guestVideo.get("canPublishData")).isEqualTo(false);
    }

    @Test
    void joinTooEarlyForbiddenExceptHost() throws Exception {
        String code = createMeeting(hostToken, """
                {"title":"Future","scheduledStartAt":"2030-01-01T00:00:00Z"}""");

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_STARTED"));

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk());
    }

    @Test
    void joinCancelledConflict() throws Exception {
        String created = mvc.perform(post("/api/v1/meetings")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(APPLICATION_JSON).content("""
                                {"title":"Bye"}"""))
                .andReturn().getResponse().getContentAsString();
        String code = read(created, "$.code");
        String id = read(created, "$.id");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/meetings/" + id)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/meetings/" + code + "/join")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_ENDED"));
    }

    private Claims parseLivekit(String jwt) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(LIVEKIT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload();
    }
}
```

- [ ] **Step 2: Chạy → fail, rồi implement**

Run: `cd backend && ./mvnw -q test -Dtest=JoinApiIT` → FAIL.

Thêm vào `MeetingDtos.java`:

```java
    public record JoinResponse(String livekitUrl, String livekitToken, String role) {}
```

Thêm vào `MeetingService.java` (inject thêm `LiveKitTokenService liveKitTokenService` và `com.meetly.user.UserRepository users` vào constructor qua `@RequiredArgsConstructor`):

```java
    @Transactional(readOnly = true)
    public MeetingDtos.JoinResponse join(String code, UUID userId) {
        Meeting m = getByCode(code);
        boolean isHost = m.getHostId().equals(userId);

        if (m.getStatus() == MeetingStatus.ENDED || m.getStatus() == MeetingStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.MEETING_ENDED,
                    "Phòng họp đã kết thúc hoặc bị hủy");
        }
        Instant earliestJoin = m.getScheduledStartAt().minus(15, ChronoUnit.MINUTES);
        if (!isHost && Instant.now().isBefore(earliestJoin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.MEETING_NOT_STARTED,
                    "Phòng họp chưa bắt đầu (được vào sớm tối đa 15 phút)");
        }

        var user = users.findById(userId).orElseThrow();
        Instant expiresAt = (m.getScheduledEndAt() != null
                ? m.getScheduledEndAt() : Instant.now().plus(4, ChronoUnit.HOURS))
                .plus(2, ChronoUnit.HOURS);
        String token = liveKitTokenService.createToken(
                m.getCode(), userId, user.getFullName(),
                true /* Phase 1: ai cũng publish */, isHost, expiresAt);
        return new MeetingDtos.JoinResponse(liveKitTokenService.wsUrl(), token,
                isHost ? "HOST" : "SPEAKER");
    }
```

(Thêm import `java.time.temporal.ChronoUnit`, `com.meetly.livekit.LiveKitTokenService`.)

`com/meetly/meeting/JoinController.java`:

```java
package com.meetly.meeting;

import com.meetly.auth.AuthenticatedUser;
import com.meetly.meeting.MeetingDtos.JoinResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class JoinController {
    private final MeetingService meetingService;

    @PostMapping("/{code}/join")
    public JoinResponse join(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable String code) {
        return meetingService.join(code, user.id());
    }
}
```

- [ ] **Step 3: Chạy toàn bộ BE test → pass, commit**

Run: `cd backend && ./mvnw -q test`
Expected: PASS toàn bộ.

```bash
git add backend/src
git commit -m "feat(be): join endpoint issuing livekit tokens"
```

---

### Task 10: Frontend bootstrap (Vite + client + authStore)

**Files:**
- Create: `frontend/` (Vite react-ts scaffold), `frontend/vite.config.ts`, `frontend/src/index.css`
- Create: `frontend/src/stores/authStore.ts`, `frontend/src/api/client.ts`, `frontend/src/api/types.ts`
- Test: `frontend/src/stores/authStore.test.ts`

**Interfaces:**
- Produces: `useAuthStore` (state `{user: User|null, accessToken: string|null, ready: boolean}`, actions `setAuth(user, accessToken)`, `clear()`, `setReady()`); axios instance `api` baseURL `/api/v1` tự gắn Bearer + tự refresh khi 401; `bootstrapAuth(): Promise<void>` gọi refresh 1 lần lúc app load. Types: `User {id,email,fullName}`, `Meeting {id,code,title,description,hostId,scheduledStartAt,scheduledEndAt,status,roomType}`, `JoinResponse {livekitUrl,livekitToken,role}` — khớp BE Task 7/9.

- [ ] **Step 1: Scaffold + deps**

Run (từ repo root):

```bash
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
npm install axios @tanstack/react-query zustand react-router-dom \
  livekit-client @livekit/components-react @livekit/components-styles
npm install -D tailwindcss @tailwindcss/vite vitest @testing-library/react \
  @testing-library/jest-dom jsdom @playwright/test
```

- [ ] **Step 2: Cấu hình Vite (proxy + vitest) và Tailwind**

`frontend/vite.config.ts`:

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
```

`frontend/src/index.css` (thay toàn bộ nội dung):

```css
@import 'tailwindcss';
```

Thêm script test vào `frontend/package.json` (trong `"scripts"`):

```json
    "test": "vitest run",
    "e2e": "playwright test"
```

- [ ] **Step 3: Viết test authStore (fail)**

`frontend/src/stores/authStore.test.ts`:

```ts
import { describe, expect, it, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clear());

  it('bắt đầu chưa đăng nhập, chưa ready', () => {
    const s = useAuthStore.getState();
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
    expect(s.ready).toBe(false);
  });

  it('setAuth lưu user + token', () => {
    useAuthStore.getState().setAuth(
      { id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    const s = useAuthStore.getState();
    expect(s.user?.email).toBe('a@b.c');
    expect(s.accessToken).toBe('tok');
  });

  it('clear xóa user nhưng giữ ready', () => {
    const st = useAuthStore.getState();
    st.setReady();
    st.setAuth({ id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().ready).toBe(true);
  });
});
```

Run: `cd frontend && npm test` → FAIL (chưa có store).

- [ ] **Step 4: Implement types + store + client**

`frontend/src/api/types.ts`:

```ts
export type User = { id: string; email: string; fullName: string };

export type AuthResponse = { accessToken: string; user: User };

export type Meeting = {
  id: string;
  code: string;
  title: string;
  description: string | null;
  hostId: string;
  scheduledStartAt: string;
  scheduledEndAt: string | null;
  status: 'SCHEDULED' | 'LIVE' | 'ENDED' | 'CANCELLED';
  roomType: 'MEETING' | 'WEBINAR';
};

export type JoinResponse = { livekitUrl: string; livekitToken: string; role: 'HOST' | 'SPEAKER' };

export type ApiError = { status: number; detail: string; code: string; fields?: Record<string, string> };
```

`frontend/src/stores/authStore.ts`:

```ts
import { create } from 'zustand';
import type { User } from '../api/types';

type AuthState = {
  user: User | null;
  accessToken: string | null;
  /** true sau khi bootstrapAuth chạy xong (dù thành công hay không) */
  ready: boolean;
  setAuth: (user: User, accessToken: string) => void;
  setReady: () => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  ready: false,
  setAuth: (user, accessToken) => set({ user, accessToken }),
  setReady: () => set({ ready: true }),
  clear: () => set({ user: null, accessToken: null, ready: get().ready }),
}));
```

`frontend/src/api/client.ts`:

```ts
import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../stores/authStore';
import type { AuthResponse } from './types';

export const api = axios.create({ baseURL: '/api/v1', withCredentials: true });

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let refreshing: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  try {
    const { data } = await axios.post<AuthResponse>('/api/v1/auth/refresh', null, {
      withCredentials: true,
    });
    useAuthStore.getState().setAuth(data.user, data.accessToken);
    return data.accessToken;
  } catch {
    return null;
  }
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined;
    const isAuthRoute = config?.url?.startsWith('/auth');
    if (error.response?.status === 401 && config && !config._retry && !isAuthRoute) {
      config._retry = true;
      refreshing ??= refreshAccessToken().finally(() => (refreshing = null));
      const token = await refreshing;
      if (token) return api(config);
      useAuthStore.getState().clear();
      window.location.assign('/login');
    }
    throw error;
  },
);

/** Gọi 1 lần lúc app load để khôi phục session từ refresh cookie. */
export async function bootstrapAuth(): Promise<void> {
  await refreshAccessToken();
  useAuthStore.getState().setReady();
}
```

- [ ] **Step 5: Chạy test → pass, commit**

Run: `cd frontend && npm test`
Expected: PASS (3 tests).

```bash
git add frontend/
git commit -m "feat(fe): vite bootstrap, api client with refresh, auth store"
```

---

### Task 11: Router + trang Login/Register + ProtectedRoute

**Files:**
- Modify: `frontend/src/main.tsx`, xóa `frontend/src/App.tsx` mặc định và thay bằng router
- Create: `frontend/src/App.tsx`, `frontend/src/components/ProtectedRoute.tsx`
- Create: `frontend/src/features/auth/LoginPage.tsx`, `frontend/src/features/auth/RegisterPage.tsx`, `frontend/src/features/auth/useAuth.ts`
- Test: `frontend/src/components/ProtectedRoute.test.tsx`

**Interfaces:**
- Consumes: `useAuthStore`, `api`, `bootstrapAuth` (Task 10).
- Produces: routes `/login`, `/register`, `/` (protected); `useAuth()` trả `{ login(email, password), register(email, password, fullName), logout() }` — login/register tự `setAuth` + navigate `/`; `<ProtectedRoute>` render `<Outlet/>` nếu có user, chưa `ready` thì hiện "Đang tải...", không có user → redirect `/login`.

- [ ] **Step 1: Viết test ProtectedRoute (fail)**

`frontend/src/components/ProtectedRoute.test.tsx`:

```tsx
import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { useAuthStore } from '../stores/authStore';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<div>dashboard</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, accessToken: null, ready: false });
  });

  it('hiện loading khi chưa ready', () => {
    renderAt('/');
    expect(screen.getByText('Đang tải...')).toBeTruthy();
  });

  it('redirect /login khi ready mà không có user', () => {
    useAuthStore.setState({ ready: true });
    renderAt('/');
    expect(screen.getByText('login page')).toBeTruthy();
  });

  it('render nội dung khi có user', () => {
    useAuthStore.setState({
      ready: true,
      user: { id: '1', email: 'a@b.c', fullName: 'A' },
      accessToken: 't',
    });
    renderAt('/');
    expect(screen.getByText('dashboard')).toBeTruthy();
  });
});
```

Run: `cd frontend && npm test` → FAIL.

- [ ] **Step 2: Implement**

`frontend/src/components/ProtectedRoute.tsx`:

```tsx
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

export function ProtectedRoute() {
  const { user, ready } = useAuthStore();
  if (!ready) return <div className="p-8 text-center text-gray-500">Đang tải...</div>;
  if (!user) return <Navigate to="/login" replace />;
  return <Outlet />;
}
```

`frontend/src/features/auth/useAuth.ts`:

```ts
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { useAuthStore } from '../../stores/authStore';
import type { AuthResponse } from '../../api/types';

export function useAuth() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const clear = useAuthStore((s) => s.clear);

  return {
    async login(email: string, password: string) {
      const { data } = await api.post<AuthResponse>('/auth/login', { email, password });
      setAuth(data.user, data.accessToken);
      navigate('/');
    },
    async register(email: string, password: string, fullName: string) {
      const { data } = await api.post<AuthResponse>('/auth/register', { email, password, fullName });
      setAuth(data.user, data.accessToken);
      navigate('/');
    },
    async logout() {
      await api.post('/auth/logout');
      clear();
      navigate('/login');
    },
  };
}
```

`frontend/src/features/auth/LoginPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useAuth } from './useAuth';

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (err) {
      setError(isAxiosError(err) && err.response?.status === 401
        ? 'Email hoặc mật khẩu không đúng'
        : 'Có lỗi xảy ra, thử lại sau');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">Meetly</h1>
        <input className="w-full border rounded-lg px-3 py-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="password" placeholder="Mật khẩu"
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button className="w-full bg-blue-600 text-white rounded-lg py-2 disabled:opacity-50"
                disabled={busy} type="submit">
          {busy ? 'Đang đăng nhập...' : 'Đăng nhập'}
        </button>
        <p className="text-sm text-center text-gray-600">
          Chưa có tài khoản? <Link className="text-blue-600" to="/register">Đăng ký</Link>
        </p>
      </form>
    </div>
  );
}
```

`frontend/src/features/auth/RegisterPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useAuth } from './useAuth';

export function RegisterPage() {
  const { register } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await register(email, password, fullName);
    } catch (err) {
      if (isAxiosError(err) && err.response?.data?.code === 'EMAIL_TAKEN') {
        setError('Email này đã được đăng ký');
      } else if (isAxiosError(err) && err.response?.status === 400) {
        setError('Mật khẩu tối thiểu 8 ký tự');
      } else {
        setError('Có lỗi xảy ra, thử lại sau');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">Tạo tài khoản Meetly</h1>
        <input className="w-full border rounded-lg px-3 py-2" placeholder="Họ tên"
               value={fullName} onChange={(e) => setFullName(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="password"
               placeholder="Mật khẩu (≥ 8 ký tự)" minLength={8}
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button className="w-full bg-blue-600 text-white rounded-lg py-2 disabled:opacity-50"
                disabled={busy} type="submit">
          {busy ? 'Đang tạo...' : 'Đăng ký'}
        </button>
        <p className="text-sm text-center text-gray-600">
          Đã có tài khoản? <Link className="text-blue-600" to="/login">Đăng nhập</Link>
        </p>
      </form>
    </div>
  );
}
```

`frontend/src/App.tsx` (thay hoàn toàn file scaffold):

```tsx
import { useEffect } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { bootstrapAuth } from './api/client';
import { ProtectedRoute } from './components/ProtectedRoute';
import { LoginPage } from './features/auth/LoginPage';
import { RegisterPage } from './features/auth/RegisterPage';

const queryClient = new QueryClient();

export default function App() {
  useEffect(() => {
    void bootstrapAuth();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<div className="p-8">Dashboard (Task 12)</div>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
```

`frontend/src/main.tsx` (thay file scaffold):

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

Xóa file scaffold không dùng: `frontend/src/App.css`, `frontend/src/assets/react.svg`.

- [ ] **Step 3: Chạy test + verify thủ công**

Run: `cd frontend && npm test` → PASS.
Run: `npm run dev` (BE + compose đang chạy) → mở http://localhost:5173/register → đăng ký → về `/` thấy "Dashboard (Task 12)". Reload trang → vẫn đăng nhập (bootstrapAuth).

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(fe): router, login/register pages, protected route"
```

---

### Task 12: Dashboard — danh sách + tạo meeting

**Files:**
- Create: `frontend/src/features/meetings/meetingApi.ts`, `frontend/src/features/meetings/DashboardPage.tsx`
- Modify: `frontend/src/App.tsx` (route `/` dùng DashboardPage)

**Interfaces:**
- Consumes: `api`, types `Meeting` (Task 10), `useAuth` (Task 11).
- Produces: `useMyMeetings(): UseQueryResult<Meeting[]>`; `useCreateMeeting(): UseMutationResult<Meeting, unknown, CreateMeetingInput>` với `CreateMeetingInput {title, scheduledStartAt?, scheduledEndAt?, description?}`; nút **"Họp ngay"** tạo meeting title "Họp nhanh" start now rồi navigate `/m/{code}`; Task 13 nhận route `/m/:code`.

- [ ] **Step 1: Implement API hooks**

`frontend/src/features/meetings/meetingApi.ts`:

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Meeting } from '../../api/types';

export type CreateMeetingInput = {
  title: string;
  description?: string;
  scheduledStartAt?: string;
  scheduledEndAt?: string;
};

export function useMyMeetings() {
  return useQuery({
    queryKey: ['meetings'],
    queryFn: async () => (await api.get<Meeting[]>('/meetings')).data,
  });
}

export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateMeetingInput) =>
      (await api.post<Meeting>('/meetings', input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['meetings'] }),
  });
}
```

- [ ] **Step 2: Implement DashboardPage**

`frontend/src/features/meetings/DashboardPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useAuth } from '../auth/useAuth';
import { useCreateMeeting, useMyMeetings } from './meetingApi';

export function DashboardPage() {
  const user = useAuthStore((s) => s.user);
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { data: meetings, isLoading } = useMyMeetings();
  const createMeeting = useCreateMeeting();

  const [title, setTitle] = useState('');
  const [startAt, setStartAt] = useState('');

  async function meetNow() {
    const m = await createMeeting.mutateAsync({ title: 'Họp nhanh' });
    navigate(`/m/${m.code}`);
  }

  async function schedule(e: FormEvent) {
    e.preventDefault();
    await createMeeting.mutateAsync({
      title,
      scheduledStartAt: startAt ? new Date(startAt).toISOString() : undefined,
    });
    setTitle('');
    setStartAt('');
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow-sm px-6 py-3 flex items-center justify-between">
        <h1 className="text-xl font-bold text-blue-600">Meetly</h1>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-gray-700">{user?.fullName}</span>
          <button onClick={() => void logout()} className="text-gray-500 hover:text-gray-800">
            Đăng xuất
          </button>
        </div>
      </header>

      <main className="max-w-3xl mx-auto p-6 space-y-6">
        <div className="flex gap-3">
          <button onClick={() => void meetNow()} disabled={createMeeting.isPending}
                  className="bg-blue-600 text-white rounded-lg px-5 py-2.5 font-medium disabled:opacity-50">
            Họp ngay
          </button>
        </div>

        <form onSubmit={schedule} className="bg-white rounded-xl shadow p-4 flex gap-3 items-end">
          <label className="flex-1 text-sm">
            Tiêu đề
            <input className="mt-1 w-full border rounded-lg px-3 py-2" value={title}
                   onChange={(e) => setTitle(e.target.value)} required placeholder="Họp team tuần" />
          </label>
          <label className="text-sm">
            Bắt đầu lúc
            <input className="mt-1 border rounded-lg px-3 py-2" type="datetime-local"
                   value={startAt} onChange={(e) => setStartAt(e.target.value)} />
          </label>
          <button className="bg-gray-800 text-white rounded-lg px-4 py-2 disabled:opacity-50"
                  disabled={createMeeting.isPending} type="submit">
            Đặt lịch
          </button>
        </form>

        <section className="bg-white rounded-xl shadow divide-y">
          <h2 className="px-4 py-3 font-semibold">Meeting của tôi</h2>
          {isLoading && <p className="px-4 py-3 text-gray-500">Đang tải...</p>}
          {meetings?.length === 0 && (
            <p className="px-4 py-3 text-gray-500">Chưa có meeting nào</p>
          )}
          {meetings?.map((m) => (
            <div key={m.id} className="px-4 py-3 flex items-center justify-between">
              <div>
                <p className="font-medium">{m.title}</p>
                <p className="text-sm text-gray-500">
                  {new Date(m.scheduledStartAt).toLocaleString('vi-VN')} · {m.code} · {m.status}
                </p>
              </div>
              {(m.status === 'SCHEDULED' || m.status === 'LIVE') && (
                <button onClick={() => navigate(`/m/${m.code}`)}
                        className="text-blue-600 font-medium hover:underline">
                  Vào phòng
                </button>
              )}
            </div>
          ))}
        </section>
      </main>
    </div>
  );
}
```

Sửa route `/` trong `frontend/src/App.tsx`: thêm import `import { DashboardPage } from './features/meetings/DashboardPage';` và thay `<Route path="/" element={<div className="p-8">Dashboard (Task 12)</div>} />` bằng:

```tsx
            <Route path="/" element={<DashboardPage />} />
```

- [ ] **Step 3: Verify thủ công + test suite**

Run: `cd frontend && npm test` → PASS (không hỏng test cũ).
Manual: đăng nhập → "Đặt lịch" 1 meeting → hiện trong danh sách; "Họp ngay" → chuyển tới `/m/<code>` (trang trắng — Task 13 làm).

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(fe): dashboard with meeting list, meet-now, schedule"
```

---

### Task 13: PreJoin + Room (LiveKit)

**Files:**
- Create: `frontend/src/features/room/roomApi.ts`, `frontend/src/features/room/PreJoinPage.tsx`, `frontend/src/features/room/RoomPage.tsx`
- Modify: `frontend/src/App.tsx` (thêm 2 routes)

**Interfaces:**
- Consumes: `JoinResponse` type (Task 10), `api`; BE `POST /meetings/{code}/join` (Task 9); components `PreJoin`, `LiveKitRoom`, `VideoConference` từ `@livekit/components-react`.
- Produces: route `/m/:code` (pre-join, xin quyền thiết bị, preview) và `/m/:code/room` (gọi join API → render LiveKitRoom). Rời phòng → về `/`.

- [ ] **Step 1: Implement join hook**

`frontend/src/features/room/roomApi.ts`:

```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { JoinResponse } from '../../api/types';

export function useJoinMeeting() {
  return useMutation({
    mutationFn: async (code: string) =>
      (await api.post<JoinResponse>(`/meetings/${code}/join`)).data,
  });
}
```

- [ ] **Step 2: Implement PreJoinPage**

`frontend/src/features/room/PreJoinPage.tsx`:

```tsx
import { PreJoin } from '@livekit/components-react';
import '@livekit/components-styles';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';

export function PreJoinPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center" data-lk-theme="default">
      <div className="w-full max-w-2xl">
        <h1 className="text-white text-center text-xl mb-4">Phòng {code}</h1>
        <PreJoin
          defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
          joinLabel="Vào phòng"
          micLabel="Micro"
          camLabel="Camera"
          onSubmit={(choices) => {
            navigate(`/m/${code}/room`, {
              state: {
                videoEnabled: choices.videoEnabled,
                audioEnabled: choices.audioEnabled,
                videoDeviceId: choices.videoDeviceId,
                audioDeviceId: choices.audioDeviceId,
              },
            });
          }}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Implement RoomPage**

`frontend/src/features/room/RoomPage.tsx`:

```tsx
import { useEffect } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import '@livekit/components-styles';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useJoinMeeting } from './roomApi';

type PreJoinChoices = {
  videoEnabled?: boolean;
  audioEnabled?: boolean;
  videoDeviceId?: string;
  audioDeviceId?: string;
};

export function RoomPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const choices = (useLocation().state ?? {}) as PreJoinChoices;
  const join = useJoinMeeting();

  useEffect(() => {
    if (code) join.mutate(code);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  if (join.isError) {
    const detail =
      isAxiosError(join.error) && join.error.response?.data?.code === 'MEETING_NOT_STARTED'
        ? 'Phòng họp chưa bắt đầu. Quay lại sau nhé.'
        : isAxiosError(join.error) && join.error.response?.data?.code === 'MEETING_ENDED'
          ? 'Phòng họp đã kết thúc hoặc bị hủy.'
          : 'Không vào được phòng họp.';
    return (
      <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center gap-4 text-white">
        <p>{detail}</p>
        <button onClick={() => navigate('/')} className="bg-blue-600 rounded-lg px-4 py-2">
          Về trang chính
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
        video={choices.videoEnabled ?? true}
        audio={choices.audioEnabled ?? true}
        onDisconnected={() => navigate('/')}
      >
        <VideoConference />
      </LiveKitRoom>
    </div>
  );
}
```

Sửa `frontend/src/App.tsx` — thêm imports:

```tsx
import { PreJoinPage } from './features/room/PreJoinPage';
import { RoomPage } from './features/room/RoomPage';
```

và thêm 2 routes bên trong `<Route element={<ProtectedRoute />}>` (sau route `/`):

```tsx
            <Route path="/m/:code" element={<PreJoinPage />} />
            <Route path="/m/:code/room" element={<RoomPage />} />
```

- [ ] **Step 4: Verify thủ công — Definition of Done của Phase 1**

Chuẩn bị: `docker compose -f ops/compose/docker-compose.dev.yml up -d` + BE (`./mvnw spring-boot:run`) + FE (`npm run dev`).

1. Cửa sổ thường: đăng ký user A → "Họp ngay" → PreJoin thấy preview camera → "Vào phòng" → thấy video mình.
2. Cửa sổ ẩn danh: đăng ký user B → mở `http://localhost:5173/m/<code>` (code từ URL cửa sổ A) → vào phòng.
3. Expected: **cả 2 cửa sổ thấy 2 video tiles**, tên hiển thị đúng, mute/unmute từ control bar hoạt động.

- [ ] **Step 5: Chạy test + commit**

Run: `cd frontend && npm test` → PASS.

```bash
git add frontend/
git commit -m "feat(fe): prejoin and room pages with livekit"
```

---

### Task 14: Playwright e2e — 2 người thấy nhau

**Files:**
- Create: `frontend/playwright.config.ts`, `frontend/e2e/two-users-meet.spec.ts`

**Interfaces:**
- Consumes: toàn bộ stack chạy local (compose + BE 8080 + FE 5173).

- [ ] **Step 1: Viết config**

`frontend/playwright.config.ts`:

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  use: {
    baseURL: 'http://localhost:5173',
    launchOptions: {
      args: [
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
      ],
    },
    permissions: ['camera', 'microphone'],
  },
});
```

- [ ] **Step 2: Viết e2e test**

`frontend/e2e/two-users-meet.spec.ts`:

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

test('hai người join cùng phòng và thấy video của nhau', async ({ browser }) => {
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();

  // User A: đăng ký → Họp ngay → vào phòng
  await registerAndLogin(pageA, 'Alice');
  await pageA.getByRole('button', { name: 'Họp ngay' }).click();
  await pageA.waitForURL(/\/m\/[a-z]{3}-[a-z]{4}-[a-z]{3}$/);
  const code = pageA.url().split('/m/')[1];
  await pageA.getByRole('button', { name: 'Vào phòng' }).click();
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // User B: đăng ký → join bằng code
  await registerAndLogin(pageB, 'Bob');
  await pageB.goto(`/m/${code}`);
  await pageB.getByRole('button', { name: 'Vào phòng' }).click();

  // Cả hai thấy 2 tiles
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });
  await expect(pageB.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });

  await contextA.close();
  await contextB.close();
});
```

- [ ] **Step 3: Chạy e2e**

Chuẩn bị: compose + BE + FE đang chạy (như Task 13 Step 4). Cài browser: `cd frontend && npx playwright install chromium`.

Run: `cd frontend && npm run e2e`
Expected: `1 passed`.

- [ ] **Step 4: Commit + cập nhật README nếu lệnh thực tế khác**

```bash
git add frontend/
git commit -m "test(e2e): two users join same room and see each other"
```

---

## Definition of Done — Phase 1

- [ ] `docker compose -f ops/compose/docker-compose.dev.yml up -d` + 2 lệnh chạy BE/FE trong README là đủ để demo.
- [ ] `cd backend && ./mvnw test` xanh; `cd frontend && npm test` xanh; e2e `npm run e2e` xanh.
- [ ] 2 trình duyệt đăng ký 2 tài khoản, join cùng code, thấy video nhau (DoD của spec mục 8, Phase 1).
