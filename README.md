# Meetly

Hệ thống họp video (BE Spring Boot · FE React · LiveKit SFU).
Spec: `docs/superpowers/specs/2026-07-18-meetly-video-conferencing-design.md`

> Quy ước: **code, comment và giao diện viết tiếng Anh; tài liệu viết tiếng Việt.**

## 📖 Bắt đầu từ đâu

Nếu anh/chị mới tiếp cận dự án, đọc theo thứ tự này:

| # | Tài liệu | Nội dung |
|---|---|---|
| 1 | [Giải thích công nghệ](docs/01-giai-thich-cong-nghe.md) | Toàn bộ công nghệ trong dự án, giải thích từng thuật ngữ từ đầu — dành cho người biết Spring Boot CRUD và React cơ bản |
| 2 | [Hướng dẫn chạy và kiểm thử](docs/02-huong-dan-chay-va-test.md) | Cầm tay chỉ việc: cài gì, chạy lệnh nào, thử từng tính năng ra sao, hỏng thì sửa thế nào |
| 3 | [Thiết kế hệ thống](docs/superpowers/specs/2026-07-18-meetly-video-conferencing-design.md) | Bản thiết kế gốc và các quyết định kiến trúc |
| 4 | [Runbook vận hành](docs/runbooks/) | Load test và checklist trước khi lên production |

## Dev quickstart

```bash
# 1. Hạ tầng (Postgres, Redis, LiveKit, MinIO, Egress)
docker compose -f ops/compose/docker-compose.dev.yml up -d

# 2. Backend (http://localhost:8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

Mở http://localhost:5173 bằng 2 cửa sổ trình duyệt (1 thường + 1 ẩn danh),
đăng ký 2 tài khoản, một bên bấm "Meet now" rồi gửi link `/m/<code>` cho bên kia.
Với phòng **Webinar**, người chưa đăng nhập mở thẳng link `/m/<code>` là vào được
với tư cách khách (chỉ xem, không phát).

## Kiểm thử

```bash
cd backend && ./mvnw test          # 42 test, cần Docker cho Testcontainers
cd frontend && npm run lint && npm test && npm run build
cd frontend && npm run e2e         # cần compose + BE + FE đang chạy
```

## Bản ghi (recording)

Host bấm **⏺ Record** trong phòng → LiveKit Egress ghi ra MP4 → MinIO
(`http://localhost:9001`, đăng nhập `minio` / `minio12345`, bucket
`meetly-recordings`) → xem lại ở `/recordings/<meetingId>`.

> **Lưu ý macOS/Windows:** service `egress` đang chạy `network_mode: host` vì trên
> Linux, LiveKit dev quảng bá ICE `127.0.0.1` (`--node-ip 127.0.0.1`) mà container
> mạng bridge không với tới được (egress báo "Start signal not received").
> Docker Desktop trên macOS/Windows KHÔNG hỗ trợ host network tương đương — nếu
> recording không chạy trên máy đó, đổi `--node-ip` thành IP LAN của máy, bỏ
> `network_mode: host` của egress và trả `ops/compose/egress.yaml` về
> `ws_url: ws://livekit:7880` + `redis.address: redis:6379`, đồng thời đổi
> `meetly.storage.upload-endpoint` thành `http://minio:9000`.
> Production không dính vấn đề này (`use_external_ip: true`, IP public thật).

## Ops

| Thư mục | Nội dung |
|---|---|
| `ops/compose/` | Hạ tầng dev một lệnh |
| `ops/docker/` | Dockerfile production (api, web — non-root) |
| `ops/helm/meetly/` | Chart api + web + ingress + HPA + monitoring |
| `ops/helm/livekit/` | Values production cho LiveKit + Egress |
| `ops/k8s/` | External Secrets, coturn |
| `ops/monitoring/` | Dashboard Grafana, hướng dẫn kube-prometheus-stack |
| `docs/runbooks/` | Load test, go-live checklist |
