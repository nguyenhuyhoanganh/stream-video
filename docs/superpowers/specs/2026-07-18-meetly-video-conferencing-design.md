# Meetly — Thiết kế hệ thống họp video kiểu Zoom

**Ngày:** 2026-07-18
**Trạng thái:** Đã duyệt thiết kế, chờ lập kế hoạch implementation
**Stack:** Java 21 / Spring Boot 3.3 · React 18 / TypeScript · LiveKit · Kubernetes

---

## 1. Bối cảnh & mục tiêu

Xây dựng sản phẩm họp video **production** (không phải POC) với yêu cầu:

- Phòng họp tới **100+ người** dạng webinar: vài người phát biểu (host/speaker), phần lớn là người xem (attendee).
- Tính năng MVP: video/audio call, **screen share**, **chat trong phòng** (kèm giơ tay, moderation), **recording** lưu trữ xem lại, **quản lý user & lịch họp** (đăng ký/đăng nhập, đặt lịch, link mời, phân quyền).
- Hạ tầng: **Cloud + Kubernetes** (EKS/GKE).
- Đội ngũ code **Java Spring Boot (BE)** và **ReactJS (FE)**; tầng media dùng hệ mã nguồn mở, vận hành như hạ tầng.

### Ngoài phạm vi MVP (để phase sau)

HLS broadcast cho phòng 500+, waiting room, breakout rooms, E2E encryption, gọi vào bằng điện thoại (PSTN), mobile app native, virtual background.

---

## 2. Các quyết định kiến trúc chính (decision log)

| # | Quyết định | Lý do / phương án bị loại |
|---|---|---|
| D1 | **SFU = LiveKit self-hosted** (Apache 2.0) | 100+ người/phòng bắt buộc SFU. LiveKit có Helm chart, multi-node qua Redis, Egress (recording + HLS), React SDK, Java server SDK. *Loại:* Jitsi (tùy biến thành sản phẩm riêng khó, XMPP phức tạp, Jibri nặng); tự build quanh mediasoup/Janus (6–12 tháng tầng media, rủi ro cao). |
| D2 | **Media không bao giờ chạm Spring Boot** | Video đi thẳng Browser ↔ LiveKit qua UDP/WebRTC. Spring Boot là control plane: cấp LiveKit access token chứa grants quyết định quyền publish/subscribe. |
| D3 | **Phân quyền phòng họp thực thi bằng token grants**, không phải UI | HOST/SPEAKER: `canPublish=true`. ATTENDEE: `canPublish=false`. Promote = cấp lại grants runtime qua RoomService API. |
| D4 | **Chat đi qua Spring Boot (STOMP over WebSocket)**, không dùng LiveKit data channel | Webinar cần moderation (host xóa tin, mute), lưu lịch sử tin cậy server-side, Q&A. Đồng bộ đa pod bằng Redis pub/sub. LiveKit data channel không dùng cho chat (`canPublishData=false` trong token). |
| D5 | **Monorepo**: `backend/ + frontend/ + ops/` | Team nhỏ, đồng bộ contract dễ, CI build theo path filter. |
| D6 | **PostgreSQL + Redis dùng managed service** (RDS/CloudSQL, ElastiCache) | Không tự vận hành stateful trong K8s. In-cluster chỉ cho staging/dev. |
| D7 | **Auth: JWT access (15 phút) + refresh token rotation (14 ngày, httpOnly cookie)** | Stateless, scale ngang pod API tự do. Refresh lưu hash trong DB, thu hồi được. |
| D8 | **Guest join được phép, chỉ vai ATTENDEE** | Webinar cần người ngoài vào xem không cần tài khoản. Guest nhận JWT scoped chỉ cho meeting đó. |
| D9 | **Webinar 100+ vẫn join qua WebRTC** (simulcast + adaptive stream) | LiveKit chịu được vài trăm subscriber/phòng. HLS chỉ cần khi ~500+, để phase sau. |

---

## 3. Kiến trúc tổng thể

