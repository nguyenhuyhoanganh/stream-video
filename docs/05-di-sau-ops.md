# Meetly — Đi sâu Ops (đóng gói, triển khai, tự động hoá)

> "Ops" (operations — vận hành) là mọi thứ để đưa code từ máy anh lên chạy trên Internet cho
> người thật dùng, và giữ nó chạy ổn định. Đây là phần anh chưa quen, nên tài liệu này giải
> thích từ đầu, đi qua **file cấu hình thật** của dự án.
>
> Không cần biết trước Docker hay Kubernetes. Đọc kèm [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md).

---

## Mục lục

1. [Vấn đề Ops giải quyết](#1-vấn-đề-ops-giải-quyết)
2. [Docker: đóng gói ứng dụng](#2-docker-đóng-gói-ứng-dụng)
3. [Đọc Dockerfile thật, từng dòng](#3-đọc-dockerfile-thật)
4. [Docker Compose: chạy cả cụm bằng một lệnh](#4-docker-compose)
5. [Từ Compose lên Kubernetes: vì sao cần đổi](#5-từ-compose-lên-kubernetes)
6. [Helm: khuôn mẫu cho Kubernetes](#6-helm)
7. [Đọc file Helm thật](#7-đọc-file-helm-thật)
8. [Bí mật: External Secrets](#8-bí-mật-external-secrets)
9. [CI/CD: tự động test và triển khai](#9-cicd)
10. [Giám sát: Prometheus, Grafana, cảnh báo](#10-giám-sát)
11. [Bức tranh triển khai hoàn chỉnh](#11-bức-tranh-triển-khai-hoàn-chỉnh)

---

## 1. Vấn đề Ops giải quyết

Trên máy anh, chạy Meetly cần: mở 3 cửa sổ terminal, cài đúng Java 21, đúng Node, bật 6
container. Được, cho một người.

Giờ hình dung production thật:

- **Nhiều người dùng cùng lúc** → cần nhiều bản backend chạy song song để chia tải.
- **Máy chủ có thể hỏng** → phải tự động khởi động lại, không đợi người trực dậy lúc 3h sáng.
- **Tải lúc cao lúc thấp** → giờ họp nhiều thì thêm máy, đêm vắng thì bớt đi để tiết kiệm tiền.
- **Cập nhật code liên tục** → phải đưa code mới lên mà không làm gián đoạn người đang họp.
- **Bảo mật** → mật khẩu database không được lọt ra ngoài, container bị chiếm không được lan rộng.

Không ai làm những việc này bằng tay được. Ops là bộ công cụ **tự động hoá** tất cả. Chuỗi
công cụ của Meetly:

```
Code  →  Docker (đóng gói)  →  Helm (khai báo cách chạy)
      →  Kubernetes (chạy và tự quản lý)  →  CI/CD (tự động toàn bộ)
      →  Prometheus/Grafana (theo dõi)
```

---

## 2. Docker: đóng gói ứng dụng

### Vấn đề "máy tôi chạy được mà"

Anh viết code, chạy ngon trên máy anh. Đưa cho đồng nghiệp, họ thiếu thư viện, sai phiên bản
Java, lỗi lung tung. Đưa lên server production, lại lỗi khác. Nguyên nhân: **môi trường mỗi
nơi một khác**.

Docker giải quyết bằng cách đóng gói ứng dụng **CÙNG toàn bộ môi trường nó cần** — một hệ điều
hành thu gọn, đúng phiên bản Java, đúng thư viện — thành một khối duy nhất gọi là **image**.
Image này chạy ở đâu cũng **y hệt nhau**.

### Ba từ cần phân biệt

| Từ | Nghĩa | Ví như |
|---|---|---|
| **Dockerfile** | Công thức để tạo image | Công thức nấu ăn |
| **Image** | Bản đóng gói tĩnh | Món ăn đông lạnh đóng hộp |
| **Container** | Một image đang chạy | Món ăn đã hâm nóng, đang ăn |

Từ một image có thể chạy nhiều container cùng lúc (như từ một hộp đông lạnh nhân bản ra nhiều
suất). Đây chính là cách chạy nhiều bản backend song song.

---

## 3. Đọc Dockerfile thật

### 3.1 Dockerfile của backend

Mở `ops/docker/Dockerfile.api`. File này dùng kỹ thuật **multi-stage build** (build nhiều
tầng) — đọc kỹ vì đây là điểm hay:

```dockerfile
# ===== TẦNG 1: build (biên dịch) =====
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests

# ===== TẦNG 2: chạy =====
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S meetly && adduser -S meetly -G meetly
USER meetly
WORKDIR /app
COPY --from=build /app/target/meetly-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

**Vì sao chia hai tầng?**

Để biên dịch Java cần cả một bộ Maven + JDK đầy đủ, rất nặng (khoảng 600 MB). Nhưng để **chạy**
thì chỉ cần Java runtime (JRE) gọn nhẹ. Multi-stage cho phép: tầng 1 dùng đồ nặng để biên
dịch, tầng 2 **chỉ lấy file `.jar` kết quả** sang một image mỏng. Image cuối không chứa Maven,
không chứa mã nguồn → nhỏ hơn (~480 MB thay vì hơn 1 GB) và **an toàn hơn** (kẻ xấu chiếm được
container cũng không thấy mã nguồn).

Từng dòng tầng 1:

- `FROM maven:3.9-eclipse-temurin-21 AS build` — bắt đầu từ image có sẵn Maven + Java 21, đặt
  tên tầng này là `build`.
- `COPY backend/pom.xml .` rồi `mvn dependency:go-offline` **trước** khi copy code — mẹo tăng
  tốc: danh sách thư viện (`pom.xml`) ít đổi, nên Docker **nhớ lại** (cache) bước tải thư viện.
  Lần build sau chỉ đổi code thì không phải tải lại thư viện.
- `--mount=type=cache,target=/root/.m2` — giữ kho thư viện Maven qua các lần build, khỏi tải lại.
- `mvn package -DskipTests` — đóng gói thành file `.jar`. Bỏ test vì test đã chạy ở bước CI riêng.

Từng dòng tầng 2:

- `FROM eclipse-temurin:21-jre-alpine` — image Java **chạy** gọn nhẹ (bản `alpine` là Linux tí
  hon). Không có Maven.
- `addgroup ... adduser ... USER meetly` — **tạo user thường và chuyển sang dùng nó**. Đây là
  bảo mật quan trọng: mặc định container chạy quyền `root` (toàn quyền); nếu kẻ xấu thoát được
  ra thì rất nguy hiểm. Chạy bằng user thường giới hạn thiệt hại.
- `COPY --from=build ...` — lấy file `.jar` từ tầng `build` sang. **Đây là điểm mấu chốt của
  multi-stage:** chỉ lấy kết quả, bỏ lại toàn bộ đồ nghề nặng nề.
- `ENTRYPOINT [...]` — lệnh chạy khi container khởi động. `-XX:MaxRAMPercentage=75` bảo Java
  dùng tối đa 75% RAM được cấp (chừa chỗ cho hệ thống).

### 3.2 Dockerfile của frontend

`ops/docker/Dockerfile.web` cũng multi-stage, nhưng ý tưởng khác:

```dockerfile
# TẦNG 1: build ra file tĩnh
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build          # → thư mục dist/ chứa HTML, CSS, JS

# TẦNG 2: nginx phục vụ file tĩnh
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY ops/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
```

React sau khi `npm run build` biến thành một mớ file HTML/CSS/JS **tĩnh** (không cần Node để
chạy). Nên tầng 2 dùng **nginx** — một web server chuyên phục vụ file tĩnh, cực nhẹ và nhanh.

- `npm ci` thay vì `npm install` — bản "sạch" dành cho máy tự động, cài chính xác theo file
  khoá phiên bản, nhanh và ổn định hơn.
- `nginx-unprivileged` — bản nginx chạy **không cần quyền root** (lại là bảo mật).
- Image cuối chỉ **77 MB** — không chứa Node, không chứa mã nguồn React, chỉ có nginx + file
  tĩnh đã build.

> **Cả hai image đã được kiểm chứng chạy thật với hệ thống file chỉ-đọc** (`--read-only`):
> backend vẫn UP, web vẫn trả 200. Đây là mức khoá bảo mật cao — container không tự sửa được
> gì trên đĩa, kẻ xấu chiếm được cũng không cài mã độc vào được.

---

## 4. Docker Compose

Chạy 6 container bằng tay, mỗi cái một lệnh dài với đủ tham số mạng, ổ đĩa, biến môi trường —
bất khả thi. **Docker Compose** khai báo tất cả trong **một file YAML** rồi bật bằng một lệnh.

Mở `ops/compose/docker-compose.dev.yml`, xem một dịch vụ mẫu — PostgreSQL:

```yaml
services:
  postgres:
    image: postgres:16-alpine              # dùng image PostgreSQL 16 có sẵn
    environment:                            # biến môi trường cấu hình
      POSTGRES_DB: meetly
      POSTGRES_USER: meetly
      POSTGRES_PASSWORD: meetly
    ports: ["5432:5432"]                    # mở cổng 5432 ra máy thật
    volumes: [pgdata:/var/lib/postgresql/data]   # lưu dữ liệu bền
    healthcheck:                            # cách kiểm tra "đã sẵn sàng chưa"
      test: ["CMD-SHELL", "pg_isready -U meetly"]
      interval: 5s
      retries: 10
```

Các khái niệm:

- **`image`** — dùng image có sẵn từ kho công cộng (Docker Hub). Không phải tự viết Dockerfile
  cho PostgreSQL; nó đã được đóng gói sẵn.
- **`environment`** — truyền cấu hình vào container qua biến môi trường. PostgreSQL đọc các
  biến này để tạo database và tài khoản.
- **`ports: ["5432:5432"]`** — "cổng 5432 trong container ↔ cổng 5432 trên máy thật". Nhờ vậy
  backend chạy trên máy thật gọi được vào database trong container.
- **`volumes: [pgdata:/...]`** — **ổ đĩa bền**. Container xoá đi tạo lại thì dữ liệu vẫn còn,
  vì nó nằm trong `volume` riêng, không nằm trong container. (Nhớ tài liệu 02: xoá cờ `-v` là
  xoá luôn volume này → mất sạch dữ liệu.)
- **`healthcheck`** — Compose định kỳ chạy lệnh `pg_isready` để biết database đã nhận kết nối
  chưa. Quan trọng vì "container đang chạy" ≠ "database dùng được ngay" — nó mất vài giây khởi
  động. Các dịch vụ phụ thuộc sẽ chờ tới khi cái này "healthy".

Một dòng bật tất cả:

```bash
docker compose -f ops/compose/docker-compose.dev.yml up -d
```

> Compose tuyệt vời cho **một máy** (máy dev của anh). Nhưng production cần nhiều máy, tự khởi
> động lại, tự co giãn — vượt khả năng của Compose. Đó là lúc cần Kubernetes.

---

## 5. Từ Compose lên Kubernetes

### Vì sao Compose không đủ cho production

| Nhu cầu production | Compose làm được? |
|---|---|
| Chạy nhiều bản backend trên nhiều máy | ❌ Compose chỉ một máy |
| Backend chết thì tự tạo lại | ❌ |
| Tải cao thì tự thêm máy | ❌ |
| Cập nhật code không gián đoạn dịch vụ | ❌ |
| Tự phân phối request đều cho các bản | ❌ |

**Kubernetes** (viết tắt **K8s**) làm được tất cả. Nó là "hệ điều hành cho cả một dàn máy chủ".

### Các khái niệm Kubernetes, giải thích bằng ví dụ

Hình dung K8s như **quản lý một nhà hàng chuỗi**:

| Khái niệm K8s | Ví như | Nghĩa thật |
|---|---|---|
| **Pod** | Một đầu bếp đang làm việc | Đơn vị chạy nhỏ nhất — thường 1 container |
| **Deployment** | Quy định "luôn có 3 đầu bếp" | Khai báo "luôn có N pod". Pod chết → tự thuê người mới |
| **Service** | Số điện thoại tổng đài của bếp | Địa chỉ ổn định trỏ tới nhóm pod (vì pod sinh/chết liên tục, IP đổi hoài) |
| **Ingress** | Cửa chính đón khách | Cổng từ Internet vào, phân luồng theo tên miền/đường dẫn |
| **HPA** | Quản lý "đông khách thì gọi thêm đầu bếp" | Tự tăng/giảm số pod theo tải |
| **Secret** | Két sắt đựng công thức mật | Nơi chứa mật khẩu, khoá bí mật |
| **Namespace** | Tầng riêng cho chi nhánh A và B | Vùng ngăn cách (staging vs production) |
| **Liveness probe** | Hỏi "còn tỉnh không?" | Pod không trả lời → bị thay |
| **Readiness probe** | Hỏi "nhận khách được chưa?" | Chưa sẵn sàng → không gửi khách tới |

**Điểm cốt lõi:** với K8s, anh không ra lệnh "chạy cái này, tắt cái kia". Anh **khai báo trạng
thái mong muốn** ("tôi muốn luôn có 2–10 bản backend khoẻ mạnh"), rồi K8s **tự lo** để hiện
thực luôn đúng như thế: máy hỏng thì dời pod sang máy khác, tải cao thì thêm pod, cập nhật thì
thay dần từng pod để không gián đoạn.

Nhưng viết file khai báo K8s bằng tay rất dài và trùng lặp (mỗi môi trường một bản gần giống
nhau). Đó là lúc cần Helm.

---

## 6. Helm

**Helm** là "trình quản lý gói" cho Kubernetes. Ý tưởng: viết **khuôn mẫu** (template) một
lần, rồi truyền **giá trị** (values) khác nhau cho từng môi trường.

```
ops/helm/meetly/
├── Chart.yaml            — thông tin gói (tên, phiên bản)
├── values.yaml           — giá trị MẶC ĐỊNH (dùng cho production)
├── values-staging.yaml   — giá trị ghi đè cho môi trường thử nghiệm
├── values-prod.yaml      — ghi đè cho production (nếu cần)
└── templates/            — các khuôn mẫu
    ├── api-deployment.yaml
    ├── api-service.yaml
    ├── api-hpa.yaml
    ├── web-deployment.yaml
    ├── web-service.yaml
    ├── ingress.yaml
    ├── api-servicemonitor.yaml
    └── prometheusrule.yaml
```

Ví như một **biểu mẫu có chỗ trống**: "Chạy `___` bản backend, mỗi bản `___` CPU". Staging điền
"1 bản, 250m CPU", production điền "2–10 bản, nhiều CPU hơn". Cùng một biểu mẫu, khác nội dung.

---

## 7. Đọc file Helm thật

### 7.1 Khuôn mẫu và cú pháp `{{ }}`

Mở `ops/helm/meetly/templates/api-deployment.yaml`. Đây là khuôn tạo các pod backend:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: meetly-api
spec:
  {{- if not .Values.api.hpa.enabled }}
  replicas: {{ .Values.api.replicas }}
  {{- end }}
  selector:
    matchLabels: { app: meetly-api }
  template:
    spec:
      securityContext:
        runAsNonRoot: true                      # (A) không chạy quyền root
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: api
          image: {{ include "meetly.apiImage" . }}    # (B) lấy tên image
          ports: [{ containerPort: 8080 }]
          securityContext:
            allowPrivilegeEscalation: false      # (C) khoá bảo mật
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
          volumeMounts:
            - { name: tmp, mountPath: /tmp }     # JVM cần /tmp ghi được
          envFrom:
            - secretRef: { name: {{ .Values.api.existingSecret }} }   # (D) nạp bí mật
          env:
            {{- range $k, $v := .Values.api.env }}                    # (E) nạp cấu hình thường
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 } # (F) probe
            initialDelaySeconds: 20
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 30
```

Những chỗ có `{{ }}` là **chỗ trống được điền lúc triển khai**:

- **`{{ .Values.api.replicas }}`** — lấy giá trị `api.replicas` từ file values. Còn
  `{{- if not .Values.api.hpa.enabled }}` nghĩa là: chỉ đặt số bản cố định NẾU không bật tự co
  giãn. Nếu bật HPA thì để HPA quyết định số bản.
- **(B) `{{ include "meetly.apiImage" . }}`** — gọi một "hàm" định nghĩa nơi khác, ghép ra tên
  image đầy đủ (`ghcr.io/tổ-chức/meetly-api:mã-commit`).
- **(E) `{{- range $k, $v := .Values.api.env }}`** — vòng lặp: với mỗi cặp key-value trong
  `api.env`, sinh ra một biến môi trường. Giống `for` trong Java.

Các điểm không phải template nhưng quan trọng:

- **(A)(C) securityContext** — khoá bảo mật ở mức Kubernetes, chồng thêm lên mức Docker:
  không chạy root, không cho leo thang đặc quyền, hệ thống file chỉ-đọc, bỏ mọi đặc quyền hệ
  điều hành. Càng khoá chặt, container bị chiếm càng ít gây hại.
- **(D) `envFrom.secretRef`** — nạp **tất cả** khoá trong Secret `meetly-api-secrets` vào làm
  biến môi trường. Đây là nơi mật khẩu database, khoá JWT... đi vào ứng dụng mà không nằm trong
  code (xem mục 8).
- **(F) probe** — nhớ ở tài liệu 03: backend có `/actuator/health/readiness` và `/liveness`.
  K8s gọi hai địa chỉ này định kỳ: `liveness` fail → khởi động lại pod; `readiness` fail →
  ngừng gửi request tới pod (nhưng không giết nó, chờ nó sẵn sàng lại). `initialDelaySeconds`
  cho ứng dụng thời gian khởi động trước khi bắt đầu hỏi.

### 7.2 File giá trị

`ops/helm/meetly/values.yaml` — giá trị mặc định (production):

```yaml
image:
  registry: ghcr.io/CHANGEME-org      # ← thay bằng tổ chức GitHub thật
  apiRepository: meetly-api
  tag: "latest-sha"                    # ← CD ghi đè bằng mã commit thật

api:
  replicas: 2
  hpa:
    enabled: true
    minReplicas: 2
    maxReplicas: 10                    # tải cao nhất: 10 bản backend
    targetCPUUtilizationPercentage: 70 # CPU vượt 70% thì thêm bản
  env:                                 # cấu hình KHÔNG nhạy cảm
    SPRING_PROFILES_ACTIVE: prod
    MEETLY_LIVEKIT_WS_URL: wss://livekit.meetly.example.com
    MEETLY_STORAGE_BUCKET: meetly-recordings
    MEETLY_AUTH_COOKIE_SECURE: "true"  # production dùng HTTPS
  existingSecret: meetly-api-secrets   # tên Secret chứa mật khẩu

ingress:
  className: nginx
  host: meet.meetly.example.com        # ← tên miền thật
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"   # chat WebSocket sống lâu
```

`values-staging.yaml` chỉ **ghi đè phần khác biệt**: staging chạy 1–3 bản (không phải 2–10),
tên miền `meet-staging...`, dùng bucket riêng. Phần giống nhau kế thừa từ `values.yaml`.

> **Những chỗ `CHANGEME` và `meetly.example.com`** là giá trị giữ chỗ, phải thay bằng thông
> tin thật khi có cụm K8s và tên miền. Deploy như hiện tại chắc chắn thất bại — đây là điều em
> đã lưu ý trong phần "còn thiếu".

### 7.3 HPA — tự co giãn

`templates/api-hpa.yaml`:

```yaml
{{- if .Values.api.hpa.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: meetly-api
spec:
  scaleTargetRef:
    kind: Deployment
    name: meetly-api               # co giãn cái Deployment backend
  minReplicas: {{ .Values.api.hpa.minReplicas }}   # ít nhất 2
  maxReplicas: {{ .Values.api.hpa.maxReplicas }}   # nhiều nhất 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          averageUtilization: 70   # mục tiêu: CPU trung bình 70%
{{- end }}
```

Đọc: "theo dõi CPU trung bình của các pod backend. Vượt 70% thì thêm pod (tối đa 10). Thấp thì
bớt (tối thiểu 2)". Đây là thứ giúp hệ thống **tự động chịu tải giờ cao điểm rồi tự tiết kiệm
lúc vắng**, không cần người can thiệp.

### 7.4 Ingress — cửa ngõ

`templates/ingress.yaml` định tuyến request từ Internet:

```yaml
rules:
  - host: meet.meetly.example.com
    http:
      paths:
        - path: /api
          backend: { service: { name: meetly-api } }     # /api → backend
        - path: /ws
          backend: { service: { name: meetly-api } }     # /ws → backend (chat)
        - path: /
          backend: { service: { name: meetly-web } }     # còn lại → frontend
```

Cùng một tên miền, nhưng: `/api` và `/ws` chuyển tới backend, mọi thứ khác tới frontend. Nhớ
ở tài liệu 04, khi phát triển thì Vite làm việc định tuyến này; lên production thì Ingress làm.

Chú ý `proxy-read-timeout: "3600"` trong values — mặc định Ingress cắt kết nối "im lặng" sau
60 giây, nhưng WebSocket chat có thể im lặng lâu (không ai gõ gì), nên nới lên 1 giờ để không
bị cắt oan.

### 7.5 Kiểm tra khuôn mẫu không cần cụm thật

Không có cụm Kubernetes vẫn kiểm được khuôn mẫu có hợp lệ không:

```bash
helm lint ops/helm/meetly                                    # kiểm cú pháp
helm template meetly ops/helm/meetly -f ops/helm/meetly/values-staging.yaml   # "in thử" ra YAML cuối
```

Lệnh thứ hai điền hết `{{ }}` bằng giá trị staging và in ra file K8s hoàn chỉnh, để anh xem
kết quả đúng ý chưa trước khi triển khai thật. Dự án đã kiểm cả bằng công cụ `kubeconform` để
đảm bảo khớp chuẩn Kubernetes.

---

## 8. Bí mật: External Secrets

**Nguyên tắc bất di bất dịch: mật khẩu, khoá bí mật KHÔNG BAO GIỜ nằm trong Git.** Git lưu
lịch sử mãi mãi và nhiều người xem được — bí mật lọt vào đó coi như lộ.

Nhưng ứng dụng vẫn cần mật khẩu database để chạy. Giải quyết thế nào?

Bí mật thật được cất trong **kho bí mật của nhà cung cấp đám mây** (AWS Secrets Manager).
**External Secrets Operator** là một thành phần chạy trong K8s, tự động lấy bí mật từ kho đó
và tạo thành Secret cho ứng dụng dùng. Mở `ops/k8s/external-secrets/meetly-api-externalsecret.yaml`:

```yaml
kind: ExternalSecret
metadata:
  name: meetly-api-secrets
spec:
  refreshInterval: 1h                    # cứ 1 giờ đồng bộ lại
  target: { name: meetly-api-secrets }   # tạo ra Secret tên này (khớp values.yaml)
  data:
    - secretKey: SPRING_DATASOURCE_PASSWORD          # tên biến trong ứng dụng
      remoteRef: { key: meetly/prod/db, property: password }   # lấy từ đâu trong AWS
    - secretKey: MEETLY_AUTH_JWT_SECRET
      remoteRef: { key: meetly/prod/app, property: jwt_secret }
    - secretKey: MEETLY_LIVEKIT_API_SECRET
      remoteRef: { key: meetly/prod/livekit, property: api_secret }
    ...
```

Luồng: AWS Secrets Manager (chứa bí mật thật) → External Secrets Operator (đồng bộ mỗi giờ) →
Secret `meetly-api-secrets` trong K8s → nạp vào backend qua `envFrom` (mục 7.1). **Git chỉ
chứa bản mô tả "lấy cái gì từ đâu", không chứa giá trị thật.**

Tên các biến (`SPRING_DATASOURCE_PASSWORD`...) chính là biến môi trường mà Spring Boot đọc:
`SPRING_DATASOURCE_PASSWORD` → cấu hình `spring.datasource.password`. Đây là cơ chế "relaxed
binding" đã nói ở tài liệu 03 — cùng một cấu hình, viết bằng biến môi trường in hoa gạch dưới.

---

## 9. CI/CD

**CI/CD** tự động hoá toàn bộ chặng từ "viết xong code" tới "chạy trên production". Dùng
**GitHub Actions** — chạy tự động mỗi khi có thay đổi trên GitHub.

### 9.1 CI — kiểm tra mỗi đề xuất thay đổi

**CI** (Continuous Integration) chạy khi ai đó mở **Pull Request** (đề xuất gộp code). Mở
`.github/workflows/ci.yml`:

```yaml
on:
  pull_request:
    branches: [main]

jobs:
  changes:                          # (1) xác định phần nào bị đụng
    ...
      filters: |
        backend: ['backend/**', 'ops/docker/Dockerfile.api']
        frontend: ['frontend/**', 'ops/docker/Dockerfile.web']

  backend:                          # (2) chỉ chạy NẾU backend bị đụng
    if: needs.changes.outputs.backend == 'true'
    steps:
      - uses: actions/setup-java@v4
        with: { java-version: '21' }
      - run: ./mvnw -B verify        # chạy toàn bộ test backend

  frontend:                         # (3) chỉ chạy NẾU frontend bị đụng
    if: needs.changes.outputs.frontend == 'true'
    steps:
      - run: |
          npm ci
          npm run lint
          npm test
          npm run build
```

Điểm hay là **path filter** (lọc theo đường dẫn): job `changes` xem PR đụng vào những file nào.
Chỉ sửa frontend thì **không chạy** test backend (tốn thời gian vô ích), và ngược lại.

Nếu test đỏ, GitHub chặn không cho gộp PR. Đây là "lưới an toàn": code lỗi không bao giờ lên
được nhánh chính.

> Nhớ ở tài liệu 02: test backend cần Docker cho Testcontainers. GitHub Actions runner có sẵn
> Docker nên `./mvnw verify` tự bật được PostgreSQL/Redis thật.

### 9.2 CD — tự động triển khai

**CD** (Continuous Deployment) chạy khi code đã **gộp vào nhánh main**. Mở
`.github/workflows/cd.yml` — đây là dây chuyền hoàn chỉnh:

```yaml
on:
  push:
    branches: [main]

jobs:
  build-push:                       # (1) đóng gói + kiểm bảo mật
    steps:
      - uses: docker/build-push-action@v6      # build image backend
        with:
          file: ops/docker/Dockerfile.api
          tags: ${{ env.REGISTRY }}/meetly-api:${{ github.sha }}   # nhãn = mã commit
      - ... (build image web tương tự) ...
      - uses: aquasecurity/trivy-action@0.28.0 # quét lỗ hổng
        with:
          severity: CRITICAL,HIGH
          exit-code: '1'                        # có lỗ hổng nặng → DỪNG

  deploy-staging:                   # (2) triển khai lên staging
    needs: build-push
    steps:
      - run: |
          helm upgrade --install meetly ops/helm/meetly \
            -f ops/helm/meetly/values-staging.yaml \
            --set image.tag=${{ github.sha }} \
            --wait

  smoke-e2e:                        # (3) chạy e2e trên staging thật
    needs: deploy-staging
    steps:
      - run: npx playwright test e2e/two-users-meet.spec.ts

  deploy-prod:                      # (4) triển khai production
    needs: smoke-e2e
    environment: production          # ← CHỜ NGƯỜI DUYỆT
    steps:
      - run: helm upgrade --install meetly ... values-prod.yaml ...
```

Bốn chặng, mỗi chặng chỉ chạy khi chặng trước xong:

1. **build-push** — đóng gói 2 image, **gắn nhãn bằng mã commit** (`github.sha`) chứ không
   phải `latest`. Rồi **Trivy** quét lỗ hổng bảo mật; có lỗ hổng nghiêm trọng thì `exit-code: 1`
   làm **dừng cả dây chuyền**. (Đây là landmine em đã cảnh báo: lần đẩy đầu tiên có thể dừng ở
   đây nếu thư viện có CVE mức HIGH.)
2. **deploy-staging** — dùng Helm triển khai lên môi trường thử nghiệm, truyền mã commit làm
   nhãn image. `--wait` bảo Helm chờ tới khi pod thật sự khoẻ.
3. **smoke-e2e** — chạy test đầu-cuối trên **staging thật** (không phải máy dev). Xác nhận hệ
   thống vừa deploy thực sự hoạt động.
4. **deploy-prod** — `environment: production` là một "cổng duyệt tay": GitHub **dừng lại chờ
   một người bấm nút phê duyệt** trước khi lên production thật. An toàn cuối cùng.

> **Vì sao gắn nhãn image bằng mã commit thay vì `latest`?** `latest` mơ hồ — không biết đang
> chạy chính xác code nào, và khi cần quay lui phiên bản cũ thì không chỉ đích danh được. Mã
> commit truy ngược được đúng dòng code, quay lui chính xác.

---

## 10. Giám sát

Triển khai xong không phải là hết. Phải **biết hệ thống có đang khoẻ không** mà không cần
ngồi nhìn mãi.

### 10.1 Ba lớp

- **Prometheus** — định kỳ "hỏi thăm" backend qua `/actuator/prometheus`, thu về các con số
  (**metric**): số request, tỷ lệ lỗi, độ trễ, bộ nhớ. Lưu lại theo thời gian.
- **Grafana** — vẽ các con số đó thành biểu đồ trực quan.
- **Alert** (cảnh báo) — quy tắc tự động báo động khi có bất thường.

### 10.2 ServiceMonitor — bảo Prometheus scrape ở đâu

`templates/api-servicemonitor.yaml`:

```yaml
kind: ServiceMonitor
spec:
  selector:
    matchLabels: { app: meetly-api }        # tìm các pod backend
  endpoints:
    - port: http
      path: /actuator/prometheus            # hỏi thăm ở địa chỉ này
      interval: 30s                          # mỗi 30 giây một lần
```

Đây là cách nói với Prometheus: "cứ 30 giây, vào `/actuator/prometheus` của mọi pod backend mà
lấy số".

### 10.3 Cảnh báo

`templates/prometheusrule.yaml` định nghĩa 4 cảnh báo. Xem cái quan trọng nhất:

```yaml
- alert: MeetlyApiHigh5xxRate
  expr: >
    sum(rate(http_server_requests_seconds_count{status=~"5..", ...}[5m]))
    / sum(rate(http_server_requests_seconds_count{...}[5m])) > 0.05
  for: 5m
  labels: { severity: critical }
  annotations:
    summary: "meetly-api 5xx above 5% for 5 minutes"
```

Đọc: "nếu tỷ lệ request bị lỗi 5xx vượt **5%** liên tục trong **5 phút** → báo động mức nghiêm
trọng". `status=~"5.."` nghĩa là các mã bắt đầu bằng 5 (500, 502, 503...).

> Nhớ lỗi đã sửa ở tài liệu 03 (lỗi client trả 500)? Chính cảnh báo này là lý do phải sửa: nếu
> lỗi do client gõ sai cũng bị tính là 5xx, thì một con bot quét lung tung sẽ làm cảnh báo này
> **báo động giả lúc nửa đêm**, gọi người trực dậy vô ích. Sau khi sửa, lỗi client trả 4xx nên
> không lọt vào công thức này.

Bốn cảnh báo của dự án: tỷ lệ lỗi 5xx cao, độ trễ p95 cao, pod khởi động lại liên tục, và máy
chủ LiveKit gần đầy tải.

### 10.4 Log dạng JSON

Nhớ ở tài liệu 03, `CorrelationIdFilter` gắn mã cho mỗi request. Trên production, log được ghi
thành **JSON một dòng** (thay vì chữ thường dễ đọc). Vì sao? Vì máy đọc được JSON → có thể tìm
kiếm: "cho tôi mọi dòng log của request có mã `X-Request-Id` này" → thấy toàn bộ hành trình
một request giữa hàng triệu dòng. Cấu hình ở `logback-spring.xml`, chỉ bật khi chạy profile
`prod`.

---

## 11. Bức tranh triển khai hoàn chỉnh

Ghép tất cả. Từ lúc lập trình viên viết code tới lúc người dùng hưởng tính năng mới:

```
1. Lập trình viên sửa code, mở Pull Request trên GitHub
        │
        ▼
2. CI tự chạy: test backend + frontend (chỉ phần bị đụng)
        │  test xanh?
        ▼ (có)
3. Gộp vào nhánh main
        │
        ▼
4. CD tự chạy:
   ├── build 2 Docker image, nhãn = mã commit
   ├── Trivy quét bảo mật (có lỗ hổng nặng → dừng)
   ├── Helm triển khai lên STAGING
   ├── chạy e2e trên staging thật
   ├── ✋ CHỜ NGƯỜI DUYỆT
   └── Helm triển khai lên PRODUCTION
        │
        ▼
5. Trên cụm Kubernetes production:
   ├── Deployment giữ 2–10 pod backend khoẻ mạnh
   ├── HPA tự thêm pod khi tải cao
   ├── Ingress phân luồng request theo /api, /ws, /
   ├── External Secrets nạp mật khẩu từ AWS (không nằm trong Git)
   ├── liveness/readiness probe tự thay pod hỏng
   └── Prometheus thu metric, Grafana vẽ biểu đồ, Alert báo động khi bất thường
        │
        ▼
6. Người dùng vào meet.meetly.example.com, họp mượt mà
```

Toàn bộ chặng 2–5 **tự động**, con người chỉ can thiệp ở bước 1 (viết code) và một cú bấm phê
duyệt ở bước 4.

---

## Còn thiếu gì để chạy thật

Như em đã nói với anh, ba thứ cần trước khi triển khai lên cloud:

1. **Cụm Kubernetes** staging và production (thuê từ AWS EKS hoặc Google GKE).
2. **Tên miền thật** thay cho `meetly.example.com` trong các file values.
3. **Hai bí mật GitHub**: `KUBECONFIG_STAGING` và `KUBECONFIG_PROD` (thông tin kết nối tới cụm).

Có đủ ba thứ đó thì dây chuyền trên chạy được đầu-cuối. Chi tiết các bước còn lại nằm trong
`docs/runbooks/go-live-checklist.md`.

---

## Tự kiểm tra hiểu bài

1. Multi-stage build giúp image nhỏ và an toàn hơn thế nào?
2. Vì sao Compose đủ cho máy dev nhưng không đủ cho production?
3. Trong Kubernetes, "khai báo trạng thái mong muốn" khác gì "ra lệnh từng bước"?
4. Helm giải quyết vấn đề gì mà viết file K8s tay không giải quyết được?
5. Vì sao mật khẩu không nằm trong Git mà vẫn vào được ứng dụng?
6. Cổng duyệt tay ở bước deploy-prod để làm gì?

*(Đáp án: mục 3.1, mục 5, mục 5, mục 6, mục 8, mục 9.2.)*

---

Tiếp theo: [06-cac-dich-vu-ngoai.md](06-cac-dich-vu-ngoai.md) — LiveKit, Redis, MinIO và các dịch vụ bên thứ ba.
