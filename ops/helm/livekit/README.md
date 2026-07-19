# Cài LiveKit + Egress (production)

Node pools cần tạo trước (Terraform/eksctl):
- `role=livekit`: public IP, mở UDP 50000-60000 + TCP 7881 trong security group, taint `dedicated=livekit:NoSchedule`
- `role=egress`: máy CPU cao (>= 8 core), taint `dedicated=egress:NoSchedule`

```bash
helm repo add livekit https://helm.livekit.io
# API key/secret lấy từ secret manager, KHÔNG hardcode:
export LK_KEY=$(aws secretsmanager get-secret-value --secret-id meetly/livekit-key --query SecretString --output text)
export LK_SECRET=$(aws secretsmanager get-secret-value --secret-id meetly/livekit-secret --query SecretString --output text)

helm upgrade --install livekit livekit/livekit-server \
  -n livekit --create-namespace \
  -f values-livekit-prod.yaml \
  --set "livekit.keys.$LK_KEY=$LK_SECRET"

helm upgrade --install egress livekit/egress \
  -n livekit \
  -f values-egress-prod.yaml \
  --set "egress.api_key=$LK_KEY" --set "egress.api_secret=$LK_SECRET"
```

Sau khi cài: cấu hình webhook trong values livekit →
`livekit.webhook.urls[0]=https://meet.meetly.example.com/api/v1/livekit/webhook`,
`livekit.webhook.api_key=$LK_KEY`.

> Tên field values theo chart chính chủ tại thời điểm cài — đối chiếu
> `helm show values livekit/livekit-server` trước khi apply. Hợp đồng cần giữ:
> redis chung với egress, hostNetwork + node pool riêng, webhook trỏ về meetly-api.

## Khác biệt so với dev compose

Dev (`ops/compose/`) chạy egress ở `network_mode: host` vì LiveKit dev quảng bá
ICE `127.0.0.1` (`--node-ip 127.0.0.1`) nên container thường không nối được media.
Production KHÔNG cần thủ thuật đó: `use_external_ip: true` + hostNetwork khiến
LiveKit quảng bá IP public thật, egress ở pod riêng vẫn kết nối được.
