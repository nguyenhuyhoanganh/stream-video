# Meetly Phase 4 (Production-Ready Ops) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy được lên K8s: image build tự động, Helm chart cho api/web, CI chạy test theo path, CD staging tự động + prod có cổng duyệt tay, monitoring Prometheus/Grafana + alerts, coturn cho firewall khắt khe, load test có số liệu trước go-live. (Spec mục 6, phase 4 mục 8.)

**Architecture:** GitHub Actions → GHCR images (tag = git sha) → Helm chart `ops/helm/meetly` (api + web + ingress + HPA). LiveKit/Egress/Redis cài bằng chart chính chủ với values riêng. Postgres/Redis production dùng managed service (spec D6) — chart chỉ nhận connection string qua Secret. Secrets qua External Secrets Operator.

**Tech Stack:** Docker multi-stage, Helm 3, GitHub Actions, kube-prometheus-stack, Trivy, External Secrets Operator, coturn, livekit-cli.

**Prerequisite:** Phase 1–3 hoàn thành. Cần sẵn: cluster K8s staging/prod (EKS/GKE), domain (thay `meetly.example.com` bằng domain thật), GHCR hoặc registry tương đương, các secret GitHub Actions: `KUBECONFIG_STAGING`, `KUBECONFIG_PROD`.

## Global Constraints

- Kế thừa Global Constraints Phase 1–3.
- Image: `ghcr.io/<org>/meetly-api` và `ghcr.io/<org>/meetly-web`, tag = git sha đầy đủ; không dùng tag `latest` trong deploy.
- Build context Docker = **repo root** (`-f ops/docker/Dockerfile.api .`).
- Container non-root; probe: liveness/readiness = `/actuator/health/liveness|readiness`.
- Flyway migration forward-only, backward-compatible trong 1 release (spec 6.3). Rollback = `helm rollback` (không rollback DB).
- Repo không chứa secret thật — mọi secret production qua External Secrets Operator (spec 6.3/6.5).
- Namespaces: `meetly-staging`, `meetly-prod`; LiveKit ở namespace `livekit` (node pool riêng — spec 6.2).

---

### Task 1: Dockerfiles + .dockerignore

**Files:**
- Create: `ops/docker/Dockerfile.api`, `ops/docker/Dockerfile.web`, `ops/docker/nginx.conf`, `.dockerignore`

**Interfaces:**
- Produces: 2 image build được từ repo root; web nginx phục vụ SPA (fallback `index.html`), KHÔNG proxy API (ingress lo routing).

- [ ] **Step 1: `.dockerignore`** (repo root)

```
**/node_modules
**/dist
**/target
**/.git
**/playwright-report
**/test-results
docs
```

- [ ] **Step 2: `ops/docker/Dockerfile.api`**

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S meetly && adduser -S meetly -G meetly
USER meetly
WORKDIR /app
COPY --from=build /app/target/meetly-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

- [ ] **Step 3: `ops/docker/nginx.conf`**

```nginx
server {
    listen 8080;
    root /usr/share/nginx/html;
    index index.html;

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # cache tài nguyên build có hash trong tên file
    location /assets/ {
        add_header Cache-Control "public, max-age=31536000, immutable";
    }

    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
}
```

- [ ] **Step 4: `ops/docker/Dockerfile.web`**

```dockerfile
# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY ops/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
```

- [ ] **Step 5: Build verify + commit**

Run (repo root):
```bash
docker build -f ops/docker/Dockerfile.api -t meetly-api:local . \
  && docker build -f ops/docker/Dockerfile.web -t meetly-web:local .
```
Expected: cả 2 build SUCCESS.

Run: `docker run --rm -d -p 18080:8080 --name webtest meetly-web:local && sleep 2 && curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/ && docker rm -f webtest`
Expected: `200`.

```bash
git add .dockerignore ops/docker/
git commit -m "chore(ops): production dockerfiles for api and web"
```

---

### Task 2: BE bổ sung cho K8s (probes + prometheus metrics)

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `/actuator/health/liveness`, `/actuator/health/readiness` bật; `/actuator/prometheus` xuất metrics (Task 7 scrape).

- [ ] **Step 1: Thêm dependency**

```xml
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId><scope>runtime</scope></dependency>
```

- [ ] **Step 2: Thêm vào `application.yml`** (khối `management:` — thay khối cũ)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

- [ ] **Step 3: Verify + commit**

