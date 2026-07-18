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
