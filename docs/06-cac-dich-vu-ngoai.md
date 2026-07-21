# Meetly — Các dịch vụ bên thứ ba (chi tiết từng cái)

> Meetly không tự viết mọi thứ. Nó dựng lên trên **6 dịch vụ mã nguồn mở/có sẵn**. Tài liệu
> này giải thích từng dịch vụ: nó là gì, giải quyết vấn đề gì, được cấu hình thế nào trong dự
> án, và trên production khác gì so với máy dev.
>
> Đọc kèm [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md) (phần video call) và
> [05-di-sau-ops.md](05-di-sau-ops.md).

---

## Mục lục

1. [Vì sao dùng dịch vụ có sẵn thay vì tự viết](#1-vì-sao-dùng-dịch-vụ-có-sẵn)
2. [Toàn cảnh 6 dịch vụ](#2-toàn-cảnh-6-dịch-vụ)
3. [LiveKit — máy chủ video](#3-livekit--máy-chủ-video)
4. [Egress — bộ ghi hình](#4-egress--bộ-ghi-hình)
5. [Redis — bộ nhớ đệm và loa phát thanh](#5-redis)
6. [PostgreSQL — cơ sở dữ liệu](#6-postgresql)
7. [MinIO / S3 — kho lưu file](#7-minio--s3)
8. [coturn — vượt tường lửa](#8-coturn)
9. [Dev khác Production ở đâu](#9-dev-khác-production)
10. [Khi một dịch vụ chết thì sao](#10-khi-một-dịch-vụ-chết)

---

## 1. Vì sao dùng dịch vụ có sẵn

Một nguyên tắc kỹ thuật: **đừng tự viết lại những thứ khó đã có người làm tốt**. Ba ví dụ:

- Viết một máy chủ video (SFU) đàng hoàng cần **6–12 tháng** và cả một đội chuyên gia. LiveKit
  đã làm sẵn, miễn phí, mã nguồn mở.
- Viết một database chịu được hàng nghìn giao dịch đồng thời, không mất dữ liệu khi mất điện —
  hàng chục năm nghiên cứu. PostgreSQL đã có.
- Tự dựng kho lưu file bền vững, nhân bản chống hỏng — Amazon S3 đã có.

Meetly tập trung viết **phần riêng của mình** (logic họp, phân quyền, chat), còn những phần
hạ tầng thì **dùng lại**. Đây là lựa chọn khôn ngoan, không phải lười.

**Điểm mấu chốt về ranh giới:** những dịch vụ này, ta chỉ **vận hành và cấu hình**, không sửa
code của chúng. Backend Spring Boot của ta **giao tiếp** với chúng qua API hoặc giao thức
chuẩn.

---

## 2. Toàn cảnh 6 dịch vụ

| Dịch vụ | Vai trò | Bắt buộc? | Trên production |
|---|---|---|---|
| **LiveKit** | Máy chủ xử lý video/tiếng | Có — không có thì không họp được | Tự cài (Helm) |
| **Egress** | Ghi hình phòng ra file | Chỉ khi cần ghi hình | Tự cài (Helm) |
| **Redis** | Kênh chat đa server + chống trùng | Có — chat và điều phối cần | Dịch vụ quản lý |
| **PostgreSQL** | Lưu dữ liệu chính | Có | Dịch vụ quản lý |
| **MinIO → S3** | Lưu file ghi hình | Chỉ khi cần ghi hình | Đổi sang S3 thật |
| **coturn** | Vượt tường lửa chặn UDP | Chỉ production | VM riêng |

"Dịch vụ quản lý" (managed service) nghĩa là **thuê từ nhà cung cấp đám mây**, họ lo vận hành
(sao lưu, cập nhật, thay máy hỏng), ta chỉ dùng. Ví dụ Amazon RDS cho PostgreSQL, ElastiCache
cho Redis. Không tự cài trong Kubernetes vì database là thứ quý nhất, không nên tự lo.

---

## 3. LiveKit — máy chủ video

### 3.1 Nhắc lại vai trò

Nhớ tài liệu 01: gọi video nhóm cần một **SFU** (máy chủ nhận 1 luồng từ mỗi người rồi chuyển
tiếp cho những người khác). **LiveKit chính là SFU đó.** Đây là dịch vụ quan trọng nhất — không
có nó thì không có video.

**Ranh giới cực kỳ quan trọng:** video/tiếng đi **thẳng** giữa trình duyệt và LiveKit, KHÔNG
qua backend Spring Boot. Backend chỉ **cấp vé** (tài liệu 03 mục 10). Nhờ vậy backend nhẹ,
không phải gánh hàng chục Mbps video.

```
Trình duyệt  ──video/tiếng (UDP)──►  LiveKit  ──chuyển tiếp──►  Trình duyệt khác
     │
     └──xin vé (HTTPS)──►  Backend  (chỉ cấp vé, không đụng video)
```

### 3.2 Cấu hình thật

Mở `ops/compose/livekit.yaml` (cấu hình môi trường dev):

```yaml
port: 7880                     # cổng tín hiệu (bắt tay, điều khiển)
rtc:
  tcp_port: 7881               # media qua TCP (dự phòng khi UDP bị chặn)
  port_range_start: 50000      # dải cổng UDP cho media
  port_range_end: 50060        # (dev chỉ mở 60 cổng; production mở cả nghìn)
  use_external_ip: false       # dev: false; production: true
keys:
  devkey: meetly_dev_secret_0123456789abcdef    # cặp khoá API
webhook:
  api_key: devkey
  urls:
    - http://host.docker.internal:8080/api/v1/livekit/webhook   # báo sự kiện về backend
logging:
  level: info
redis:
  address: redis:6379          # nhiều LiveKit tìm nhau qua Redis
```

Giải thích từng phần:

- **`port: 7880`** — cổng "tín hiệu". Đây là nơi trình duyệt bắt tay ban đầu, thoả thuận sẽ
  gửi video thế nào. Nhẹ.
- **`rtc.port_range_start/end`** — dải cổng UDP thật sự **chở video/tiếng**. Mỗi kết nối chiếm
  một cổng. Dev mở 60 cổng (đủ thử), production mở cả nghìn (50000–60000) cho nhiều người.
- **`tcp_port: 7881`** — nếu tường lửa chặn UDP, LiveKit thử chuyển video qua TCP cổng này. Chậm
  hơn UDP nhưng còn hơn không vào được.
- **`keys`** — cặp khoá API. `devkey` là "tên khoá", chuỗi dài là "bí mật". Đây chính là cặp
  mà backend dùng để **ký vé** (tài liệu 03 mục 10). Backend và LiveKit **phải dùng chung** cặp
  này thì vé mới hợp lệ. (Xem lại `application.yml` của backend: `meetly.livekit.api-key: devkey`
  khớp đúng.)
- **`webhook.urls`** — địa chỉ LiveKit **gọi ngược về backend** khi có sự kiện (ai vào phòng,
  phòng kết thúc...). `host.docker.internal` là cách container LiveKit gọi ra backend chạy trên
  máy thật. Backend nhận ở `WebhookController` (tài liệu 03 mục 13).
- **`redis.address`** — khi chạy nhiều máy chủ LiveKit, chúng dùng Redis để biết nhau và phối
  hợp (một phòng đông quá thì chia người sang máy khác).

### 3.3 Backend gọi LiveKit thế nào

Có hai chiều giao tiếp:

**Backend → LiveKit** (khi host điều khiển): `RoomControlService.java`. Ví dụ host thăng quyền
cho khách, backend gọi API LiveKit để đổi quyền của người đó ngay lập tức:

```java
// (rút gọn từ RoomControlService)
public void setRole(String room, String identity, MeetingRole role) {
    boolean canPublish = role != MeetingRole.ATTENDEE;
    client.updateParticipant(room, identity, ...canPublish...);   // gọi API LiveKit
}
```

**LiveKit → Backend** (báo sự kiện): qua webhook, backend cập nhật database (đánh dấu phòng
đang họp, ghi điểm danh...).

### 3.4 Production: node pool riêng

Nhớ ở tài liệu 05, backend chạy trong Kubernetes bình thường. Nhưng LiveKit **đặc biệt**, cần
nhóm máy riêng (`ops/helm/livekit/values-livekit-prod.yaml`):

```yaml
livekit:
  rtc:
    port_range_start: 50000
    port_range_end: 60000        # mở cả nghìn cổng UDP
    use_external_ip: true        # production: dùng IP công khai thật
nodeSelector:
  role: livekit                  # chỉ chạy trên máy gắn nhãn "livekit"
hostNetwork: true                # dùng thẳng mạng của máy (không qua lớp ảo)
```

Vì sao khác biệt:

- **`hostNetwork: true`** — LiveKit cần UDP đi **thẳng** vào máy, không qua lớp mạng ảo của
  Kubernetes (lớp ảo làm chậm và phức tạp cho UDP). Đây là ngoại lệ; backend thường không cần.
- **`nodeSelector: role: livekit`** — chạy trên nhóm máy có IP công khai, mở sẵn dải cổng UDP.
  Tách khỏi backend vì nhu cầu mạng rất khác.
- **`use_external_ip: true`** — production quảng bá IP công khai thật để trình duyệt ngoài
  Internet kết nối được. (Dev để `false` vì chỉ chạy nội bộ máy — và chính chỗ này gây ra lỗi
  Egress trên máy dev, xem mục 4.)

---

## 4. Egress — bộ ghi hình

### 4.1 Cách hoạt động (nhắc lại + chi tiết)

Nhớ tài liệu 01: Egress ghi hình bằng cách **mở một trình duyệt Chrome ẩn, cho nó "vào phòng"
như người xem vô hình, rồi quay lại màn hình** thành MP4.

Cấu hình `ops/compose/egress.yaml`:

```yaml
api_key: devkey
api_secret: meetly_dev_secret_0123456789abcdef   # cùng khoá với LiveKit
ws_url: ws://localhost:7880                        # địa chỉ LiveKit để "vào phòng"
redis:
  address: localhost:6379                          # cùng Redis với LiveKit
insecure: true                                     # dev: cho phép không mã hoá
```

- **`api_key/api_secret`** — cùng cặp khoá với LiveKit. Egress cũng cần vé để vào phòng ghi hình.
- **`ws_url`** — địa chỉ LiveKit mà Egress kết nối vào để "dự họp".
- **`redis`** — Egress và LiveKit phải **chung một Redis** để phối hợp (LiveKit ra lệnh "ghi
  phòng này", Egress nhận qua Redis).

### 4.2 Luồng ghi hình đầy đủ

Ghép với backend (tài liệu 03):

```
1. Host bấm Record → backend RecordingService.start()
2. Backend gọi EgressClient → gửi lệnh cho LiveKit Egress (kèm thông tin kho lưu file)
3. Egress mở Chrome ẩn, vào phòng, bắt đầu quay
4. Egress → webhook "egress_started" → backend đánh dấu ACTIVE
5. Host bấm dừng → Egress đóng file, TẢI THẲNG lên MinIO/S3 (không qua backend)
6. Egress → webhook "egress_ended" → backend đánh dấu COMPLETED, lưu dung lượng
```

Chú ý bước 5: Egress tải file **thẳng** lên kho lưu, không đi qua backend. Vì file video nặng,
bắt nó đi vòng qua backend sẽ tốn băng thông và bộ nhớ vô ích.

### 4.3 Tốn tài nguyên — vì sao node riêng

Chạy cả một Chrome + mã hoá video là việc **rất nặng**: khoảng **2–4 lõi CPU cho mỗi bản ghi
đang chạy**. Nên `ops/helm/livekit/values-egress-prod.yaml` đặt Egress trên nhóm máy CPU mạnh
riêng:

```yaml
nodeSelector:
  role: egress
resources:
  requests: { cpu: "2", memory: 4Gi }
  limits: { cpu: "4", memory: 8Gi }     # tối đa 4 lõi, 8GB RAM mỗi bản
```

Từ đó suy ra một giới hạn thực tế: nếu nhóm máy egress có 16 lõi, thì ghi được tối đa ~4 phòng
cùng lúc (4 lõi/phòng). Đây là con số cần cân nhắc khi lập kế hoạch capacity.

### 4.4 Lỗi Egress trên máy dev — câu chuyện thật đáng biết

Đây là sự cố em đã gặp và xử lý trong dự án, giải thích để anh hiểu bản chất:

**Triệu chứng:** ghi hình trên máy Linux báo lỗi `Start signal not received`, file ra 0 byte.

**Nguyên nhân — gốc rễ ở ICE (tài liệu 01 mục 3.2):** LiveKit ở dev cấu hình
`--node-ip 127.0.0.1`, tức nó nói với mọi người "địa chỉ của tôi là `127.0.0.1`". Nhưng
`127.0.0.1` nghĩa là "chính máy này". Container Egress nghe vậy, cố kết nối tới `127.0.0.1`,
nhưng với **nó** thì `127.0.0.1` là **chính container Egress**, không phải LiveKit → không bao
giờ kết nối được.

**Cách sửa (trên Linux):** cho Egress dùng chung mạng với máy thật (`network_mode: host`), khi
đó `127.0.0.1` của Egress trùng với `127.0.0.1` của LiveKit → kết nối được. Đã ghi trong
`docker-compose.dev.yml`:

```yaml
egress:
  network_mode: host    # dùng chung mạng máy thật
```

**Điểm cần nhớ:** cách này chạy trên **Linux**, nhưng **Docker Desktop trên macOS/Windows
không hỗ trợ tương đương**. Đó là lý do có cảnh báo trong README. **Production KHÔNG dính lỗi
này** vì `use_external_ip: true` khiến LiveKit quảng bá IP công khai thật, không phải `127.0.0.1`.

> Câu chuyện này minh hoạ vì sao ICE/NAT (tài liệu 01) là kiến thức nền quan trọng: một dòng
> cấu hình IP sai chỗ làm cả tính năng ghi hình sập, và phải hiểu ICE mới lần ra nguyên nhân.

---

## 5. Redis

### 5.1 Redis là gì

**Redis** là một kho dữ liệu **trong bộ nhớ** (RAM, không phải đĩa). Vì nằm trong RAM nên cực
nhanh (hàng trăm nghìn thao tác/giây), nhưng dung lượng nhỏ và không hợp lưu dữ liệu lâu dài.
Hợp cho những việc cần nhanh và tạm thời.

Trong Meetly, Redis làm **ba việc**:

### 5.2 Việc 1 — Kênh chat đa server (quan trọng nhất)

Đây là việc em đã giải thích kỹ ở tài liệu 01 mục 5.3 và tài liệu 03 mục 12. Tóm tắt: khi chạy
nhiều bản backend, người gửi tin nối server A, người nhận nối server B. Redis đóng vai "loa
phát thanh chung": server A phát tin lên Redis, **mọi** server (kể cả B) nghe được, rồi đẩy
xuống người dùng của mình.

Cơ chế này gọi là **pub/sub** (publish/subscribe — xuất bản/đăng ký). Redis có sẵn, backend
chỉ việc dùng.

### 5.3 Việc 2 — Chống trùng webhook

Nhớ tài liệu 03: LiveKit có thể gửi lại **cùng một sự kiện webhook** nếu nghi ngờ backend chưa
nhận. Nếu xử lý cả bản trùng, một người vào phòng có thể bị ghi điểm danh 2–3 lần.

Backend dùng Redis để nhớ "sự kiện này đã xử lý rồi": lưu mã sự kiện vào Redis với hạn 24 giờ.
Sự kiện tới, kiểm Redis trước — đã có thì bỏ qua. Thao tác này gọi là `SETNX` (set nếu chưa
tồn tại), nhanh vì Redis trong RAM.

### 5.4 Việc 3 — Giúp các LiveKit tìm nhau

Khi chạy nhiều máy chủ LiveKit, chúng dùng Redis để biết nhau và phối hợp định tuyến. Đây là
tính năng của LiveKit, ta chỉ cấu hình cùng một Redis cho cả LiveKit và Egress.

### 5.5 Cấu hình

Trong `docker-compose.dev.yml`:

```yaml
redis:
  image: redis:7-alpine
  ports: ["6379:6379"]
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]    # kiểm tra bằng lệnh ping, trả PONG
```

Backend kết nối qua `application.yml`: `spring.data.redis.host: localhost`, `port: 6379`.

Trên production, Redis là **dịch vụ quản lý** (AWS ElastiCache). Một lưu ý trong runbook:
Redis production nên đặt chính sách `noeviction` — nghĩa là khi đầy bộ nhớ thì **báo lỗi chứ
không tự xoá key**. Vì key của ta là tin nhắn chat đang truyền và định tuyến LiveKit, xoá nhầm
là mất tin/rối loạn.

---

## 6. PostgreSQL

### 6.1 Vai trò

Đây là dịch vụ anh quen nhất — cơ sở dữ liệu quan hệ, lưu **toàn bộ dữ liệu nghiệp vụ**:

| Bảng | Chứa gì |
|---|---|
| `users` | Tài khoản (email, mật khẩu băm, tên) |
| `refresh_tokens` | Token làm mới (dạng băm) |
| `meetings` | Phòng họp |
| `meeting_members` | Ai được mời vào phòng nào, vai gì |
| `participant_sessions` | Điểm danh (ai vào/ra lúc nào) |
| `chat_messages` | Tin nhắn |
| `recordings` | Thông tin bản ghi hình |

Backend nói chuyện với PostgreSQL qua JPA/Hibernate như CRUD anh quen (tài liệu 03). Cấu trúc
bảng do Flyway quản lý (3 file `V1`, `V2`, `V3`).

### 6.2 Vì sao production dùng dịch vụ quản lý

Database là thứ **quý nhất** — mất dữ liệu là mất tất cả. Tự vận hành PostgreSQL trong
Kubernetes đòi hỏi tự lo: sao lưu định kỳ, khôi phục khi máy hỏng, cập nhật bảo mật, mở rộng
dung lượng. Rất dễ sai và hậu quả nặng.

Nên production dùng **Amazon RDS** hoặc **Google Cloud SQL** — nhà cung cấp lo hết những việc
đó. Ta chỉ nhận một "chuỗi kết nối" và dùng. Đây là quyết định kiến trúc D6 trong bản thiết kế.

### 6.3 Migration an toàn khi cập nhật

Nhớ tài liệu 03: Flyway chỉ tiến, không lùi (forward-only). Có một quy tắc thêm cho production:
mỗi lần cập nhật, thay đổi database phải **tương thích ngược** trong cùng một đợt — chỉ **thêm**
cột/bảng, **không đổi tên hay xoá**.

Vì sao? Vì lúc cập nhật, K8s thay pod **dần dần** — có lúc pod code cũ và pod code mới **chạy
song song**, cùng nối một database. Nếu đợt cập nhật xoá một cột mà pod cũ còn cần, pod cũ sẽ
lỗi. Chỉ thêm (không xoá) thì cả cũ lẫn mới đều chạy được. Đây là điều kiện để cập nhật **không
gián đoạn dịch vụ**.

---

## 7. MinIO / S3 — kho lưu file

### 7.1 Vì sao không lưu video vào database

Database giỏi lưu dữ liệu có cấu trúc nhỏ (một dòng user, một tin nhắn). Nó **không hợp lưu
file lớn** như video — vừa đắt vừa chậm. File video cần một loại kho khác.

**S3** (Simple Storage Service của Amazon) là kho chuyên chứa file: rẻ, dung lượng gần như vô
hạn, tự nhân bản chống hỏng. Nó tổ chức theo "thùng" (bucket) và "khoá" (key, giống đường dẫn
file).

### 7.2 MinIO — S3 chạy trên máy mình

Khi phát triển, không lẽ mỗi lần thử ghi hình lại tải lên Amazon thật. **MinIO** là phần mềm
mã nguồn mở **nói cùng ngôn ngữ với S3**, cài được trên máy. Nhờ vậy:

- Dev: dùng MinIO (`localhost:9000`).
- Production: đổi sang S3 thật.
- **Code backend không đổi một dòng** — chỉ đổi cấu hình địa chỉ.

Đó là vì backend dùng thư viện chuẩn của Amazon (`software.amazon.awssdk:s3`), và cả MinIO lẫn
S3 đều hiểu thư viện đó.

### 7.3 Cấu hình

Trong `docker-compose.dev.yml`:

```yaml
minio:
  image: minio/minio:latest
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: minio
    MINIO_ROOT_PASSWORD: minio12345
  ports: ["9000:9000", "9001:9001"]    # 9000 = API, 9001 = giao diện web

createbucket:                           # tiến trình tạo "thùng" rồi thoát
  image: minio/mc:latest
  entrypoint: >
    ... mc mb -p local/meetly-recordings ...
```

`createbucket` là tiến trình dùng-một-lần: tạo thùng `meetly-recordings` rồi kết thúc (nhớ
tài liệu 02: nó `Exited (0)` là đúng).

### 7.4 Presigned URL — cho xem file an toàn

Đây là kỹ thuật hay, em giải thích kỹ ở tài liệu 01 mục 7. Nhắc lại bằng code:

Bản ghi không thể để ai cũng tải. Nhưng cũng không nên bắt video đi qua backend (tốn tài
nguyên). Giải pháp: `StorageService.java` tạo **đường dẫn có chữ ký, hạn 1 giờ**:

```java
public String presignGetUrl(String key, Duration ttl) {
    // tạo request lấy file, ký bằng khoá bí mật, đặt hạn dùng
    return presigner.presignGetObject(...ttl...).url().toString();
}
```

Backend kiểm quyền một lần, rồi trả đường dẫn ký sẵn này cho trình duyệt. Trình duyệt tải
**thẳng** từ kho lưu (không qua backend). Hết 1 giờ, đường dẫn vô hiệu. Như tấm vé xem phim:
soát một lần ở cửa, chỉ dùng cho suất đó.

### 7.5 Chi tiết dev tinh tế — hai địa chỉ endpoint

Ở `application.yml` có hai cấu hình storage nhìn hơi lạ:

```yaml
meetly:
  storage:
    endpoint: http://localhost:9000         # cho backend/browser tạo presigned URL
    upload-endpoint: http://localhost:9000  # cho Egress tải file lên
```

Trên production hai cái này giống nhau (đều là địa chỉ S3 thật). Nhưng ở dev từng có lúc chúng
**khác nhau**, vì backend nhìn MinIO từ "góc máy thật" còn Egress (trong container) nhìn từ
"góc mạng container" — hai góc thấy MinIO ở địa chỉ khác nhau. Sau khi chuyển Egress sang
`network_mode: host` (mục 4.4), cả hai cùng là `localhost:9000`.

> Đây lại là một hệ quả của bài học mạng container. Với người mới, những chi tiết địa chỉ mạng
> này chính là chỗ tốn nhiều thời gian debug nhất — nên em ghi lại kỹ.

---

## 8. coturn

### 8.1 Vấn đề nó giải quyết

Nhớ tài liệu 01 mục 3.2: có những mạng (tường lửa công ty khắt khe) **chặn sạch UDP**. Với các
mạng đó, video không đi thẳng tới LiveKit được. Cần một máy chủ **trung chuyển**: trình duyệt
gửi cho nó, nó chuyển tiếp tới LiveKit. Đó là **TURN server**, và **coturn** là phần mềm TURN
mã nguồn mở dự án dùng.

### 8.2 Mẹo cổng 443

Cấu hình `ops/k8s/coturn/coturn-configmap.yaml`:

```yaml
turnserver.conf: |
  listening-port=3478            # cổng TURN chuẩn
  tls-listening-port=443         # ← TURN qua TLS trên cổng 443
  realm=turn.meetly.example.com
  use-auth-secret
```

Điểm thông minh: `tls-listening-port=443`. Cổng **443 là cổng HTTPS** — cổng mà **mọi tường
lửa đều phải mở** (nếu chặn thì không ai duyệt web được). Bằng cách chạy TURN qua TLS trên
443, lưu lượng video trung chuyển **nhìn giống hệt việc duyệt web bình thường**, tường lửa
không phân biệt được để chặn.

Đây là "đường thoát cuối cùng" — chậm hơn kết nối thẳng (vì phải đi vòng qua máy trung chuyển),
nhưng đảm bảo **kể cả sau tường lửa doanh nghiệp khắt khe nhất vẫn vào họp được**. Đây là rủi
ro số 1 trong bản thiết kế được giải quyết.

### 8.3 Chỉ cần trên production

Máy dev không cần coturn (chạy nội bộ, không có tường lửa doanh nghiệp). Nên nó chỉ xuất hiện
trong `ops/k8s/coturn/` (cấu hình Kubernetes), không có trong `docker-compose.dev.yml`. Cách
kiểm chứng nó hoạt động thật (từ mạng chặn UDP) nằm trong `go-live-checklist.md`.

---

## 9. Dev khác Production

Bảng tổng hợp để anh thấy rõ ranh giới. **Điểm quan trọng: code không đổi, chỉ đổi cấu hình.**

| Dịch vụ | Máy dev | Production |
|---|---|---|
| **LiveKit** | Container, dải cổng nhỏ, IP nội bộ | Node pool riêng, hostNetwork, IP công khai, dải cổng lớn |
| **Egress** | Container `network_mode: host` (mẹo cho Linux) | Node pool CPU mạnh riêng |
| **Redis** | Container | Dịch vụ quản lý (ElastiCache) |
| **PostgreSQL** | Container | Dịch vụ quản lý (RDS/Cloud SQL) |
| **Lưu file** | MinIO container | Amazon S3 thật |
| **coturn** | Không có | VM riêng, cổng 443 TLS |
| **Bí mật** | Ghi thẳng trong file (chỉ dev!) | External Secrets từ AWS |

Nhờ thiết kế này, quy trình lên production chủ yếu là **đổi các giá trị cấu hình** (địa chỉ,
khoá) trong file Helm values, chứ không phải viết lại code.

---

## 10. Khi một dịch vụ chết thì sao

Phần này lấy từ runbook sự cố (`go-live-checklist.md`), giúp anh hình dung độ bền của hệ thống:

| Dịch vụ chết | Ảnh hưởng | Vì sao |
|---|---|---|
| **Redis** | Chat tê liệt, LiveKit mất phối hợp đa node. **Nhưng video 1 node vẫn chạy** | Chat cần Redis làm loa phát; video không cần Redis |
| **Egress** | Ghi hình thất bại (bản ghi thành FAILED). **Họp không ảnh hưởng** | Egress tách rời khỏi luồng họp |
| **Một node LiveKit** | Phòng trên node đó **đứt**; client tự kết nối lại vào node khác | LiveKit đặt trọn 1 phòng trên 1 node; multi-node giúp phục vụ NHIỀU phòng, không chia 1 phòng |
| **PostgreSQL** | API lỗi toàn bộ (không đọc/ghi được gì) | Mọi thứ đều cần database |

Nhận xét quan trọng: hệ thống được thiết kế **tách lớp** — media (LiveKit), chat (Redis),
ghi hình (Egress), dữ liệu (PostgreSQL) độc lập nhau. Một mảng chết không kéo sập tất cả. Ví
dụ Egress chết thì người ta **vẫn họp bình thường**, chỉ là không ghi hình được.

Riêng bản ghi bị kẹt: nhớ ở phần cải thiện, nếu Egress chết giữa chừng làm bản ghi kẹt trạng
thái STARTING, backend sẽ **tự đánh dấu hỏng sau 5 phút** để host ghi lại được — không bị kẹt
vĩnh viễn.

---

## Tự kiểm tra hiểu bài

1. Vì sao video không đi qua backend Spring Boot mà đi thẳng tới LiveKit?
2. Egress ghi hình bằng cách nào? Vì sao nó tốn CPU đến vậy?
3. Redis làm ba việc gì trong dự án?
4. Vì sao production dùng RDS/S3 thật thay vì tự cài PostgreSQL/MinIO trong Kubernetes?
5. coturn dùng cổng 443 để làm gì?
6. Nếu Redis chết, video có còn chạy không? Vì sao?

*(Đáp án: mục 3.1, mục 4.1 và 4.3, mục 5, mục 6.2 và runbook, mục 8.2, mục 10.)*

---

## Kết thúc bộ tài liệu

Anh đã đi qua toàn bộ 6 quyển:

1. [Giải thích công nghệ](01-giai-thich-cong-nghe.md) — tổng quan và thuật ngữ
2. [Hướng dẫn chạy](02-huong-dan-chay-va-test.md) — tự chạy và thử
3. [Đi sâu Backend](03-di-sau-backend.md) — code Java
4. [Đi sâu Frontend](04-di-sau-frontend.md) — code React
5. [Đi sâu Ops](05-di-sau-ops.md) — Docker, Kubernetes, CI/CD
6. **Các dịch vụ ngoài** — LiveKit, Redis, MinIO... (quyển này)

Đọc hết là anh nắm được **toàn bộ hệ thống** từ dòng code tới hạ tầng production.
