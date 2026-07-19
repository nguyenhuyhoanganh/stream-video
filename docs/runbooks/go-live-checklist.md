# Go-live checklist

## Hạ tầng
- [ ] DNS + TLS: meet / livekit / turn .meetly.example.com xanh (cert-manager)
- [ ] Managed Postgres: backup tự động bật, connection pool đủ (HPA max 10 pod × pool 10)
- [ ] Managed Redis: dùng chung livekit + egress + api, eviction policy `noeviction`
      (Redis là kênh chat pub/sub và routing LiveKit — mất key = mất tin nhắn)
- [ ] S3 bucket prod + lifecycle policy (xóa/glacier sau N ngày theo chính sách công ty)
- [ ] Node pool livekit: UDP 50000-60000 + TCP 7881 mở, public IP, autoscale min 2
- [ ] Node pool egress riêng, đủ core cho số bản ghi đồng thời (~4 core/bản ghi)
- [ ] coturn: test từ mạng chặn UDP (tethering + firewall) vào được phòng,
      `chrome://webrtc-internals` thấy candidate `relay`

## Bảo mật
- [ ] Toàn bộ secret trong Secrets Manager, ExternalSecret `SecretSynced`, repo sạch secret
- [ ] LiveKit API key prod ≠ staging ≠ dev; đã có lịch rotation
- [ ] Rate limit ingress cho `/api/v1/auth/**` (annotation `nginx.ingress.kubernetes.io/limit-rps`)
- [ ] Webhook signature verify hoạt động (gửi payload sai chữ ký → 401)
- [ ] `MEETLY_CORS_ALLOWED_ORIGINS` trỏ đúng domain prod (thiếu → app fail-fast lúc boot,
      sai → WS handshake bị từ chối)
- [ ] `MEETLY_AUTH_COOKIE_SECURE=true` (refresh cookie chỉ đi qua HTTPS)
- [ ] Kiểm chứng token ATTENDEE thật sự không publish được (mở webinar prod bằng
      tài khoản thường, xác nhận không có nút mic/cam)

## Vận hành
- [ ] Alerts nối Slack/PagerDuty (Alertmanager receiver)
- [ ] Dashboard Meetly Overview + LiveKit hiển thị dữ liệu thật
- [ ] Load test đạt ngưỡng, số liệu đã ghi vào `load-test.md`, alert capacity chỉnh theo
- [ ] Chạy thử `helm rollback` trên staging thành công
- [ ] Xác nhận migration Flyway của release là backward-compatible (chỉ thêm cột/bảng),
      vì rollback Helm KHÔNG rollback DB

### Runbook sự cố nhanh

| Sự cố | Ảnh hưởng | Xử lý |
|---|---|---|
| Redis chết | Chat tê liệt (relay pub/sub), LiveKit mất routing đa node; **media 1 node vẫn chạy** | Failover managed Redis; API tự nối lại |
| Egress chết | Recording fail (`egress_ended` → FAILED), họp không ảnh hưởng | Scale lại node pool egress; báo host ghi lại |
| Node LiveKit chết | Phòng trên node đó **đứt**; client tự reconnect sang node khác (multi-node routing), phòng dựng lại | Kiểm tra autoscale; hậu kiểm số phòng/ node |
| Postgres chết | API 5xx toàn bộ | Failover RDS; alert 5xx sẽ nổ trước |

## Sản phẩm
- [ ] Smoke test prod: 2 người họp, webinar guest, promote, chat, recording, xem lại
- [ ] Trang lỗi/timeout FE thân thiện khi BE bảo trì
