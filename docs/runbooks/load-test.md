# Load test LiveKit (trước go-live)

Cài `livekit-cli` (lệnh `lk`):

```bash
# Linux/macOS
curl -sSL https://get.livekit.io/cli | bash
# hoặc macOS: brew install livekit-cli
```

## Kịch bản webinar 100+ (khớp spec: vài speaker, đông viewer)

```bash
export LIVEKIT_URL=wss://livekit-staging.meetly.example.com
export LIVEKIT_API_KEY=...     # từ secret manager, KHÔNG commit
export LIVEKIT_API_SECRET=...

# 4 publisher (speaker) + 150 subscriber (attendee), video 720p simulcast
lk load-test \
  --url $LIVEKIT_URL --api-key $LIVEKIT_API_KEY --api-secret $LIVEKIT_API_SECRET \
  --room loadtest-webinar \
  --video-publishers 4 \
  --subscribers 150 \
  --duration 10m \
  --simulcast
```

> Cờ CLI đổi theo version — chạy `lk load-test --help` đối chiếu trước.
> Chạy load test từ máy **ngoài cluster** để đo cả đường mạng thật, và chạy vào
> **staging**, không bao giờ vào prod đang có người dùng.

## Đo gì (Grafana trong lúc chạy)

| Metric | Ngưỡng đạt |
|---|---|
| CPU node livekit | < 70% ở tải mục tiêu |
| `livekit_participant_total` / node | ghi lại con số node chịu được |
| Packet loss (livekit metrics) | < 2% |
| API p95 (join storm: chạy script join 100 user/30s) | < 500ms |

Dashboard "Meetly Overview" (`ops/monitoring/grafana-dashboard-meetly.json`) đã có
sẵn panel p95 latency và participants/rooms cho việc này.

## Kết quả → hành động

- Ghi số participant/node đạt được vào bảng dưới, chỉnh alert
  `LiveKitNodeHighParticipants` (`ops/helm/meetly/templates/prometheusrule.yaml`,
  hiện đặt tạm 400) = 80% con số đo được.
- Nếu 1 node không đủ cho phòng mục tiêu → tăng cỡ máy node pool.
  **Lưu ý kiến trúc:** LiveKit đặt trọn 1 phòng trên 1 node; multi-node giúp phục vụ
  NHIỀU phòng song song chứ không chia tải 1 phòng. Phòng lớn ⇒ node to hơn,
  không phải nhiều node hơn.
- Đo cả Egress: mỗi bản ghi đang encode tốn ~2–4 core (spec 6.2). Số phòng ghi
  đồng thời tối đa = (core node pool egress) / 4 → set giới hạn concurrent recording.

| Ngày test | Cỡ máy | Participants đạt | Packet loss | Ghi chú |
|---|---|---|---|---|
| (điền sau khi chạy) | | | | |