```
                        ┌──────────────────────────────────────────┐
                        │              Kubernetes (EKS/GKE)         │
                        │                                          │
  ┌─────────┐  HTTPS    │  ┌─────────┐      ┌──────────────────┐  │
  │ Browser │───────────┼─►│ Ingress │─────►│  meetly-api       │  │
  │ (React  │           │  │ (nginx) │      │  (Spring Boot)    │  │
  │  SPA)   │           │  └─────────┘      │  - Auth/JWT       │  │
  └────┬────┘           │       │           │  - Users/Meetings │  │
       │                │       │           │  - Chat WS (STOMP)│  │
       │                │       ▼           │  - LiveKit tokens │  │
       │                │  ┌─────────┐      │  - Webhook receiver│ │
       │                │  │ meetly- │      └───┬────────┬──────┘  │
       │                │  │ web     │          ▼        ▼         │
       │                │  │ (nginx) │     ┌────────┐ ┌───────┐   │
       │                │  └─────────┘     │Postgres│ │ Redis │   │
       │ WebRTC (UDP)   │                  └────────┘ └───┬───┘   │
       └────────────────┼─►┌──────────────┐◄──────────────┘       │
                        │  │ LiveKit SFU  │  (multi-node routing)  │
                        │  │ (2+ nodes)   │                        │
                        │  └──────┬───────┘                        │
                        │         └►┌────────┐──► S3 (recordings)  │
                        │           │ Egress │                     │
                        │           └────────┘                     │
                        │  ┌──────────────┐                        │
                        │  │ coturn (TURN)│ ◄── fallback UDP bị chặn│
                        │  └──────────────┘                        │
                        └──────────────────────────────────────────┘
```

| Thành phần | Vai trò | Code hay vận hành |
|---|---|---|
| `meetly-api` (Spring Boot 3.3, Java 21) | Control plane: auth, user, meeting, lịch, phân quyền, chat, cấp token, webhook | **Code** |
| `meetly-web` (React 18 + Vite + TS) | SPA quản lý + phòng họp | **Code** |
| LiveKit SFU | Toàn bộ media WebRTC, simulcast, 100+ người/phòng | Vận hành (Helm) |
| LiveKit Egress | Recording → MP4 → S3; (HLS phase sau) | Vận hành |
| coturn | TURN relay, `turns:443` cho firewall chặn UDP | Vận hành |
| PostgreSQL | Dữ liệu chính | Managed |
| Redis | LiveKit routing + chat pub/sub đa pod + cache | Managed |
| S3 | File recordings, truy cập qua presigned URL | Managed |

---

## 4. Backend — `meetly-api`

### 4.1 Stack & cấu trúc

Maven, Spring Boot 3.3, Java 21, Spring Security, Spring Data JPA + Flyway, WebSocket/STOMP, springdoc-openapi, SDK `io.livekit:livekit-server`.

Package-by-feature:

```
com.meetly/
├── auth/        # đăng ký, đăng nhập, JWT access + refresh rotation, guest token
├── user/        # hồ sơ người dùng
├── meeting/     # CRUD meeting, mã phòng, join flow, roles, in-room controls
├── chat/        # STOMP endpoint, persistence, moderation, raise-hand
├── recording/   # Egress start/stop, metadata, presigned playback URL
├── livekit/     # wrapper: token generation, RoomService client, webhook receiver
└── common/      # security config, RFC 7807 error handling, base entity, correlation-id
```

### 4.2 Schema PostgreSQL (Flyway migrations)

| Bảng | Cột chính | Ghi chú |
|---|---|---|
| `users` | id uuid, email unique, password_hash (BCrypt), full_name, role, created_at | role app: `USER`/`ADMIN` |
| `meetings` | id uuid, **code** unique (dạng `abc-defg-hij`), title, description, host_id → users, scheduled_start_at, scheduled_end_at, status, room_type, allow_recording, created_at | status: `SCHEDULED→LIVE→ENDED` / `CANCELLED`; room_type: `MEETING`/`WEBINAR` |
| `meeting_members` | id, meeting_id →, user_id → nullable, invited_email nullable, **role**, invited_by, created_at | role phòng: `HOST`/`SPEAKER`/`ATTENDEE`. Unique (meeting_id, user_id) |
| `participant_sessions` | id, meeting_id →, identity, display_name, joined_at, left_at | Điểm danh, ghi từ webhook `participant_joined/left` |
| `chat_messages` | id, meeting_id →, sender_identity, sender_display_name, content, type, deleted_at, deleted_by, created_at | type: `TEXT`/`SYSTEM`/`RAISE_HAND`. Soft-delete. Index (meeting_id, created_at) |
| `recordings` | id, meeting_id →, egress_id unique, status, s3_key, duration_seconds, size_bytes, started_by, started_at, ended_at | status: `STARTING`/`ACTIVE`/`COMPLETED`/`FAILED` |
| `refresh_tokens` | id, user_id →, token_hash, expires_at, revoked_at | Rotation: mỗi lần refresh cấp token mới, thu hồi cũ |

### 4.3 Luồng join phòng & phân quyền (luồng lõi)