Run: `cd backend && ./mvnw -q spring-boot:run &` … chờ start … `curl -s http://localhost:8080/actuator/health/readiness`
Expected: `{"status":"UP"}`; `curl -s http://localhost:8080/actuator/prometheus | head -5` có metrics. Dừng app.

```bash
git add backend/
git commit -m "feat(be): k8s probes + prometheus metrics endpoint"
```

---

### Task 3: Helm chart `meetly`

**Files:**
- Create: `ops/helm/meetly/Chart.yaml`, `values.yaml`, `values-staging.yaml`, `values-prod.yaml`
- Create: `ops/helm/meetly/templates/_helpers.tpl`, `api-deployment.yaml`, `api-service.yaml`, `api-hpa.yaml`, `web-deployment.yaml`, `web-service.yaml`, `ingress.yaml`

**Interfaces:**
- Produces: `helm install meetly ops/helm/meetly -f values-<env>.yaml` dựng api (HPA 2→10) + web + ingress. Secret `meetly-api-secrets` (Task 8 tạo qua ESO) chứa: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`, `MEETLY_AUTH_JWT_SECRET`, `MEETLY_LIVEKIT_API_KEY`, `MEETLY_LIVEKIT_API_SECRET`, `MEETLY_STORAGE_ACCESS_KEY`, `MEETLY_STORAGE_SECRET_KEY`.

- [ ] **Step 1: `Chart.yaml` + `values.yaml`**

`Chart.yaml`:

```yaml
apiVersion: v2
name: meetly
description: Meetly video conferencing (api + web)
type: application
version: 0.1.0
appVersion: "0.1.0"
```

`values.yaml`:

```yaml
image:
  registry: ghcr.io/CHANGEME-org
  apiRepository: meetly-api
  webRepository: meetly-web
  tag: "latest-sha"          # CD ghi đè --set image.tag=$GITHUB_SHA

api:
  replicas: 2
  resources:
    requests: { cpu: 250m, memory: 512Mi }
    limits: { cpu: "1", memory: 1Gi }
  hpa:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70
  env:                        # config KHÔNG nhạy cảm
    MEETLY_LIVEKIT_WS_URL: wss://livekit.meetly.example.com
    MEETLY_LIVEKIT_HTTP_URL: https://livekit.meetly.example.com
    MEETLY_STORAGE_ENDPOINT: https://s3.ap-southeast-1.amazonaws.com
    MEETLY_STORAGE_REGION: ap-southeast-1
    MEETLY_STORAGE_BUCKET: meetly-recordings
    MEETLY_AUTH_COOKIE_SECURE: "true"
    MEETLY_CORS_ALLOWED_ORIGINS: https://meet.meetly.example.com
  existingSecret: meetly-api-secrets

web:
  replicas: 2
  resources:
    requests: { cpu: 50m, memory: 64Mi }
    limits: { cpu: 200m, memory: 128Mi }

ingress:
  className: nginx
  host: meet.meetly.example.com
  tlsSecret: meetly-tls
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"   # WS chat sống lâu
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
```

- [ ] **Step 2: Templates**

`templates/_helpers.tpl`:

```yaml
{{- define "meetly.apiImage" -}}
{{ .Values.image.registry }}/{{ .Values.image.apiRepository }}:{{ .Values.image.tag }}
{{- end -}}
{{- define "meetly.webImage" -}}
{{ .Values.image.registry }}/{{ .Values.image.webRepository }}:{{ .Values.image.tag }}
{{- end -}}
```

`templates/api-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: meetly-api
  labels: { app: meetly-api }
