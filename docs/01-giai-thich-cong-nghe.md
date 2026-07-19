# Meetly — Giải thích toàn bộ công nghệ trong dự án

> Tài liệu này viết cho người đã biết **Spring Boot CRUD** và **React cơ bản**, nhưng chưa
> làm việc với video call, hạ tầng, hay triển khai. Mọi thuật ngữ lạ đều được giải thích
> ngay tại chỗ dùng nó lần đầu.
>
> Đọc xong tài liệu này thì chuyển sang [02-huong-dan-chay-va-test.md](02-huong-dan-chay-va-test.md)
> để tự chạy và nghịch thử.

---

## Mục lục

1. [Bức tranh tổng thể — hệ thống gồm những mảnh nào](#1-bức-tranh-tổng-thể)
2. [Vì sao gọi video lại khó (phần quan trọng nhất)](#2-vì-sao-gọi-video-lại-khó)
3. [Tầng media: WebRTC, LiveKit, TURN](#3-tầng-media)
4. [Tầng backend: Spring Boot và bạn bè](#4-tầng-backend)
5. [Tầng realtime: WebSocket, STOMP, Redis](#5-tầng-realtime-chat)
6. [Tầng frontend: React và bạn bè](#6-tầng-frontend)
7. [Tầng lưu trữ: PostgreSQL, S3/MinIO](#7-tầng-lưu-trữ)
8. [Tầng kiểm thử](#8-tầng-kiểm-thử)
9. [Tầng vận hành: Docker, Kubernetes, CI/CD](#9-tầng-vận-hành)
10. [Ba luồng quan trọng nhất, giải thích từng bước](#10-ba-luồng-quan-trọng-nhất)
11. [Bảng tra cứu thuật ngữ](#11-bảng-tra-cứu-thuật-ngữ)

---

## 1. Bức tranh tổng thể

Meetly là ứng dụng họp video kiểu Zoom. Nó gồm **5 phần chạy độc lập**:

```
   TRÌNH DUYỆT (React)
        │        ╲
        │ (1)     ╲ (2) video/tiếng đi thẳng, KHÔNG qua backend
        │          ╲
        ▼           ▼
   ┌─────────┐   ┌──────────────┐
   │ Backend │   │ LiveKit SFU  │──► Egress ──► MinIO/S3 (file ghi hình)
   │ (Spring)│   │ (máy chủ     │
   └────┬────┘   │  video)      │
        │        └──────────────┘
        ▼
   ┌──────────┐  ┌───────┐
   │PostgreSQL│  │ Redis │
   └──────────┘  └───────┘
```

| Phần | Là cái gì | Ai viết |
|---|---|---|
| **Frontend** (`frontend/`) | Giao diện web bằng React, chạy trong trình duyệt | Chúng ta |
| **Backend** (`backend/`) | API Spring Boot: đăng nhập, tạo phòng, phân quyền, chat | Chúng ta |
| **LiveKit** | Máy chủ chuyên xử lý video/tiếng. Phần mềm mã nguồn mở, ta chỉ cấu hình | Bên thứ ba |
| **PostgreSQL** | Cơ sở dữ liệu | Bên thứ ba |
| **Redis** | Bộ nhớ đệm + kênh truyền tin giữa các bản backend | Bên thứ ba |

**Điểm mấu chốt cần nhớ:** mũi tên (2) trong sơ đồ — **video và tiếng KHÔNG bao giờ đi qua
backend Spring Boot**. Backend chỉ làm "người gác cổng": kiểm tra bạn là ai, có quyền gì,
rồi cấp cho bạn một tấm vé để đi thẳng vào LiveKit. Lý do ở mục 2.

---

## 2. Vì sao gọi video lại khó

Đây là phần đáng đọc kỹ nhất, vì mọi lựa chọn công nghệ phía sau đều bắt nguồn từ đây.

### 2.1 Video khác dữ liệu CRUD ở chỗ nào

Một API CRUD bình thường: client gửi request, server trả response, xong. Mỗi request vài KB,
kéo dài vài chục mili-giây.

Video call thì khác hoàn toàn:

- **Liên tục**: 30 khung hình mỗi giây, không ngừng suốt buổi họp.
- **Nặng**: video 720p tốn khoảng **1,5–2 Mbps** cho mỗi người. 10 người là 20 Mbps.
- **Cực kỳ nhạy với độ trễ**: chậm quá 200ms là người ta thấy "lag", nói chồng lên nhau.
- **Thà mất còn hơn chậm**: mất một khung hình thì mắt không nhận ra; nhưng khung hình đến
  trễ 2 giây thì vô dụng. Ngược hoàn toàn với dữ liệu thường (thà chậm còn hơn sai).

Chính vì đặc điểm cuối, video **không dùng TCP** (giao thức mà HTTP dựa vào). TCP đảm bảo
"không mất gói nào", nên khi mất gói nó sẽ dừng lại chờ gửi lại — gây khựng. Video dùng
**UDP**: gửi đi không cần xác nhận, mất thì thôi, miễn là đến nhanh.

> **TCP** (Transmission Control Protocol): giao thức đảm bảo dữ liệu đến đủ và đúng thứ tự.
> Dùng cho web, API, tải file.
> **UDP** (User Datagram Protocol): giao thức gửi nhanh, không đảm bảo. Dùng cho video, game.

### 2.2 Ba kiến trúc gọi nhóm, và vì sao chọn cái thứ hai

**Cách 1 — P2P (Peer-to-Peer, ngang hàng):** mỗi người gửi video thẳng cho từng người còn lại.

```
3 người:  A ⟷ B,  A ⟷ C,  B ⟷ C     → mỗi người gửi 2 luồng
10 người: mỗi người gửi 9 luồng      → 9 × 2 Mbps = 18 Mbps upload
```

Máy tính cá nhân thường chỉ có 5–10 Mbps upload. Nên P2P **chết ở khoảng 4–5 người**. Chỉ hợp
gọi 1-1.

**Cách 2 — SFU (Selective Forwarding Unit, bộ chuyển tiếp chọn lọc):** mỗi người gửi video
**một lần duy nhất** lên máy chủ; máy chủ nhân bản và chuyển tiếp cho những người khác.

```
10 người: mỗi người upload 1 luồng (2 Mbps) — nhẹ nhàng
          máy chủ gánh phần nặng: nhận 10, gửi ra 90 luồng
```

Máy chủ đặt ở trung tâm dữ liệu, băng thông lớn, nên gánh được. **Đây là cách Meetly dùng.**
Từ "chọn lọc" nghĩa là máy chủ **chỉ chuyển tiếp**, không giải mã/mã hoá lại video — nên tốn
rất ít CPU.

**Cách 3 — MCU (Multipoint Control Unit):** máy chủ giải mã tất cả video, ghép thành **một**
khung hình duy nhất, mã hoá lại rồi gửi đi. Client chỉ nhận 1 luồng — nhẹ cho client, nhưng
máy chủ tốn CPU kinh khủng (mã hoá video là việc rất nặng). Đắt, và không cho phép client tự
sắp xếp bố cục.

> Meetly chọn **SFU** vì mục tiêu là phòng 100+ người. Đó là lý do có LiveKit trong dự án.

### 2.3 Vì sao không tự viết SFU

Viết một SFU đàng hoàng cần 6–12 tháng: xử lý mất gói, điều tiết băng thông, vượt tường lửa,
đồng bộ tiếng/hình... LiveKit là phần mềm mã nguồn mở đã làm sẵn tất cả, và cho tự cài trên
máy chủ của mình (không phải trả tiền theo phút như dịch vụ đám mây).

---

## 3. Tầng media

### 3.1 WebRTC

**WebRTC** (Web Real-Time Communication) là bộ công nghệ **có sẵn trong mọi trình duyệt hiện
đại**, cho phép truyền video/tiếng/dữ liệu trực tiếp với độ trễ thấp. Không cần cài plugin.

Trình duyệt cung cấp sẵn các hàm JavaScript như `getUserMedia()` (xin quyền truy cập
camera/micro) và `RTCPeerConnection` (tạo kết nối truyền media). Trong dự án này ta **không
gọi trực tiếp** những hàm đó — thư viện của LiveKit bọc lại hết.

Vài khái niệm WebRTC xuất hiện trong code:

| Thuật ngữ | Nghĩa |
|---|---|
| **Track** (luồng) | Một dòng dữ liệu media. Bật camera = 1 video track; bật mic = 1 audio track; chia sẻ màn hình = thêm 1 video track nữa |
| **Publish** (phát) | Hành động gửi track của mình lên máy chủ |
| **Subscribe** (nhận) | Hành động nhận track của người khác về |
| **Peer** | Một đầu của kết nối (trình duyệt của bạn, hoặc máy chủ) |

**Điểm mấu chốt của toàn bộ hệ thống phân quyền:** người "chỉ xem" (khán giả) là người
**không có quyền publish**. Đây không phải chuyện ẩn nút trên giao diện — mà là máy chủ
LiveKit từ chối nhận track từ họ.

### 3.2 NAT, ICE, STUN, TURN — vì sao có `coturn` trong dự án

Đây là phần khó hiểu nhất với người mới, nên em giải thích bằng ví dụ đời thường.

**Vấn đề:** máy tính của bạn ở nhà **không có địa chỉ công khai trên Internet**. Cả nhà bạn
(điện thoại, laptop, TV) dùng chung một địa chỉ IP của modem. Bên trong, router cấp cho mỗi
máy một địa chỉ riêng kiểu `192.168.1.5`. Cơ chế này gọi là **NAT** (Network Address
Translation — dịch địa chỉ mạng).

> Ví như một toà chung cư chỉ có **một địa chỉ đường phố**. Bưu tá muốn gửi thư cho bạn thì
> chỉ biết địa chỉ toà nhà, không biết bạn ở căn nào. Thư bạn **gửi đi** thì được, nhưng thư
> **gửi đến** mà không hẹn trước thì bảo vệ không biết đưa cho ai.

Vậy làm sao máy chủ gửi video ngược về cho bạn? Ba công cụ:

**STUN** (Session Traversal Utilities for NAT): một máy chủ nhỏ ngoài Internet, bạn hỏi nó
"nhìn từ ngoài, địa chỉ của tôi là gì?". Nó trả lời "bạn là 113.161.x.x cổng 54321". Giờ bạn
biết địa chỉ công khai của mình để đưa cho đối phương. **Rẻ, nhanh, giải quyết ~80% trường hợp.**

**TURN** (Traversal Using Relays around NAT): khi tường lửa công ty chặn sạch UDP, STUN vô
dụng. Lúc này cần một máy chủ **trung chuyển** đứng giữa: bạn gửi cho nó, nó chuyển tiếp.
Tốn băng thông máy chủ nên chỉ dùng khi bắt buộc. **`coturn`** trong dự án chính là phần mềm
TURN này.

Mẹo quan trọng trong dự án: coturn được cấu hình chạy ở **cổng 443 với TLS** (`turns:443`).
Vì 443 là cổng HTTPS, hầu như **không tường lửa nào dám chặn** — nhìn từ ngoài, lưu lượng
này giống hệt việc duyệt web bình thường.

**ICE** (Interactive Connectivity Establishment): quy trình thử tất cả đường đi khả dĩ (trực
tiếp, qua STUN, qua TURN) rồi chọn đường tốt nhất. Mỗi đường ứng viên gọi là **ICE candidate**.

> Trong tài liệu vận hành có ghi lỗi *"Start signal not received"* của Egress — nguyên nhân
> đúng là ICE: LiveKit ở môi trường dev thông báo địa chỉ `127.0.0.1`, mà `127.0.0.1` nhìn từ
> bên trong một container khác lại là **chính container đó**, nên không kết nối được.

### 3.3 LiveKit

**LiveKit** là SFU mã nguồn mở mà dự án dùng. Các khái niệm:

| Khái niệm | Nghĩa |
|---|---|
| **Room** (phòng) | Không gian ảo chứa người tham gia. Trong Meetly, tên phòng chính là mã phòng kiểu `abc-defg-hij` |
| **Participant** | Một người trong phòng |
| **Identity** | Định danh duy nhất của participant. Meetly dùng `userId`, hoặc `guest:<uuid>` cho khách |
| **Access Token** | Tấm vé vào phòng, dạng JWT (xem mục 4.3), do **backend của ta** cấp |
| **Grants** (quyền) | Danh sách quyền ghi bên trong tấm vé: được vào phòng không, được publish không, được làm quản trị phòng không |

**Cách phân quyền hoạt động — trái tim của hệ thống:**

Backend sinh tấm vé, trong đó ghi rõ quyền hạn:

| Vai trò | `canPublish` | `canSubscribe` | `roomAdmin` | Nghĩa thực tế |
|---|---|---|---|---|
| HOST (chủ phòng) | ✅ | ✅ | ✅ | Nói được, xem được, đuổi/tắt mic người khác được |
| SPEAKER (diễn giả) | ✅ | ✅ | ❌ | Nói được, xem được |
| ATTENDEE (khán giả) | ❌ | ✅ | ❌ | **Chỉ xem**, không phát được |

Tấm vé được **ký bằng chữ ký số** bởi khoá bí mật mà chỉ backend và LiveKit biết. Người dùng
không thể tự sửa "canPublish: false" thành "true" — sửa là chữ ký hỏng, LiveKit từ chối ngay.

> Đây là lý do file `LiveKitTokenService.java` là chỗ nhạy cảm nhất về bảo mật trong dự án,
> và vì sao nó có test riêng kiểm tra từng quyền theo từng vai trò.

**Promote (thăng quyền) chạy thế nào:** khi host bấm "cho phát biểu", backend gọi API của
LiveKit (`updateParticipant`) để **cấp lại quyền ngay lập tức**, người kia không phải thoát ra
vào lại. Trình duyệt của họ nhận sự kiện `ParticipantPermissionsChanged` và hiện nút mic/camera.

### 3.4 Simulcast và Dynacast — tiết kiệm băng thông

**Simulcast**: trình duyệt gửi **cùng lúc 3 phiên bản** của video mình: nét (720p), vừa (360p),
mờ (180p). Máy chủ sẽ chọn gửi phiên bản phù hợp cho từng người nhận: ai mạng khoẻ và đang
xem bạn ở ô lớn thì nhận bản nét; ai mạng yếu hoặc chỉ thấy bạn ở ô nhỏ thì nhận bản mờ.

**Dynacast**: nếu **không ai** đang xem bản nét, máy chủ báo trình duyệt bạn **ngừng gửi** bản
đó, tiết kiệm băng thông.

Cả hai đều được LiveKit bật sẵn — ta không phải viết code.

### 3.5 Egress — bộ ghi hình

**Egress** (nghĩa đen: "lối ra") là thành phần riêng của LiveKit chuyên **ghi hình**. Cách nó
làm việc nghe hơi bất ngờ nhưng rất thông minh:

> Egress **mở một trình duyệt Chrome ẩn** bên trong máy chủ, cho trình duyệt đó "vào phòng
> họp" như một người tham gia vô hình, rồi **quay lại màn hình** của trình duyệt đó thành file
> MP4 và tải lên kho lưu trữ.

Vì phải chạy Chrome + mã hoá video nên nó **rất tốn CPU** (khoảng 2–4 lõi cho mỗi bản ghi đang
chạy). Đó là lý do trong tài liệu triển khai, Egress được đặt trên **nhóm máy riêng**.

Kiểu ghi ta dùng là **RoomComposite** — ghi toàn bộ phòng dưới dạng lưới các ô video, ra một
file duy nhất.

---

## 4. Tầng backend

Phần này anh đã quen nhất, nên em chỉ giải thích những thứ vượt ra ngoài CRUD thông thường.

### 4.1 Spring Boot, Maven

**Maven** là công cụ quản lý thư viện và build cho Java. File `backend/pom.xml` khai báo dự án
cần những thư viện nào; Maven tự tải về. Lệnh hay dùng:

- `./mvnw test` — chạy toàn bộ test
- `./mvnw spring-boot:run` — chạy ứng dụng
- `./mvnw package` — đóng gói thành file `.jar` chạy được

> `mvnw` là **Maven Wrapper**: một script nhỏ nằm sẵn trong repo, tự tải đúng phiên bản Maven
> mà dự án cần. Nhờ nó, người mới không phải cài Maven thủ công và cả nhóm dùng chung một
> phiên bản.

Cấu trúc code chia **theo tính năng** (package-by-feature), không phải theo tầng:

```
com.meetly/
├── auth/       — đăng ký, đăng nhập, token
├── user/       — hồ sơ người dùng
├── meeting/    — phòng họp, thành viên, vào phòng, điều khiển trong phòng
├── chat/       — tin nhắn
├── recording/  — ghi hình
├── livekit/    — giao tiếp với LiveKit
└── common/     — bảo mật, xử lý lỗi, tiện ích dùng chung
```

Cách này dễ tìm code hơn kiểu chia `controllers/`, `services/`, `repositories/`: muốn sửa
tính năng chat thì mọi thứ liên quan nằm gọn trong `chat/`.

### 4.2 JPA, Hibernate, Flyway

**JPA/Hibernate** ánh xạ class Java ↔ bảng trong database (anh đã quen).

**Flyway** quản lý **thay đổi cấu trúc database theo phiên bản**. Thay vì để Hibernate tự tạo
bảng (nguy hiểm khi lên production vì có thể xoá dữ liệu), ta viết các file SQL đánh số:

```
db/migration/
├── V1__init.sql                    — tạo users, refresh_tokens, meetings
├── V2__members_sessions_chat.sql   — thêm meeting_members, participant_sessions, chat_messages
└── V3__recordings.sql              — thêm recordings
```

Khi ứng dụng khởi động, Flyway kiểm tra bảng `flyway_schema_history` xem đã chạy tới file nào,
rồi chạy tiếp những file mới. **Không bao giờ sửa file đã chạy** — muốn đổi gì thì thêm file
`V4__...` mới. Nguyên tắc này gọi là *forward-only* (chỉ tiến, không lùi).

Hệ thống hiện có **8 bảng**: `users`, `refresh_tokens`, `meetings`, `meeting_members`,
`participant_sessions`, `chat_messages`, `recordings`, và `flyway_schema_history` (của Flyway).

### 4.3 JWT — trái tim của việc đăng nhập

**JWT** (JSON Web Token) là một chuỗi ký tự dài chứa thông tin đã được **ký số**. Nhìn như thế
này (3 phần ngăn bởi dấu chấm):

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMiLCJlbWFpbCI6ImFAYi5jIn0.k3nB7xQ...
   ↑ header            ↑ payload (dữ liệu)              ↑ chữ ký
```

- **Payload** chứa dữ liệu: bạn là ai, token hết hạn lúc nào. Phần này **ai cũng đọc được**
  (chỉ mã hoá Base64, không phải mã hoá bí mật) → **tuyệt đối không để mật khẩu vào đây**.
- **Chữ ký** được tạo từ payload + một chuỗi bí mật chỉ server biết. Sửa payload một ký tự là
  chữ ký sai ngay → server phát hiện và từ chối.

**Vì sao dùng JWT thay vì session truyền thống?** Session cần server lưu trạng thái đăng nhập
trong bộ nhớ. Khi chạy nhiều bản server song song, người dùng đăng nhập ở bản A rồi request
tiếp rơi vào bản B thì B không biết họ là ai. JWT thì **tự chứa thông tin**, bản nào cũng
kiểm tra được bằng chữ ký, không cần hỏi ai. Nhờ đó **scale ngang thoải mái**.

**Vấn đề của JWT:** đã cấp thì không thu hồi được (server không lưu gì để mà xoá). Giải pháp
tiêu chuẩn là **cặp hai token**, và dự án dùng đúng cách này:

| | Access token | Refresh token |
|---|---|---|
| Sống bao lâu | **15 phút** | **14 ngày** |
| Dùng làm gì | Gắn vào mỗi request API | Chỉ để xin access token mới |
| Lưu ở đâu | Bộ nhớ trình duyệt (JavaScript đọc được) | **Cookie httpOnly** |
| Thu hồi được không | Không (nhưng chỉ sống 15 phút) | **Có** (server lưu mã băm trong DB) |

> **Cookie httpOnly**: loại cookie mà **JavaScript không đọc được**, chỉ trình duyệt tự động
> gửi kèm request. Nếu website dính lỗ hổng XSS (kẻ xấu chèn được JavaScript), chúng vẫn
> không lấy được refresh token. Access token thì lấy được, nhưng chỉ dùng được 15 phút.

**Rotation (xoay vòng)** — mỗi lần dùng refresh token để xin token mới, token cũ **bị thu hồi
ngay** và bạn nhận một token hoàn toàn mới.

**Phát hiện tái sử dụng** — nếu ai đó dùng lại token **đã bị thu hồi**, đó là dấu hiệu token
bị đánh cắp (vì người dùng thật đã có token mới rồi). Hệ thống **thu hồi toàn bộ phiên** của
tài khoản đó, buộc đăng nhập lại. Đây là khuyến nghị chuẩn của OWASP (tổ chức về bảo mật ứng
dụng web) và có test riêng trong `RefreshTokenReuseIT.java`.

Trong DB, refresh token **không lưu nguyên văn** mà lưu **mã băm SHA-256** — kẻ nào đọc trộm
được database cũng không đăng nhập được.

### 4.4 BCrypt — băm mật khẩu

Mật khẩu không bao giờ lưu nguyên văn. **BCrypt** biến `"secret123"` thành chuỗi kiểu
`$2a$10$N9qo8uLO...`. Đặc điểm:

- **Một chiều**: từ chuỗi băm không suy ngược ra mật khẩu.
- **Có "muối" (salt) ngẫu nhiên**: hai người cùng mật khẩu `123456` vẫn ra hai chuỗi băm khác
  nhau → kẻ xấu không thể tra bảng tính sẵn.
- **Cố tình chậm**: mỗi lần băm mất ~100ms. Người dùng đăng nhập không cảm thấy gì, nhưng kẻ
  xấu muốn thử 1 tỷ mật khẩu thì mất hàng nghìn năm.

### 4.5 Spring Security và luồng lọc request

Mỗi request đi qua một chuỗi **filter** (bộ lọc) trước khi tới controller:

```
Request → CorrelationIdFilter → JwtAuthFilter → [kiểm tra quyền] → Controller
```

- **`CorrelationIdFilter`**: gắn cho mỗi request một mã định danh (`X-Request-Id`). Khi có sự
  cố, mã này giúp lọc ra **tất cả dòng log của đúng request đó** giữa hàng triệu dòng. Cực kỳ
  hữu ích khi chạy nhiều bản server.
- **`JwtAuthFilter`**: đọc header `Authorization: Bearer <token>`, xác thực chữ ký, gắn thông
  tin người dùng vào ngữ cảnh để controller dùng.

### 4.6 RFC 7807 — chuẩn trả lỗi

Thay vì mỗi API trả lỗi một kiểu, dự án theo chuẩn **RFC 7807 Problem Details**:

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "You have not been invited to this meeting",
  "instance": "/api/v1/meetings/abc-defg-hij/join",
  "code": "NOT_A_MEMBER"
}
```

Trường `code` là **mã lỗi ổn định** do dự án tự định nghĩa (`ErrorCode.java`). Frontend dựa vào
`code` để hiển thị thông báo phù hợp — chứ không dựa vào `detail` (câu chữ có thể đổi bất cứ lúc nào).

**Nguyên tắc quan trọng đã được sửa trong dự án:** lỗi do client gửi sai (JSON hỏng, tham số
sai kiểu) phải trả **4xx**, không được trả 5xx. Vì 5xx nghĩa là "server hỏng" — sẽ kích hoạt
báo động gọi người trực lúc nửa đêm, trong khi thực ra chỉ là ai đó gõ sai địa chỉ.

---

## 5. Tầng realtime (chat)

### 5.1 Vì sao chat không dùng REST

Với REST, muốn biết có tin mới thì client phải **hỏi liên tục** ("có tin chưa? có tin chưa?")
— gọi là *polling*, vừa tốn tài nguyên vừa chậm.

**WebSocket** giải quyết: mở **một kết nối duy nhất, giữ mở suốt**, hai chiều. Server có tin
mới thì **chủ động đẩy xuống** ngay lập tức.

> REST như gửi thư: mỗi lần liên lạc là một lá thư mới.
> WebSocket như gọi điện thoại: nhấc máy một lần, nói chuyện qua lại thoải mái đến khi cúp.

### 5.2 STOMP

WebSocket chỉ định nghĩa "đường ống", không quy định **nội dung gửi qua có định dạng gì**.
**STOMP** (Simple Text Oriented Messaging Protocol) là quy ước đặt lên trên, cho phép:

- **SUBSCRIBE** (đăng ký nghe) một "chủ đề": `/topic/meetings/{id}/chat`
- **SEND** (gửi) tới một địa chỉ: `/app/meetings/{id}/chat`

Nhờ đó nhiều loại tin nhắn dùng chung một kết nối mà không lẫn lộn.

### 5.3 Redis pub/sub — vì sao bắt buộc phải có

Đây là chỗ tinh tế, đáng hiểu kỹ.

Khi lên production, backend chạy **nhiều bản song song** (gọi là pod) để chịu tải. Giả sử:

- An kết nối WebSocket vào **pod 1**
- Bình kết nối WebSocket vào **pod 2**
- An gửi tin nhắn → pod 1 nhận

**Vấn đề:** pod 1 chỉ giữ kết nối tới An, nó **không biết gì về Bình**. Tin nhắn không tới được Bình.

**Giải pháp — Redis pub/sub** (xuất bản/đăng ký): Redis đóng vai "loa phát thanh chung".

```
An gửi tin → pod 1 → lưu vào PostgreSQL
                   → phát lên kênh Redis "chat:<mã phòng>"
                          ↓ Redis phát lại cho TẤT CẢ pod đang nghe
              pod 1 ─────┴───── pod 2
                 ↓                ↓
              gửi cho An      gửi cho Bình  ✅
```

Điều này **đã được kiểm chứng thật**: chạy hai bản backend ở cổng 8080 và 8081, người nghe nối
pod A vẫn nhận được tin gửi qua pod B.

Redis trong dự án làm **ba việc**: kênh chat như trên, chống trùng lặp webhook, và giúp nhiều
máy chủ LiveKit tìm thấy nhau.

### 5.4 Webhook — LiveKit báo tin ngược lại

**Webhook** là cơ chế ngược với API thông thường: thay vì ta gọi LiveKit, **LiveKit gọi ta**
khi có sự kiện. Backend có sẵn địa chỉ `/api/v1/livekit/webhook` để nhận:

| Sự kiện | Backend làm gì |
|---|---|
| `room_started` | Đánh dấu phòng đang diễn ra (LIVE) |
| `room_finished` | Đánh dấu đã kết thúc, đóng các phiên còn treo |
| `participant_joined` / `left` | Ghi điểm danh vào bảng `participant_sessions` |
| `egress_ended` | Cập nhật bản ghi thành hoàn tất (kèm dung lượng, thời lượng) |

Hai chi tiết quan trọng:

- **Xác thực chữ ký**: webhook được ký bằng khoá bí mật, tránh kẻ xấu giả mạo LiveKit để phá
  dữ liệu.
- **Idempotent** (chạy lại không đổi kết quả): LiveKit có thể gửi lại cùng một sự kiện nếu
  nghi ngờ ta chưa nhận. Ta lưu mã sự kiện vào Redis 24 giờ để bỏ qua bản trùng — nếu không,
  một người vào phòng có thể bị ghi điểm danh 2–3 lần.

---

## 6. Tầng frontend

### 6.1 Vite

**Vite** là công cụ build và máy chủ phát triển cho web. So với Webpack (thế hệ trước):
khởi động gần như tức thì, sửa code là trình duyệt cập nhật ngay (**HMR** — Hot Module
Replacement, thay module nóng, không mất trạng thái đang có trên trang).

Vite còn làm **proxy** khi phát triển: frontend chạy cổng 5173, backend cổng 8080. Cấu hình
proxy trong `vite.config.ts` khiến mọi request tới `/api` và `/ws` được chuyển tiếp sang 8080.
Nhờ vậy trình duyệt tưởng tất cả cùng một nơi → **tránh được lỗi CORS**.

> **CORS** (Cross-Origin Resource Sharing): quy tắc bảo mật của trình duyệt, chặn trang web ở
> địa chỉ A gọi API ở địa chỉ B trừ khi B cho phép rõ ràng.

### 6.2 TypeScript

**TypeScript** là JavaScript có thêm **kiểu dữ liệu**. Khai báo `const user: User` thì viết
sai tên thuộc tính là báo lỗi **ngay khi gõ**, không phải chờ chạy mới phát hiện. Với dự án
nhiều người, đây là mạng lưới an toàn rất đáng giá.

### 6.3 TanStack Query — quản lý dữ liệu từ server

Bình thường lấy dữ liệu từ API phải tự viết: trạng thái đang tải, trạng thái lỗi, lưu tạm,
tải lại khi cần... rất lặp lại.

**TanStack Query** làm hộ hết:

```ts
const { data: meetings, isLoading } = useMyMeetings();
```

Một dòng này đã bao gồm: gọi API, cho biết đang tải hay chưa, tự lưu tạm (cache), và **tự
động tải lại** khi dữ liệu cũ. Trong dự án còn dùng `refetchInterval` để hỏi lại mỗi 10 giây
xem bản ghi hình xong chưa.

### 6.4 Zustand — quản lý trạng thái giao diện

Trạng thái **không đến từ server** (ví dụ: đang đăng nhập là ai) cần chia sẻ giữa nhiều
component. **Zustand** là thư viện nhẹ làm việc đó, đơn giản hơn Redux nhiều:

```ts
const user = useAuthStore((s) => s.user);   // component nào cũng lấy được
```

Dự án có 2 kho: `authStore` (người dùng, access token) và `roomStore` (thông tin phòng đang vào).

> **Phân biệt:** dữ liệu từ server → TanStack Query. Trạng thái cục bộ → Zustand.

### 6.5 Tailwind CSS

Thay vì viết file CSS riêng, **Tailwind** cho gắn thẳng các lớp tiện ích lên thẻ HTML:

```tsx
<button className="bg-blue-600 text-white rounded-lg px-4 py-2">Join</button>
//                 nền xanh    chữ trắng  bo góc   đệm ngang/dọc
```

Nhìn hơi rối lúc đầu, nhưng đổi lại: không phải nghĩ tên class, không sợ CSS chỗ này phá giao
diện chỗ kia, và xoá component là CSS của nó biến mất theo.

### 6.6 Thư viện LiveKit cho React

`@livekit/components-react` cung cấp sẵn các component:

- `<LiveKitRoom>` — bọc ngoài, lo việc kết nối, tự kết nối lại khi rớt mạng
- `<PreJoin>` — màn hình chuẩn bị: xem trước camera, chọn thiết bị, nhập tên
- `<GridLayout>` / `<FocusLayout>` — bố cục lưới, hoặc bố cục tập trung một người (khi chia sẻ màn hình)
- `<ParticipantTile>` — ô video của một người
- `<TrackToggle>` — nút bật/tắt mic, camera

Ban đầu dự án dùng `<VideoConference>` (component gộp sẵn tất cả), sau thay bằng bố cục tự
ghép để chèn được danh sách người tham gia, khung chat, và các nút riêng của host.

---

## 7. Tầng lưu trữ

### 7.1 PostgreSQL

Cơ sở dữ liệu quan hệ, lưu toàn bộ dữ liệu nghiệp vụ. Trên production nên dùng **dịch vụ được
quản lý** (Amazon RDS, Google Cloud SQL) thay vì tự cài, để khỏi phải tự lo sao lưu, cập nhật
bảo mật, chuyển đổi khi máy hỏng.

### 7.2 S3 và MinIO

**S3** (Simple Storage Service) là dịch vụ lưu file của Amazon. Khác database ở chỗ nó chuyên
chứa **file lớn** (video, ảnh) — rẻ hơn nhiều và không giới hạn dung lượng.

**MinIO** là phần mềm mã nguồn mở **nói cùng "ngôn ngữ" với S3**, cài được trên máy mình. Nhờ
vậy khi phát triển ta dùng MinIO, lên production đổi sang S3 thật mà **không phải sửa code** —
chỉ đổi cấu hình địa chỉ.

**Presigned URL (đường dẫn ký sẵn)** — cách cho xem file mà không cần công khai:

> Bản ghi hình không thể để ai cũng tải được. Nhưng cũng không nên bắt video đi vòng qua
> backend (tốn băng thông và bộ nhớ server).
>
> Giải pháp: backend kiểm tra bạn có quyền không, rồi tạo một **đường dẫn đặc biệt có chữ ký
> và có hạn dùng 1 giờ**, trả về cho trình duyệt. Trình duyệt tải thẳng từ kho lưu trữ. Hết
> 1 giờ, đường dẫn đó vô hiệu.
>
> Giống như vé xem phim: bảo vệ kiểm tra một lần ở cửa, vé chỉ dùng được cho suất chiếu đó.

---

## 8. Tầng kiểm thử

Dự án hiện có **47 test backend**, **9 test frontend**, **3 kịch bản e2e**.

### 8.1 Các loại test

| Loại | Công cụ | Kiểm cái gì | Nhanh/chậm |
|---|---|---|---|
| **Unit** (đơn vị) | JUnit 5 (Java), Vitest (JS) | Một hàm/lớp riêng lẻ, không cần DB | Mili-giây |
| **Integration** (tích hợp) | Spring Boot Test + Testcontainers | Nhiều thành phần cùng nhau, có DB thật | Vài giây |
| **E2E** (đầu-cuối) | Playwright | Toàn hệ thống qua trình duyệt thật | Vài chục giây |

### 8.2 Testcontainers — điểm sáng đáng biết

Test tích hợp cần database thật. Ba cách làm, hai cách đầu đều tệ:

1. ❌ Dùng chung DB với người khác → test của người này phá dữ liệu người kia
2. ❌ Dùng DB giả trong bộ nhớ (H2) → cú pháp khác PostgreSQL, test xanh nhưng production vẫn hỏng
3. ✅ **Testcontainers**: tự động **khởi động một container PostgreSQL thật** khi test bắt đầu,
   xoá sạch khi test xong

Nhờ vậy test chạy trên PostgreSQL 16 y hệt production, mà máy ai chạy cũng được, không cần cài
gì thêm ngoài Docker.

> Đây là lý do **Docker phải đang chạy** thì `./mvnw test` mới thành công.

### 8.3 Playwright — test qua trình duyệt thật

Playwright điều khiển Chrome thật, làm đúng những gì người dùng làm: mở trang, gõ email, bấm
nút, kiểm tra thấy gì trên màn hình.

Mẹo hay trong dự án: video call cần camera thật, mà máy chủ CI không có camera. Playwright được
chạy với cờ `--use-fake-device-for-media-stream` — Chrome **giả lập một camera** phát hình mẫu.
Nhờ đó test video chạy được ở mọi nơi.

Ba kịch bản e2e:

1. `two-users-meet` — hai người được mời vào phòng kín, thấy video của nhau
2. `webinar-roles` — khách vào webinar chỉ xem được, host thăng quyền thì phát biểu được, chat hoạt động
3. `recording-dod` — host ghi hình 20 giây, file MP4 lên MinIO thật, xem lại được

---

## 9. Tầng vận hành

### 9.1 Docker

**Docker** đóng gói ứng dụng **cùng toàn bộ môi trường nó cần** (hệ điều hành thu gọn, thư
viện, cấu hình) thành một **image**. Chạy image lên thì thành **container**.

> Giải quyết câu kinh điển *"máy tôi chạy được mà?"*: image chạy ở máy anh, máy em, và trên
> production đều **y hệt nhau**.

- **Image**: bản đóng gói tĩnh (như file cài đặt)
- **Container**: một tiến trình đang chạy từ image (như chương trình đang mở)
- **Dockerfile**: công thức để tạo image

Dự án dùng **multi-stage build** (build nhiều tầng): tầng 1 dùng image Maven đầy đủ để biên
dịch; tầng 2 chỉ copy file `.jar` sang một image Java gọn nhẹ. Kết quả: image cuối **không
chứa mã nguồn, không chứa Maven** → nhỏ hơn và an toàn hơn.

**Container chạy non-root**: mặc định container chạy quyền root; nếu kẻ xấu thoát được ra
ngoài thì rất nguy hiểm. Ta tạo user thường và chạy bằng user đó. Ngoài ra còn khoá thêm:
hệ thống file **chỉ đọc**, bỏ toàn bộ đặc quyền hệ điều hành.

### 9.2 Docker Compose

Chạy 6 container thủ công thì mệt. **Compose** khai báo tất cả trong một file YAML rồi bật
bằng một lệnh. File `ops/compose/docker-compose.dev.yml` dựng: PostgreSQL, Redis, LiveKit,
MinIO, tiến trình tạo thùng chứa file, và Egress.

**Healthcheck** (kiểm tra sức khoẻ): Compose định kỳ chạy một lệnh để biết dịch vụ đã sẵn
sàng thật chưa. Quan trọng vì PostgreSQL mất vài giây mới nhận kết nối — "container đang chạy"
không có nghĩa "đã dùng được".

### 9.3 Kubernetes (K8s)

Compose hợp cho một máy. Khi lên production cần nhiều máy, tự khởi động lại khi hỏng, tự tăng
giảm số lượng theo tải — đó là việc của **Kubernetes**.

| Khái niệm | Nghĩa dễ hiểu |
|---|---|
| **Pod** | Đơn vị nhỏ nhất — thường là 1 container đang chạy |
| **Deployment** | Bản khai báo "tôi muốn luôn có N pod loại này". Pod chết thì K8s tự tạo lại |
| **Service** | Địa chỉ nội bộ ổn định trỏ tới nhóm pod (vì pod sinh ra chết đi liên tục, địa chỉ IP thay đổi) |
| **Ingress** | Cửa ngõ từ Internet vào, định tuyến theo tên miền/đường dẫn, lo chứng chỉ HTTPS |
| **HPA** | Horizontal Pod Autoscaler — tự tăng số pod khi CPU cao, giảm khi rảnh (dự án đặt 2→10) |
| **Namespace** | Vùng ngăn cách logic (`meetly-staging`, `meetly-prod` tách biệt nhau) |
| **Secret** | Nơi chứa dữ liệu nhạy cảm (mật khẩu DB, khoá bí mật) |
| **Liveness probe** | K8s hỏi "còn sống không?" — không trả lời thì bị khởi động lại |
| **Readiness probe** | K8s hỏi "nhận việc được chưa?" — chưa sẵn sàng thì không gửi request tới |

### 9.4 Helm

Viết file cấu hình K8s bằng tay cho mỗi môi trường thì trùng lặp và dễ sai. **Helm** là "trình
quản lý gói" cho K8s: viết **khuôn mẫu** một lần, rồi truyền **giá trị** khác nhau cho từng
môi trường.

```
ops/helm/meetly/
├── templates/        — khuôn mẫu (deployment, service, ingress, HPA...)
├── values.yaml       — giá trị mặc định (production)
├── values-staging.yaml — ghi đè cho môi trường thử nghiệm
└── values-prod.yaml
```

Staging chạy 1 pod, production chạy 2–10 pod: **cùng một khuôn, khác giá trị**.

### 9.5 CI/CD với GitHub Actions

- **CI** (Continuous Integration — tích hợp liên tục): mỗi khi có đề xuất thay đổi code, tự
  động chạy test. Hỏng thì không cho gộp vào nhánh chính.
- **CD** (Continuous Deployment — triển khai liên tục): code vào nhánh chính thì tự động đóng
  gói và đưa lên máy chủ.

Dây chuyền của dự án:

```
Mở Pull Request  → chạy test backend + frontend
                   (chỉ chạy phần bị ảnh hưởng — gọi là path filter)

Gộp vào main     → build 2 image, gắn nhãn theo mã commit
                 → quét bảo mật bằng Trivy
                 → triển khai lên staging
                 → chạy e2e trên staging
                 → CHỜ NGƯỜI DUYỆT ✋
                 → triển khai lên production
```

**Trivy** quét image tìm lỗ hổng bảo mật đã biết (CVE) trong các thư viện. Có lỗ hổng nghiêm
trọng thì dây chuyền dừng.

**Vì sao gắn nhãn image bằng mã commit** thay vì `latest`? Vì `latest` mơ hồ — không biết đang
chạy code nào, và không quay lui chính xác được. Mã commit thì truy ngược được đúng dòng code.

### 9.6 Giám sát: Prometheus, Grafana

- **Prometheus** định kỳ "hỏi thăm" ứng dụng qua địa chỉ `/actuator/prometheus` và lưu lại các
  con số theo thời gian (số request, độ trễ, bộ nhớ...). Những con số đó gọi là **metric**.
- **Grafana** vẽ các con số đó thành biểu đồ.
- **Alert** (cảnh báo): quy tắc kiểu "nếu tỷ lệ lỗi 5xx vượt 5% trong 5 phút thì báo động".

Dự án cấu hình sẵn 4 cảnh báo: tỷ lệ lỗi cao, phản hồi chậm, pod khởi động lại liên tục, máy
chủ LiveKit gần đầy tải.

**Log dạng JSON**: ở production, log được ghi thành JSON một dòng thay vì chữ thường. Máy đọc
được → có thể tìm kiếm kiểu "cho tôi mọi log của request `X-Request-Id` này".

### 9.7 External Secrets Operator

Mật khẩu database, khoá bí mật **tuyệt đối không được nằm trong Git**. Chúng được cất trong
kho bí mật của nhà cung cấp đám mây (AWS Secrets Manager). **External Secrets Operator** là
thành phần chạy trong K8s, tự động lấy bí mật từ kho đó và tạo thành Secret cho ứng dụng dùng.

---

## 10. Ba luồng quan trọng nhất

### 10.1 Vào phòng họp

```
1. Người dùng mở link  /m/abc-defg-hij
2. Trình duyệt xin quyền camera/micro, hiện màn hình xem trước (PreJoin)
3. Bấm "Join" → gọi  POST /api/v1/meetings/abc-defg-hij/join
4. Backend kiểm tra lần lượt:
   ├── Phòng có tồn tại không?          → không thì 404
   ├── Phòng đã kết thúc/bị huỷ chưa?   → rồi thì 409
   ├── Đã tới giờ chưa?                 → sớm quá thì 403 (host được miễn)
   └── Người này vai gì?
       ├── Là chủ phòng            → HOST
       ├── Có trong danh sách mời  → vai đã gán (SPEAKER/ATTENDEE)
       ├── Người lạ + phòng WEBINAR → ATTENDEE
       └── Người lạ + phòng kín     → 403 NOT_A_MEMBER
5. Backend sinh vé LiveKit với đúng quyền của vai đó, ký số
6. Trả về: { địa chỉ LiveKit, vé, vai trò }
7. Trình duyệt cầm vé kết nối THẲNG tới LiveKit — backend không tham gia nữa
8. LiveKit xác minh chữ ký vé, cho vào phòng với đúng quyền ghi trong vé
```

### 10.2 Gửi tin nhắn chat

```
1. Gõ tin, bấm gửi → gửi qua WebSocket tới  /app/meetings/{id}/chat
2. Backend kiểm tra quyền (ChatAccessGuard):
   - Khách: vé chat có đúng phòng này không?
   - Thành viên: có thuộc phòng, hoặc phòng có phải webinar mở không?
3. Lưu tin vào PostgreSQL (để sau vào còn xem lại được)
4. Phát tin lên kênh Redis "chat:<id phòng>"
5. MỌI pod backend đang nghe kênh đó đều nhận được
6. Mỗi pod đẩy tin xuống những người đang kết nối với chính nó
7. Trình duyệt nhận và hiển thị
```

Nếu mạng rớt rồi nối lại, frontend gọi thêm API lịch sử với tham số "lấy tin sau thời điểm X"
để bù những tin bị lỡ.

### 10.3 Ghi hình

```
1. Host bấm "⏺ Record" → POST /api/v1/meetings/{id}/recordings/start
2. Backend kiểm tra: đúng host? phòng cho phép ghi? chưa có bản ghi nào đang chạy?
3. Backend gọi API Egress của LiveKit, kèm thông tin kho lưu trữ
4. Egress mở Chrome ẩn, vào phòng, bắt đầu quay
5. Backend lưu bản ghi với trạng thái STARTING
6. Egress gửi webhook "egress_started" → trạng thái ACTIVE
7. Host bấm dừng (hoặc phòng kết thúc → Egress tự dừng)
8. Egress đóng file, tải MP4 lên MinIO/S3
9. Egress gửi webhook "egress_ended" → COMPLETED, kèm dung lượng, thời lượng
10. Vào trang /recordings/{id}, bấm xem:
    → backend kiểm tra quyền, tạo presigned URL hạn 1 giờ
    → thẻ <video> phát thẳng từ kho lưu trữ
```

---

## 11. Bảng tra cứu thuật ngữ

| Thuật ngữ | Giải thích ngắn |
|---|---|
| **Access token** | Vé đi đường ngắn hạn (15 phút), gắn vào mỗi request API |
| **BCrypt** | Thuật toán băm mật khẩu, cố tình chậm để chống dò |
| **CI/CD** | Tự động chạy test / tự động triển khai |
| **Container** | Ứng dụng đang chạy trong môi trường đóng gói, cách ly |
| **CORS** | Quy tắc trình duyệt chặn gọi API khác tên miền |
| **CVE** | Mã định danh của một lỗ hổng bảo mật đã công bố |
| **Docker image** | Bản đóng gói tĩnh của ứng dụng + môi trường |
| **Egress** | Thành phần LiveKit chuyên ghi hình phòng họp |
| **E2E test** | Test toàn hệ thống qua trình duyệt thật |
| **Flyway** | Công cụ quản lý thay đổi cấu trúc database theo phiên bản |
| **Grants** | Danh sách quyền ghi trong vé LiveKit |
| **Helm** | Trình quản lý gói cho Kubernetes, dùng khuôn mẫu + giá trị |
| **HMR** | Sửa code là trình duyệt cập nhật ngay, không mất trạng thái |
| **HPA** | Tự động tăng/giảm số pod theo tải |
| **httpOnly cookie** | Cookie JavaScript không đọc được, chống đánh cắp bằng XSS |
| **ICE** | Quy trình thử mọi đường mạng để tìm đường kết nối được |
| **Idempotent** | Chạy lại nhiều lần cho kết quả như chạy một lần |
| **Ingress** | Cửa ngõ từ Internet vào cụm Kubernetes |
| **JPA/Hibernate** | Ánh xạ class Java ↔ bảng database |
| **JWT** | Chuỗi chứa thông tin đã ký số, dùng để xác thực |
| **Kubernetes** | Hệ thống điều phối container trên nhiều máy |
| **LiveKit** | Máy chủ SFU mã nguồn mở xử lý video |
| **Metric** | Con số đo được theo thời gian (độ trễ, số request...) |
| **MinIO** | Kho lưu file tự cài, tương thích S3 |
| **Multi-stage build** | Dockerfile nhiều tầng, tầng cuối chỉ giữ thứ cần chạy |
| **NAT** | Nhiều máy trong nhà dùng chung một địa chỉ IP công khai |
| **Non-root container** | Container chạy bằng user thường, an toàn hơn |
| **Pod** | Đơn vị chạy nhỏ nhất trong Kubernetes |
| **Polling** | Hỏi lại liên tục để biết có dữ liệu mới (cách kém) |
| **Presigned URL** | Đường dẫn tải file có chữ ký, tự hết hạn |
| **Prometheus** | Hệ thống thu thập metric |
| **Publish / Subscribe** | Gửi track lên / nhận track về (WebRTC) |
| **Redis** | Kho dữ liệu trong bộ nhớ, làm cache và kênh truyền tin |
| **Refresh token** | Vé dài hạn (14 ngày) chỉ dùng để xin access token mới |
| **RFC 7807** | Chuẩn định dạng trả lỗi của API |
| **Rotation** | Mỗi lần dùng refresh token thì cấp mới, thu hồi cũ |
| **S3** | Dịch vụ lưu file của Amazon |
| **SFU** | Máy chủ nhận 1 luồng từ mỗi người rồi chuyển tiếp cho những người khác |
| **Simulcast** | Gửi cùng lúc nhiều độ phân giải để máy chủ chọn |
| **STOMP** | Quy ước định dạng tin nhắn chạy trên WebSocket |
| **STUN** | Máy chủ giúp biết địa chỉ công khai của mình |
| **Tailwind CSS** | Viết giao diện bằng các lớp tiện ích gắn thẳng vào HTML |
| **TanStack Query** | Thư viện quản lý dữ liệu lấy từ server |
| **Testcontainers** | Tự bật container database thật khi chạy test |
| **Track** | Một luồng media (video hoặc tiếng) |
| **Trivy** | Công cụ quét lỗ hổng bảo mật trong image |
| **TURN / coturn** | Máy chủ trung chuyển media khi tường lửa chặn |
| **UDP / TCP** | Giao thức gửi nhanh không đảm bảo / gửi chắc chắn |
| **Vite** | Công cụ build và máy chủ phát triển frontend |
| **WebRTC** | Công nghệ truyền video/tiếng thời gian thực trong trình duyệt |
| **WebSocket** | Kết nối hai chiều giữ mở lâu dài |
| **Webhook** | Dịch vụ ngoài chủ động gọi ngược về hệ thống của ta |
| **Zustand** | Thư viện quản lý trạng thái giao diện phía client |

---

Tiếp theo: [02-huong-dan-chay-va-test.md](02-huong-dan-chay-va-test.md) — hướng dẫn tự chạy và thử từng tính năng.