```
FE ── POST /api/v1/meetings/{code}/join ──► BE:
  1. Xác thực: user đăng nhập, HOẶC guest (body có displayName)
  2. Validate: meeting tồn tại, status hợp lệ, trong khung giờ
     (cho join sớm 15 phút trước scheduled_start_at)
  3. Xác định role:
     - User có trong meeting_members → role đã gán
     - User lạ/guest: room_type=WEBINAR → ATTENDEE; MEETING → từ chối 403
  4. Sinh LiveKit token (TTL = đến scheduled_end_at + 2h):
     - HOST/SPEAKER:  canPublish=true,  canSubscribe=true, canPublishData=false
     - ATTENDEE:      canPublish=false, canSubscribe=true, canPublishData=false
     - HOST thêm: roomAdmin=true
     - identity = userId (hoặc "guest:{uuid}"), name = displayName
  5. Response: { livekitUrl, livekitToken, role,
                 chatToken? }   ← chỉ guest nhận chatToken (JWT scoped
                                   meeting đó, dùng cho STOMP + REST chat);
                                   member dùng access token sẵn có
```

**In-room controls** (host gọi REST → BE gọi LiveKit RoomService API):

- Mute participant: `mutePublishedTrack`
- **Promote ATTENDEE → SPEAKER**: `updateParticipant` cấp lại grants `canPublish=true` (runtime, không cần rejoin) + cập nhật `meeting_members`
- Demote, Kick (`removeParticipant`), End meeting (`deleteRoom`)

### 4.4 Chat — STOMP over WebSocket

- Endpoint `/ws`; xác thực JWT (access token hoặc guest chatToken) tại bước CONNECT qua ChannelInterceptor. **Bước SUBSCRIBE cũng phải kiểm tra quyền**: guest chỉ subscribe được topic phòng trong token; user phải là host/member hoặc phòng là WEBINAR — chặn nghe lén chat phòng khác.
- Client SEND `/app/meetings/{id}/chat` → server validate quyền (cùng luật với SUBSCRIBE, tập trung một chỗ) → lưu Postgres → publish Redis channel `chat:{meetingId}` → mọi pod API rebroadcast `/topic/meetings/{id}/chat` cho subscriber của mình.
- Giơ tay = message type `RAISE_HAND`; hạ tay/host acknowledge = `SYSTEM`.
- Moderation: host `DELETE /messages/{id}` → soft-delete + broadcast event xóa để FE gỡ tin.
- Lịch sử: `GET /api/v1/meetings/{id}/messages?before={cursor}&limit=50`; khi STOMP reconnect, FE fetch `after={lastMessageId}` để bù tin lỡ.

### 4.5 Webhook LiveKit — `POST /api/v1/livekit/webhook`

Verify chữ ký bằng API secret. Xử lý **idempotent** (LiveKit retry) — dedupe theo event id (Redis SETNX, TTL 24h).

| Event | Hành động |
|---|---|
| `room_started` | meeting status → `LIVE` |
| `room_finished` | status → `ENDED`; đóng session còn treo |
| `participant_joined` / `participant_left` | Ghi/đóng `participant_sessions` |
| `egress_ended` | recording → `COMPLETED` (kèm s3_key, duration) hoặc `FAILED` |

### 4.6 Recording

- Host bấm Record → `POST /meetings/{id}/recordings/start` → BE gọi Egress API `RoomCompositeEgress` (layout grid/speaker) → output MP4 → S3 → lưu `egress_id`, status `STARTING`.
- Stop thủ công hoặc tự dừng khi `room_finished`.
- Xem lại: `GET /recordings/{id}/playback-url` → presigned URL S3 (TTL 1h), chỉ cấp cho member của meeting.

### 4.7 API surface (REST, prefix `/api/v1`)

```
Auth:       POST /auth/register | /auth/login | /auth/refresh | /auth/logout
Users:      GET /users/me               (PATCH hồ sơ: sau MVP)
Meetings:   POST /meetings              GET /meetings  (filter upcoming|past: sau MVP)
            GET /meetings/{code}        PATCH|DELETE /meetings/{id}
            POST /meetings/{id}/members            DELETE /meetings/{id}/members/{memberId}
            POST /meetings/{code}/join             POST /meetings/{id}/end
Controls:   POST /meetings/{id}/participants/{identity}/mute|promote|demote|kick
Chat:       GET /meetings/{id}/messages            DELETE /meetings/{id}/messages/{msgId}
Recording:  POST /meetings/{id}/recordings/start|stop
            GET /meetings/{id}/recordings          GET /recordings/{id}/playback-url
Webhook:    POST /livekit/webhook
Ops:        GET /actuator/health|prometheus
```

