# External Secrets (ESO)

Repo không chứa secret thật (spec 6.3/6.5) — mọi giá trị nhạy cảm nằm trong
AWS Secrets Manager, ESO đồng bộ xuống Secret `meetly-api-secrets` mà Helm chart
tham chiếu qua `api.existingSecret`.

## 1. Cài operator

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm upgrade --install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace \
  --set serviceAccount.name=external-secrets-sa
```

ServiceAccount `external-secrets-sa` cần IRSA (EKS) hoặc Workload Identity (GKE)
với quyền `secretsmanager:GetSecretValue` trên prefix `meetly/*`.

## 2. Tạo secrets trong AWS Secrets Manager

| Secret id | Properties |
|---|---|
| `meetly/prod/db` | `url`, `username`, `password` |
| `meetly/prod/redis` | `host` |
| `meetly/prod/app` | `jwt_secret` (≥ 32 ký tự) |
| `meetly/prod/livekit` | `api_key`, `api_secret` |
| `meetly/prod/s3` | `access_key`, `secret_key` |

```bash
aws secretsmanager create-secret --name meetly/prod/db \
  --secret-string '{"url":"jdbc:postgresql://<rds-host>:5432/meetly","username":"meetly","password":"<...>"}'
```

## 3. Apply

```bash
kubectl apply -f secretstore.yaml
kubectl apply -f meetly-api-externalsecret.yaml
# staging: đổi namespace + prefix key sang meetly/staging/*
sed -e 's/meetly-prod/meetly-staging/' -e 's|meetly/prod/|meetly/staging/|g' \
  meetly-api-externalsecret.yaml | kubectl apply -f -
```

Kiểm tra: `kubectl -n meetly-prod get externalsecret meetly-api-secrets` →
`STATUS=SecretSynced`, và `kubectl -n meetly-prod get secret meetly-api-secrets`
có đủ 9 key khớp danh sách trong `values.yaml` (`api.existingSecret`).

## Vì sao các key này

Tên key = biến môi trường Spring Boot đọc trực tiếp (relaxed binding):
`MEETLY_AUTH_JWT_SECRET` → `meetly.auth.jwt-secret`,
`MEETLY_STORAGE_SECRET_KEY` → `meetly.storage.secret-key`, v.v.
Deployment nạp cả Secret bằng `envFrom.secretRef` nên thêm key mới trong
Secrets Manager là app nhận được sau lần restart kế tiếp, không phải sửa chart.