spec:
  {{- if not .Values.api.hpa.enabled }}
  replicas: {{ .Values.api.replicas }}
  {{- end }}
  selector:
    matchLabels: { app: meetly-api }
  template:
    metadata:
      labels: { app: meetly-api }
    spec:
      securityContext:
        runAsNonRoot: true
      containers:
        - name: api
          image: {{ include "meetly.apiImage" . }}
          ports: [{ containerPort: 8080 }]
          envFrom:
            - secretRef: { name: {{ .Values.api.existingSecret }} }
          env:
            {{- range $k, $v := .Values.api.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 30
          resources: {{- toYaml .Values.api.resources | nindent 12 }}
```

`templates/api-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: meetly-api
  labels: { app: meetly-api }
spec:
  selector: { app: meetly-api }
  ports:
    - name: http
      port: 80
      targetPort: 8080
```

`templates/api-hpa.yaml`:

```yaml
{{- if .Values.api.hpa.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: meetly-api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: meetly-api
  minReplicas: {{ .Values.api.hpa.minReplicas }}
  maxReplicas: {{ .Values.api.hpa.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.api.hpa.targetCPUUtilizationPercentage }}
{{- end }}
```

`templates/web-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: meetly-web
  labels: { app: meetly-web }
spec:
  replicas: {{ .Values.web.replicas }}
  selector:
    matchLabels: { app: meetly-web }
  template:
    metadata:
      labels: { app: meetly-web }
    spec:
      securityContext:
        runAsNonRoot: true
      containers:
        - name: web
          image: {{ include "meetly.webImage" . }}
          ports: [{ containerPort: 8080 }]
          readinessProbe:
            httpGet: { path: /, port: 8080 }
          resources: {{- toYaml .Values.web.resources | nindent 12 }}
```

`templates/web-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: meetly-web
  labels: { app: meetly-web }
spec:
  selector: { app: meetly-web }
  ports:
    - name: http
      port: 80
      targetPort: 8080
```

`templates/ingress.yaml`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: meetly
  annotations: {{- toYaml .Values.ingress.annotations | nindent 4 }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  tls:
    - hosts: [{{ .Values.ingress.host }}]
      secretName: {{ .Values.ingress.tlsSecret }}
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend: { service: { name: meetly-api, port: { number: 80 } } }
          - path: /ws
            pathType: Prefix
            backend: { service: { name: meetly-api, port: { number: 80 } } }
          # /actuator KHÔNG expose ra ingress: probes gọi thẳng pod,
          # Prometheus scrape qua ServiceMonitor nội bộ (Task 7)
          - path: /
            pathType: Prefix
            backend: { service: { name: meetly-web, port: { number: 80 } } }
```

- [ ] **Step 3: `values-staging.yaml` / `values-prod.yaml`**

`values-staging.yaml`:

```yaml
ingress:
  host: meet-staging.meetly.example.com
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-staging
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
api:
  hpa: { enabled: true, minReplicas: 1, maxReplicas: 3, targetCPUUtilizationPercentage: 70 }
  env:
    MEETLY_LIVEKIT_WS_URL: wss://livekit-staging.meetly.example.com
    MEETLY_LIVEKIT_HTTP_URL: https://livekit-staging.meetly.example.com
    MEETLY_STORAGE_ENDPOINT: https://s3.ap-southeast-1.amazonaws.com
    MEETLY_STORAGE_REGION: ap-southeast-1
    MEETLY_STORAGE_BUCKET: meetly-recordings-staging
    MEETLY_AUTH_COOKIE_SECURE: "true"
    MEETLY_CORS_ALLOWED_ORIGINS: https://meet-staging.meetly.example.com
web:
  replicas: 1
```

`values-prod.yaml`: giữ mặc định `values.yaml` (đã là prod), chỉ override nếu cần — tạo file rỗng kèm comment:

```yaml
# Prod dùng values.yaml mặc định. Override tại đây khi cần (vd tăng maxReplicas).
```

- [ ] **Step 4: Lint + render verify, commit**

Run: `helm lint ops/helm/meetly && helm template meetly ops/helm/meetly -f ops/helm/meetly/values-staging.yaml > /dev/null && echo OK`
Expected: `1 chart(s) linted, 0 chart(s) failed` + `OK`.

```bash
git add ops/helm/
git commit -m "chore(ops): helm chart for api + web with hpa and ingress"
```

---

### Task 4: CI workflow (PR)

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: PR nào đụng `backend/**` chạy `mvn verify` (Testcontainers), đụng `frontend/**` chạy lint + test + build. Bắt buộc pass mới merge (cấu hình branch protection trên GitHub — làm tay, ghi trong README).

- [ ] **Step 1: Viết `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  pull_request:
    branches: [main]

jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      backend: ${{ steps.filter.outputs.backend }}
      frontend: ${{ steps.filter.outputs.frontend }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          filters: |
            backend: ['backend/**', 'ops/docker/Dockerfile.api']
            frontend: ['frontend/**', 'ops/docker/Dockerfile.web']

  backend:
    needs: changes
    if: needs.changes.outputs.backend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - name: Test
        working-directory: backend
        run: ./mvnw -B verify

  frontend:
    needs: changes
    if: needs.changes.outputs.frontend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - name: Install
        working-directory: frontend
        run: npm ci
      - name: Lint + typecheck + test + build
        working-directory: frontend
        run: |
          npx eslint src
          npx tsc --noEmit
          npm test
          npm run build
```

- [ ] **Step 2: Commit** (verify thật xảy ra khi mở PR đầu tiên)

```bash
git add .github/
git commit -m "ci: pr workflow with path-filtered be/fe jobs"
```

---

### Task 5: CD workflow (build → staging → gate → prod)

**Files:**
- Create: `.github/workflows/cd.yml`

**Interfaces:**
- Produces: push lên `main` → build 2 images (tag = sha) + Trivy scan → deploy `meetly-staging` → smoke e2e → chờ approve (GitHub environment `production` có required reviewers — cấu hình tay) → deploy `meetly-prod`. Cần secrets: `KUBECONFIG_STAGING`, `KUBECONFIG_PROD` (base64 kubeconfig).

- [ ] **Step 1: Viết `.github/workflows/cd.yml`**

```yaml
name: CD

on:
  push:
    branches: [main]

env:
  REGISTRY: ghcr.io/${{ github.repository_owner }}

jobs:
  build-push:
    runs-on: ubuntu-latest
    permissions: { contents: read, packages: write }
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build & push api
        uses: docker/build-push-action@v6
        with:
          context: .
          file: ops/docker/Dockerfile.api
          push: true
          tags: ${{ env.REGISTRY }}/meetly-api:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
      - name: Build & push web
        uses: docker/build-push-action@v6
        with:
          context: .
          file: ops/docker/Dockerfile.web
          push: true
          tags: ${{ env.REGISTRY }}/meetly-web:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
      - name: Trivy scan api image
        uses: aquasecurity/trivy-action@0.28.0
        with:
          image-ref: ${{ env.REGISTRY }}/meetly-api:${{ github.sha }}
          severity: CRITICAL,HIGH
          exit-code: '1'
          ignore-unfixed: true

  deploy-staging:
    needs: build-push
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - name: Kubeconfig
        run: |
          mkdir -p ~/.kube
          echo "${{ secrets.KUBECONFIG_STAGING }}" | base64 -d > ~/.kube/config
      - name: Helm upgrade staging
        run: |
          helm upgrade --install meetly ops/helm/meetly \
            -n meetly-staging --create-namespace \
            -f ops/helm/meetly/values-staging.yaml \
            --set image.registry=${{ env.REGISTRY }} \
            --set image.tag=${{ github.sha }} \
            --wait --timeout 5m
      - name: Smoke check
        run: |
          kubectl -n meetly-staging rollout status deploy/meetly-api --timeout=120s
          READY=$(kubectl -n meetly-staging get deploy meetly-api -o jsonpath='{.status.readyReplicas}')
          test "$READY" -ge 1

  smoke-e2e:
    needs: deploy-staging
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22' }
      - name: Playwright smoke trên staging
        working-directory: frontend
        env:
          E2E_BASE_URL: https://meet-staging.meetly.example.com
        run: |
          npm ci
          npx playwright install chromium
          npx playwright test e2e/two-users-meet.spec.ts

  deploy-prod:
    needs: smoke-e2e
    runs-on: ubuntu-latest
    environment: production   # đặt required reviewers trong GitHub Settings
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - name: Kubeconfig
        run: |
          mkdir -p ~/.kube
          echo "${{ secrets.KUBECONFIG_PROD }}" | base64 -d > ~/.kube/config
      - name: Helm upgrade prod
        run: |
          helm upgrade --install meetly ops/helm/meetly \
            -n meetly-prod --create-namespace \
            -f ops/helm/meetly/values-prod.yaml \
            --set image.registry=${{ env.REGISTRY }} \
            --set image.tag=${{ github.sha }} \
            --wait --timeout 5m
```

- [ ] **Step 2: Sửa `frontend/playwright.config.ts`** hỗ trợ baseURL từ env:

```ts
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
```

- [ ] **Step 3: Commit**

```bash
git add .github/ frontend/playwright.config.ts
git commit -m "ci: cd pipeline build->staging->gate->prod"
```

---

### Task 6: LiveKit + Egress production values

**Files:**
- Create: `ops/helm/livekit/values-livekit-prod.yaml`, `ops/helm/livekit/values-egress-prod.yaml`, `ops/helm/livekit/README.md`

**Interfaces:**
- Produces: values + lệnh cài chart chính chủ. LiveKit node pool riêng label `role=livekit` có public IP, hostNetwork; Egress node pool `role=egress`. Keys production KHÔNG nằm trong file — inject qua `--set` từ secret manager khi chạy lệnh (ghi trong README).

- [ ] **Step 1: `values-livekit-prod.yaml`**

```yaml
replicaCount: 2

livekit:
  port: 7880
  rtc:
    tcp_port: 7881
    port_range_start: 50000
    port_range_end: 60000
    use_external_ip: true
  redis:
    address: "CHANGEME-redis-host:6379"   # managed redis, cùng redis với egress
  # keys: inject khi install:
  #   --set livekit.keys.<API_KEY>=<API_SECRET>
  turn:
    enabled: false   # dùng coturn riêng (Task 9)
  prometheus:
    port: 6789

nodeSelector:
  role: livekit
tolerations:
  - key: dedicated
    value: livekit
    effect: NoSchedule

# hostNetwork để UDP đi thẳng vào node public IP
hostNetwork: true

loadBalancer:
  type: disable   # media không đi qua LB; chỉ signalling ws đi qua ingress riêng
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: livekit.meetly.example.com
      paths: ["/"]
  tls:
    - secretName: livekit-tls
      hosts: [livekit.meetly.example.com]
```

- [ ] **Step 2: `values-egress-prod.yaml`**

```yaml
replicaCount: 2

egress:
  redis:
    address: "CHANGEME-redis-host:6379"
  # api_key/api_secret inject khi install (trùng key của livekit server)
  prometheus_port: 6790

nodeSelector:
  role: egress
tolerations:
  - key: dedicated
    value: egress
    effect: NoSchedule

resources:
  requests: { cpu: "2", memory: 4Gi }
  limits: { cpu: "4", memory: 8Gi }
```

- [ ] **Step 3: `ops/helm/livekit/README.md`**

```markdown
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
```

*Lưu ý: tên field values theo chart chính chủ tại thời điểm cài — đối chiếu `helm show values livekit/livekit-server` trước khi apply; hợp đồng cần giữ: redis chung với egress, hostNetwork + node pool riêng, webhook trỏ về meetly-api.*

- [ ] **Step 4: Commit**

```bash
git add ops/helm/livekit/
git commit -m "chore(ops): livekit + egress production values"
```

---

### Task 7: Monitoring (ServiceMonitor + alerts + dashboard)

**Files:**
- Create: `ops/helm/meetly/templates/api-servicemonitor.yaml`, `ops/helm/meetly/templates/prometheusrule.yaml`
- Create: `ops/monitoring/grafana-dashboard-meetly.json`, `ops/monitoring/README.md`
- Modify: `ops/helm/meetly/values.yaml` (khối `monitoring`)

**Interfaces:**
- Produces: khi cluster có kube-prometheus-stack, chart meetly tự đăng ký scrape `/actuator/prometheus` + 4 alert cơ bản; dashboard JSON import tay vào Grafana (hoặc mount ConfigMap có label `grafana_dashboard=1`).

- [ ] **Step 1: Thêm vào `values.yaml`**

```yaml
monitoring:
  enabled: true
  serviceMonitorLabels:
    release: kube-prometheus-stack
```

- [ ] **Step 2: `templates/api-servicemonitor.yaml`**

```yaml
{{- if .Values.monitoring.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: meetly-api
  labels: {{- toYaml .Values.monitoring.serviceMonitorLabels | nindent 4 }}
spec:
  selector:
    matchLabels: { app: meetly-api }
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
{{- end }}
```

- [ ] **Step 3: `templates/prometheusrule.yaml`**

```yaml
{{- if .Values.monitoring.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: meetly-alerts
  labels: {{- toYaml .Values.monitoring.serviceMonitorLabels | nindent 4 }}
spec:
  groups:
    - name: meetly
      rules:
        - alert: MeetlyApiHigh5xxRate
          expr: >
            sum(rate(http_server_requests_seconds_count{status=~"5..", job=~".*meetly-api.*"}[5m]))
            / sum(rate(http_server_requests_seconds_count{job=~".*meetly-api.*"}[5m])) > 0.05
          for: 5m
          labels: { severity: critical }
          annotations:
            summary: "meetly-api 5xx > 5% trong 5 phút"
        - alert: MeetlyApiHighLatency
          expr: >
            histogram_quantile(0.95,
              sum(rate(http_server_requests_seconds_bucket{job=~".*meetly-api.*"}[5m])) by (le)) > 1
          for: 10m
          labels: { severity: warning }
          annotations:
            summary: "meetly-api p95 latency > 1s"
        - alert: MeetlyApiPodRestarting
          expr: increase(kube_pod_container_status_restarts_total{container="api"}[15m]) > 2
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "meetly-api pod restart liên tục"
        - alert: LiveKitNodeHighParticipants
          expr: livekit_participant_total > 400
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "LiveKit node gần ngưỡng capacity (điều chỉnh sau load test)"
{{- end }}
```

- [ ] **Step 4: `ops/monitoring/grafana-dashboard-meetly.json`** (dashboard 4 panel; import tay vào Grafana)

```json
{
  "title": "Meetly Overview",
  "uid": "meetly-overview",
  "timezone": "browser",
  "panels": [
    {
      "title": "API p95 latency", "type": "timeseries",
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0},
      "targets": [{"expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job=~\".*meetly-api.*\"}[5m])) by (le))"}]
    },
    {
      "title": "API request rate theo status", "type": "timeseries",
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0},
      "targets": [{"expr": "sum(rate(http_server_requests_seconds_count{job=~\".*meetly-api.*\"}[5m])) by (status)"}]
    },
    {
      "title": "LiveKit participants / rooms", "type": "timeseries",
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 8},
      "targets": [
        {"expr": "livekit_participant_total", "legendFormat": "participants"},
        {"expr": "livekit_room_total", "legendFormat": "rooms"}
      ]
    },
    {
      "title": "JVM heap used", "type": "timeseries",
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 8},
      "targets": [{"expr": "sum(jvm_memory_used_bytes{area=\"heap\", job=~\".*meetly-api.*\"}) by (pod)"}]
    }
  ],
  "schemaVersion": 39,
  "version": 1
}
```

`ops/monitoring/README.md`:

```markdown
# Monitoring

1. Cài stack: `helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring --create-namespace`
2. Chart meetly tự tạo ServiceMonitor + PrometheusRule (values `monitoring.enabled=true`,
   label `release: kube-prometheus-stack` phải khớp tên release ở bước 1).
3. Grafana: import `grafana-dashboard-meetly.json`.
4. LiveKit metrics: chart livekit đã bật prometheus port — thêm ServiceMonitor tương tự
   nếu chart không tự tạo (đối chiếu `helm show values livekit/livekit-server`).
5. Logs: cài loki-stack (`grafana/loki-stack`) khi cần — logback đã xuất JSON (xem Task 8).
```

- [ ] **Step 5: Lint + commit**

Run: `helm lint ops/helm/meetly && helm template meetly ops/helm/meetly > /dev/null && echo OK`
Expected: OK.

```bash
git add ops/
git commit -m "chore(ops): servicemonitor, alerts, grafana dashboard"
```

---

### Task 8: JSON logs + External Secrets

**Files:**
- Modify: `backend/pom.xml`, Create: `backend/src/main/resources/logback-spring.xml`
- Create: `ops/k8s/external-secrets/secretstore.yaml`, `ops/k8s/external-secrets/meetly-api-externalsecret.yaml`, `ops/k8s/external-secrets/README.md`

**Interfaces:**
- Produces: profile `prod` log JSON một dòng (Loki/ELK ăn được); ExternalSecret sinh secret `meetly-api-secrets` (đúng tên Task 3 tham chiếu) từ AWS Secrets Manager.

- [ ] **Step 1: Logback JSON** — thêm dependency:

```xml
    <dependency><groupId>net.logstash.logback</groupId><artifactId>logstash-logback-encoder</artifactId><version>8.0</version></dependency>
```

`backend/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <springProfile name="!prod">
    <include resource="org/springframework/boot/logging/logback/base.xml"/>
  </springProfile>
  <springProfile name="prod">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <customFields>{"app":"meetly-api"}</customFields>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON"/>
    </root>
  </springProfile>
</configuration>
```

(Helm values thêm env `SPRING_PROFILES_ACTIVE: prod` vào `api.env` trong `values.yaml`.)

- [ ] **Step 2: External Secrets manifests**

`ops/k8s/external-secrets/secretstore.yaml`:

```yaml
apiVersion: external-secrets.io/v1
kind: ClusterSecretStore
metadata:
  name: aws-secrets
spec:
  provider:
    aws:
      service: SecretsManager
      region: ap-southeast-1
      auth:
        jwt:
          serviceAccountRef:
            name: external-secrets-sa
            namespace: external-secrets
```

`ops/k8s/external-secrets/meetly-api-externalsecret.yaml`:

```yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: meetly-api-secrets
  namespace: meetly-prod
spec:
  refreshInterval: 1h
  secretStoreRef: { kind: ClusterSecretStore, name: aws-secrets }
  target: { name: meetly-api-secrets }
  data:
    - secretKey: SPRING_DATASOURCE_URL
      remoteRef: { key: meetly/prod/db, property: url }
    - secretKey: SPRING_DATASOURCE_USERNAME
      remoteRef: { key: meetly/prod/db, property: username }
    - secretKey: SPRING_DATASOURCE_PASSWORD
      remoteRef: { key: meetly/prod/db, property: password }
    - secretKey: SPRING_DATA_REDIS_HOST
      remoteRef: { key: meetly/prod/redis, property: host }
    - secretKey: MEETLY_AUTH_JWT_SECRET
      remoteRef: { key: meetly/prod/app, property: jwt_secret }
    - secretKey: MEETLY_LIVEKIT_API_KEY
      remoteRef: { key: meetly/prod/livekit, property: api_key }
    - secretKey: MEETLY_LIVEKIT_API_SECRET
      remoteRef: { key: meetly/prod/livekit, property: api_secret }
    - secretKey: MEETLY_STORAGE_ACCESS_KEY
      remoteRef: { key: meetly/prod/s3, property: access_key }
    - secretKey: MEETLY_STORAGE_SECRET_KEY
      remoteRef: { key: meetly/prod/s3, property: secret_key }
```

`README.md`: ghi lệnh cài ESO (`helm upgrade --install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace`), tạo secrets trong AWS SM theo key trên, và nhân bản ExternalSecret cho `meetly-staging`.

- [ ] **Step 3: Verify + commit**

Run: `cd backend && ./mvnw -q test` (logback không phá test — profile test dùng logging thường)
Expected: PASS.

```bash
git add backend/ ops/k8s/
git commit -m "chore(ops): json logging + external secrets manifests"
```

---

### Task 9: coturn

**Files:**
- Create: `ops/k8s/coturn/coturn-configmap.yaml`, `coturn-deployment.yaml`, `coturn-service.yaml`, `README.md`

**Interfaces:**
- Produces: coturn chạy `turns:443` (TLS) + `turn:3478` UDP/TCP, static-auth-secret; LiveKit values thêm `rtc.turn_servers` trỏ tới nó (hoặc client cấu hình qua LiveKit — LiveKit server trả TURN cho client khi cấu hình trong livekit values `rtc.turn_servers`).

- [ ] **Step 1: Manifests**

`coturn-configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: coturn-config
  namespace: livekit
data:
  turnserver.conf: |
    listening-port=3478
    tls-listening-port=443
    realm=turn.meetly.example.com
    use-auth-secret
    # static-auth-secret inject qua env TURN_SECRET (script entrypoint nối vào)
    no-cli
    no-loopback-peers
    no-multicast-peers
    min-port=49152
    max-port=65535
    cert=/certs/tls.crt
    pkey=/certs/tls.key
```

`coturn-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: coturn
  namespace: livekit
spec:
  replicas: 1
  selector:
    matchLabels: { app: coturn }
  template:
    metadata:
      labels: { app: coturn }
    spec:
      nodeSelector: { role: livekit }
      hostNetwork: true
      containers:
        - name: coturn
          image: coturn/coturn:4.6-alpine
          args:
            - -c
            - /etc/coturn/turnserver.conf
            - --static-auth-secret=$(TURN_SECRET)
          env:
            - name: TURN_SECRET
              valueFrom:
                secretKeyRef: { name: coturn-secret, key: static-auth-secret }
          volumeMounts:
            - { name: config, mountPath: /etc/coturn }
            - { name: certs, mountPath: /certs }
      volumes:
        - name: config
          configMap: { name: coturn-config }
        - name: certs
          secret: { secretName: turn-tls }   # cert-manager Certificate cho turn.meetly.example.com
```

`coturn-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: coturn
  namespace: livekit
spec:
  type: LoadBalancer
  selector: { app: coturn }
  ports:
    - { name: turns-tcp, port: 443, targetPort: 443, protocol: TCP }
    - { name: turn-udp, port: 3478, targetPort: 3478, protocol: UDP }
    - { name: turn-tcp, port: 3478, targetPort: 3478, protocol: TCP }
```

`README.md`: DNS `turn.meetly.example.com` → LB; tạo secret `coturn-secret`; thêm vào values LiveKit:

```yaml
livekit:
  rtc:
    turn_servers:
      - host: turn.meetly.example.com
        port: 443
        protocol: tls
        username: ""            # dùng auth-secret: LiveKit tự sinh credential
        credential: ""          # đặt static-auth-secret trùng coturn-secret
```

*(đối chiếu format `turn_servers` với docs LiveKit khi apply.)*

- [ ] **Step 2: Verify render + commit**

Run: `kubectl apply --dry-run=client -f ops/k8s/coturn/ && echo OK` (cần kubectl; nếu chưa có cluster, chỉ cần YAML parse OK)
Expected: OK (3 resources).

```bash
git add ops/k8s/coturn/
git commit -m "chore(ops): coturn manifests for restrictive firewalls"
```

---

### Task 10: Load test + go-live runbook

**Files:**
- Create: `docs/runbooks/load-test.md`, `docs/runbooks/go-live-checklist.md`

- [ ] **Step 1: `docs/runbooks/load-test.md`**

```markdown
# Load test LiveKit (trước go-live)

Cài: `brew install livekit-cli` (hoặc tải từ GitHub releases).

## Kịch bản webinar 100+ (khớp spec: vài speaker, đông viewer)

```bash
export LIVEKIT_URL=wss://livekit-staging.meetly.example.com
export LIVEKIT_API_KEY=...     # từ secret manager
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

## Đo gì (Grafana trong lúc chạy)

| Metric | Ngưỡng đạt |
|---|---|
| CPU node livekit | < 70% ở tải mục tiêu |
| `livekit_participant_total` / node | ghi lại con số node chịu được |
| Packet loss (livekit metrics) | < 2% |
| API p95 (join storm: chạy script join 100 user/30s) | < 500ms |

## Kết quả → hành động
- Ghi số participant/node đạt được vào bảng dưới, chỉnh alert
  `LiveKitNodeHighParticipants` (Task 7) = 80% con số đó.
- Nếu 1 node không đủ cho phòng mục tiêu → tăng cỡ máy node pool
  (LiveKit: 1 phòng nằm trọn 1 node; multi-node giúp NHIỀU phòng, không chia 1 phòng).

| Ngày test | Cỡ máy | Participants đạt | Packet loss | Ghi chú |
|---|---|---|---|---|
| (điền sau khi chạy) | | | | |
```

- [ ] **Step 2: `docs/runbooks/go-live-checklist.md`**

```markdown
# Go-live checklist

## Hạ tầng
- [ ] DNS + TLS: meet / livekit / turn .meetly.example.com xanh (cert-manager)
- [ ] Managed Postgres: backup tự động bật, connection pool đủ (HPA max 10 pod × pool 10)
- [ ] Managed Redis: dùng chung livekit + egress + api, eviction policy noeviction
- [ ] S3 bucket prod + lifecycle policy (xóa/glacier sau N ngày theo chính sách công ty)
- [ ] Node pool livekit: UDP 50000-60000 + TCP 7881 mở, public IP, autoscale min 2
- [ ] coturn: test từ mạng chặn UDP (tethering + firewall) vào được phòng

## Bảo mật
- [ ] Toàn bộ secret trong Secrets Manager, ExternalSecret sync OK, không secret nào trong repo
- [ ] LiveKit API key prod ≠ staging ≠ dev; đã có lịch rotation
- [ ] Rate limit ingress cho /api/v1/auth/** (nginx annotation limit-rps)
- [ ] Webhook signature verify hoạt động (gửi thử payload sai → 401)

## Vận hành
- [ ] Alerts nối Slack/PagerDuty (Alertmanager receiver)
- [ ] Dashboard Meetly Overview + LiveKit hiển thị dữ liệu thật
- [ ] Load test đạt ngưỡng (runbook load-test.md có số liệu)
- [ ] Chạy thử helm rollback trên staging thành công
- [ ] Runbook sự cố: mất redis (chat tê liệt, media vẫn chạy), mất egress (recording fail),
      node livekit chết (LiveKit tự di dời phòng? — KHÔNG, phòng trên node chết sẽ đứt:
      client tự reconnect vào node khác nhờ multi-node routing)

## Sản phẩm
- [ ] Smoke test prod: 2 người họp, webinar guest, promote, chat, recording, xem lại
- [ ] Trang lỗi/timeout FE thân thiện khi BE bảo trì
```

- [ ] **Step 3: Commit**

```bash
git add docs/runbooks/
git commit -m "docs: load test + go-live runbooks"
```

---

## Definition of Done — Phase 4 (khớp spec mục 8)

- [ ] Merge main → staging tự deploy, smoke e2e xanh, prod deploy sau approve.
- [ ] `helm lint` + `helm template` sạch; images non-root, Trivy pass.
- [ ] Grafana thấy metrics API + LiveKit; 4 alerts active.
- [ ] Load test webinar 4 publisher + 150 subscriber pass ngưỡng, số liệu ghi vào runbook.
- [ ] Go-live checklist duyệt từng mục.