### 4.8 Error handling

- RFC 7807 Problem Details (hỗ trợ sẵn Spring Boot 3) + `@RestControllerAdvice` toàn cục.
- Mã lỗi enum ổn định (`MEETING_NOT_FOUND`, `MEETING_NOT_STARTED`, `NOT_A_MEMBER`, `RECORDING_ALREADY_ACTIVE`…) để FE map sang thông báo tiếng Việt.
- Bean Validation cho request; 401/403 phân biệt rõ chưa đăng nhập vs không đủ quyền.

---

## 5. Frontend — `meetly-web`

### 5.1 Stack

| Thứ | Chọn |
|---|---|
| Build | Vite + React 18 + TypeScript |
| Media UI | `@livekit/components-react` v2 + `livekit-client` |
| Server state | TanStack Query |
| UI state | Zustand (authStore, roomUiStore) |
| Chat | `@stomp/stompjs` |
| Styling | Tailwind CSS |
| API types | Types TS viết tay đồng bộ với DTO của BE (MVP); sinh tự động từ OpenAPI là cải tiến sau |

### 5.2 Routes

```
/login /register           Auth
/                          Dashboard: meeting sắp tới, "Họp ngay", "Đặt lịch"
/meetings/new /:id/edit    Form đặt lịch + mời thành viên + gán vai trò
/m/:code                   Pre-join lobby: xin quyền thiết bị, preview cam,
                           chọn mic/cam, guest nhập tên → POST /join → room
/m/:code/room              Phòng họp
/recordings                Danh sách bản ghi + player (presigned URL)
```

### 5.3 Cấu trúc thư mục

```
frontend/src/
├── api/            # axios instance, interceptor auto-refresh 401, generated types
├── features/
│   ├── auth/
│   ├── meetings/   # dashboard, schedule form, members
│   ├── room/       # ★ lõi: VideoLayout, ControlBar, ParticipantList, ChatPanel
│   └── recordings/
├── components/     # Button, Modal, Toast...
├── stores/
└── lib/
```

### 5.4 Room UI

```
<LiveKitRoom serverUrl token>          ← SDK lo connect/reconnect/track
├── <VideoLayout>    GridLayout ⟷ FocusLayout (screen share hoặc active speaker)
├── <ControlBar>     mic/cam, share screen, raise hand, leave
│                    host: + Record, End meeting
├── <ParticipantList> host actions từng người: Mute / Promote / Kick → REST
└── <ChatPanel>      tự code: STOMP + lịch sử REST, host xóa tin, tab Q&A (raise hand)
</LiveKitRoom>
```

- Attendee (webinar): UI ẩn nút publish; bảo vệ thật nằm ở token không có `canPublish` (D3).
- Được promote: client nhận `ParticipantPermissionsChanged` từ LiveKit → hiện control phát biểu.

### 5.5 Chống chịu lỗi UI

- Banner "Đang kết nối lại…" theo connection state của LiveKit; indicator chất lượng mạng mỗi participant.
- STOMP reconnect backoff; khi nối lại fetch bù tin nhắn `after=lastMessageId`.
- Lỗi API RFC 7807 → map mã lỗi sang toast tiếng Việt.

---

## 6. Ops

### 6.1 Monorepo layout

```
meetly/
├── backend/
├── frontend/
├── ops/
│   ├── docker/     # Dockerfile.api (multi-stage Maven→JRE21 slim, non-root)
│   │               # Dockerfile.web (Vite build→nginx, runtime env inject)
│   ├── compose/    # docker-compose.dev.yml: postgres, redis, livekit(dev),
│   │               # minio, mailhog → 1 lệnh có full stack local
│   └── helm/meetly/  # umbrella chart: api, web; values-staging/prod.yaml
├── docs/
└── .github/workflows/
```

Dev local: compose cho hạ tầng, BE/FE chạy native hot-reload. Mục tiêu: dev mới chạy được phòng họp 2 người trong ngày đầu.

### 6.2 K8s production

