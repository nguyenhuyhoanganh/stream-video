# Monitoring

1. Cài stack: `helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring --create-namespace`
2. Chart meetly tự tạo ServiceMonitor + PrometheusRule (values `monitoring.enabled=true`,
   label `release: kube-prometheus-stack` phải khớp tên release ở bước 1).
3. Grafana: import `grafana-dashboard-meetly.json`.
4. LiveKit metrics: chart livekit đã bật prometheus port — thêm ServiceMonitor tương tự
   nếu chart không tự tạo (đối chiếu `helm show values livekit/livekit-server`).
5. Logs: cài loki-stack (`grafana/loki-stack`) khi cần — logback đã xuất JSON kèm
   `correlationId` (xem Task 8) nên filter theo request rất nhanh.
6. Tên metric LiveKit (`livekit_participant_total`, `livekit_room_total`) có thể khác theo
   version — curl endpoint `/metrics` của livekit lấy tên thật rồi chỉnh alert + dashboard.

## Kiểm chứng nhanh trên dev

`/actuator/prometheus` của BE local đã xuất metric Micrometer:

```bash
curl -s localhost:8080/actuator/prometheus | grep http_server_requests_seconds_count | head
```

Metric `http_server_requests_seconds_*` là nguồn cho alert 5xx và p95 ở trên —
chỉ xuất hiện sau khi có ít nhất 1 request đi qua controller.
