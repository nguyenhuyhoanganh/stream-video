# coturn — TURN relay cho firewall chặn UDP

Spec mục 9: user sau firewall doanh nghiệp chặn UDP vẫn phải vào họp được.
`turns:443` (TLS trên cổng 443) là đường cuối cùng luôn qua được, kèm LiveKit
TCP fallback 7881.

## Chuẩn bị

1. DNS `turn.meetly.example.com` → external IP của Service LoadBalancer.
2. Cert: cert-manager Certificate tên `turn-tls` cho domain trên (namespace `livekit`).
3. Secret auth:

```bash
kubectl -n livekit create secret generic coturn-secret \
  --from-literal=static-auth-secret="$(openssl rand -hex 32)"
```

## Apply

```bash
kubectl apply -f ops/k8s/coturn/
```

## Nối vào LiveKit

Thêm vào values LiveKit (`ops/helm/livekit/values-livekit-prod.yaml`) — LiveKit tự
sinh credential ngắn hạn từ static-auth-secret rồi trả cho client:

```yaml
livekit:
  rtc:
    turn_servers:
      - host: turn.meetly.example.com
        port: 443
        protocol: tls
        credential: "<static-auth-secret trùng coturn-secret>"
```

> Đối chiếu format `turn_servers` với docs LiveKit đúng version trước khi apply.

## Nghiệm thu (go-live checklist)

Test từ mạng chặn UDP thật: điện thoại phát 4G + firewall chặn UDP outbound,
hoặc `sudo iptables -A OUTPUT -p udp --dport 3478:60000 -j DROP` trên máy test →
vào phòng vẫn thấy/nghe được. Kiểm tra `chrome://webrtc-internals` thấy
candidate pair đang dùng là `relay`.