| Workload | Cách chạy | Lưu ý |
|---|---|---|
| `meetly-api` | Deployment, HPA 2→10 | Stateless (D7 + Redis pub/sub) |
| `meetly-web` | nginx static, 2 replicas | — |
| LiveKit SFU | Helm chart chính chủ, `hostNetwork: true`, **node pool riêng, public IP** | UDP 50000–60000 + TCP 7881 thẳng vào node; multi-node qua Redis; thêm node = thêm capacity |
| Egress | Deployment riêng, node pool riêng | ~2–4 core/bản ghi đang encode |
| coturn | Node/VM riêng public IP | `turns:443` (TLS) cho firewall doanh nghiệp |
| Ingress | nginx-ingress + cert-manager | Let's Encrypt; WebRTC bắt buộc HTTPS/WSS |
| Postgres/Redis | Managed (D6) | In-cluster chỉ staging |
| Recordings | S3 + lifecycle policy | Egress ghi trực tiếp |

### 6.3 CI/CD — GitHub Actions

```
PR:         path filter → BE: mvn verify (Testcontainers Postgres+Redis)
                        → FE: eslint + vitest + tsc + build
merge main: build images (tag = git sha) → push registry → Trivy scan
            → helm upgrade STAGING → Playwright smoke e2e trên staging
            → [manual approval] → helm upgrade PRODUCTION
Rollback:   helm rollback; Flyway forward-only, backward-compatible
            trong cùng release (thêm cột/bảng, không rename/drop)
Secrets:    External Secrets Operator ← cloud secret manager; repo không chứa secret
```

### 6.4 Observability

- **Metrics**: Prometheus + Grafana. Micrometer/Actuator (API latency, WS connections, JVM) + LiveKit metrics (rooms, participants, packet loss, bandwidth/node) + Egress.
- **Logs**: logback JSON + Loki; correlation-id từ Ingress xuyên suốt.
- **Alerts**: API 5xx rate, LiveKit node gần capacity, Egress fail, cert expiry, pod restart loop, DB connections.
- **Load test**: `livekit-cli load-test` giả lập 100+ subscriber/phòng trước go-live → số liệu thật để chọn cỡ node và set HPA.

### 6.5 Security (ops-level)

Non-root containers, NetworkPolicy giữa các namespace, Trivy scan trong CI, rate limit tại Ingress, LiveKit API key/secret rotation, webhook signature verification (4.5), presigned URL TTL ngắn (4.6).

---

## 7. Chiến lược testing (tổng hợp)

| Tầng | Công cụ | Trọng tâm |
|---|---|---|
| BE unit | JUnit 5 + Mockito | Logic sinh **token grants theo role** (bảng role × quyền — điểm security nhạy nhất), join validation, webhook idempotency |
| BE integration | Testcontainers (Postgres, Redis) | Repository, Flyway, chat pub/sub, refresh rotation |
| FE unit | Vitest + RTL | ChatPanel, permission-driven UI, hooks |
| E2E | Playwright | Login → tạo meeting → 2 browser contexts join → thấy video tile của nhau (`--use-fake-device-for-media-stream`); smoke trên staging sau mỗi deploy |
| Load | livekit-cli load-test | Capacity node SFU trước go-live |

---

## 8. Lộ trình (mỗi phase = 1 implementation plan riêng)

| Phase | Nội dung | Definition of done |
|---|---|---|
| **1. Skeleton** | Monorepo, compose dev, auth JWT, meeting CRUD, join flow, room cơ bản | 2 người join cùng phòng qua UI, thấy video nhau, chạy từ `docker compose up` |
| **2. Roles & realtime** | Grants HOST/SPEAKER/ATTENDEE, guest join, promote/mute/kick, chat STOMP + moderation, raise hand | Webinar demo: attendee không publish được, host promote → phát biểu được; chat đồng bộ 2 pod API |
| **3. Recording** | Egress → S3, webhook trạng thái, trang xem lại | Host record → file xem lại được qua presigned URL |
| **4. Production-ready** | Helm, staging, CI/CD đầy đủ, monitoring, TURN, load test | Deploy staging tự động, dashboard Grafana, load test 100+ pass, go-live checklist |
| **5. Post-MVP** | HLS 500+, waiting room, breakout rooms… | (thiết kế riêng sau) |

---

## 9. Rủi ro & giảm thiểu

| Rủi ro | Giảm thiểu |
|---|---|
| User sau firewall chặn UDP không vào được | coturn `turns:443`; LiveKit TCP fallback 7881 |
| Egress quá tải khi nhiều phòng record cùng lúc | Node pool riêng + giới hạn concurrent recordings + alert |
| LiveKit nâng version breaking | Pin version trong Helm values; test staging trước |
| Chi phí băng thông SFU cao (100+ subscriber) | Simulcast + adaptive stream (dynacast) bật mặc định; load test đo số thật trước khi cam kết SLA |
| Token bị lộ → vào phòng trái phép | TTL ngắn theo meeting, identity gắn userId, grants tối thiểu theo role |
