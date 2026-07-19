# Meetly — Hướng dẫn chạy và kiểm thử

> Tài liệu này hướng dẫn từng bước để chạy toàn bộ hệ thống trên máy cá nhân và tự tay thử
> mọi tính năng. Không cần biết trước gì về Docker hay hạ tầng.
>
> Nếu gặp thuật ngữ lạ, tra ở [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md).

---

## Mục lục

1. [Cần cài gì trước](#1-cần-cài-gì-trước)
2. [Hệ thống gồm mấy phần phải bật](#2-hệ-thống-gồm-mấy-phần-phải-bật)
3. [Bước 1 — Bật hạ tầng](#bước-1--bật-hạ-tầng)
4. [Bước 2 — Bật backend](#bước-2--bật-backend)
5. [Bước 3 — Bật frontend](#bước-3--bật-frontend)
6. [Kịch bản thử từng tính năng](#kịch-bản-thử-từng-tính-năng)
7. [Chạy bộ test tự động](#chạy-bộ-test-tự-động)
8. [Xem dữ liệu bên trong hệ thống](#xem-dữ-liệu-bên-trong-hệ-thống)
9. [Xử lý sự cố thường gặp](#xử-lý-sự-cố-thường-gặp)
10. [Tắt hệ thống](#tắt-hệ-thống)

---

## 1. Cần cài gì trước

| Phần mềm | Phiên bản | Dùng để làm gì | Kiểm tra bằng lệnh |
|---|---|---|---|
| **Docker** | 20 trở lên | Chạy database, LiveKit, kho file | `docker --version` |
| **Docker Compose** | v2 | Bật nhiều container bằng 1 lệnh | `docker compose version` |
| **Java JDK** | **đúng 21** | Chạy backend | `java -version` |
| **Node.js** | 20 trở lên | Chạy frontend | `node -v` |

> **Trên máy anh hiện tại:** Java và Maven **không cài vào hệ thống** mà nằm ở thư mục
> `~/tools`. Vì vậy **trước mỗi lệnh liên quan tới backend, phải chạy lệnh này trước:**
>
> ```bash
> source ~/tools/env.sh
> ```
>
> Lệnh này chỉ có tác dụng trong **cửa sổ terminal hiện tại**. Mở cửa sổ mới là phải chạy lại.
> Quên bước này sẽ gặp lỗi `JAVA_HOME environment variable is not defined`.

**Kiểm tra nhanh mọi thứ đã sẵn sàng:**

```bash
docker --version && docker compose version && node -v
source ~/tools/env.sh && java -version
```

Nếu cả 4 dòng đều in ra phiên bản, không có chữ `command not found`, là ổn.

---

## 2. Hệ thống gồm mấy phần phải bật

Cần **3 cửa sổ terminal** mở song song, mỗi cửa sổ chạy một phần và **để nguyên đó** (không
đóng, không bấm Ctrl+C):

| Cửa sổ | Chạy cái gì | Địa chỉ |
|---|---|---|
| 1 | Hạ tầng (Docker) — bật xong chạy nền, không cần giữ cửa sổ | — |
| 2 | Backend | http://localhost:8080 |
| 3 | Frontend | http://localhost:5173 |

Mở terminal ở thư mục dự án:

```bash
cd ~/Desktop/WorkSpace/stream-video
```

---

## Bước 1 — Bật hạ tầng

Lệnh này bật 6 container: PostgreSQL, Redis, LiveKit, MinIO, tiến trình tạo thùng chứa file,
và Egress.

```bash
docker compose -f ops/compose/docker-compose.dev.yml up -d
```

> `-d` nghĩa là *detached* — chạy nền, trả lại con trỏ cho anh dùng tiếp.

**Lần đầu chạy sẽ mất 3–10 phút** vì phải tải các image về (khoảng 2 GB). Những lần sau chỉ
vài giây.

### Kiểm tra đã lên đủ chưa

```bash
docker compose -f ops/compose/docker-compose.dev.yml ps
```

Kết quả mong đợi — **5 dịch vụ** đều `Up`:

```
NAME                    STATUS
meetly-dev-postgres-1   Up (healthy)
meetly-dev-redis-1      Up (healthy)
meetly-dev-livekit-1    Up
meetly-dev-minio-1      Up (healthy)
meetly-dev-egress-1     Up
```

> **Không thấy `createbucket` trong danh sách là ĐÚNG.** Nhiệm vụ của nó chỉ là tạo "thùng
> chứa" tên `meetly-recordings` trong MinIO rồi kết thúc, nên nó không còn chạy nữa. Muốn xem
> cả những container đã kết thúc thì thêm cờ `-a`:
>
> ```bash
> docker compose -f ops/compose/docker-compose.dev.yml ps -a | grep createbucket
> # meetly-dev-createbucket-1   Exited (0)      ← số 0 nghĩa là thành công
> ```

**Kiểm tra LiveKit trả lời:**

```bash
curl http://localhost:7880
```

Phải in ra chữ `OK`.

### Các cổng đang mở

| Dịch vụ | Cổng | Ghi chú |
|---|---|---|
| PostgreSQL | 5432 | tài khoản `meetly` / mật khẩu `meetly` / DB `meetly` |
| Redis | 6379 | |
| LiveKit | 7880 (tín hiệu), 7881 (media qua TCP) | |
| LiveKit media | 50000–50060 (UDP) | đường video/tiếng đi chính |
| MinIO | 9000 (API), 9001 (giao diện web) | tài khoản `minio` / mật khẩu `minio12345` |

---

## Bước 2 — Bật backend

Mở **cửa sổ terminal thứ hai**:

```bash
cd ~/Desktop/WorkSpace/stream-video/backend
source ~/tools/env.sh
./mvnw spring-boot:run
```

**Lần đầu mất 2–5 phút** vì Maven tải thư viện. Lần sau khoảng 10 giây.

Chạy thành công khi thấy dòng:

```
Started MeetlyApplication in 2.345 seconds
```

**Cửa sổ này phải để nguyên.** Muốn dừng backend thì bấm `Ctrl + C`.

### Kiểm tra backend sống

Mở cửa sổ khác (hoặc dùng cửa sổ 1) chạy:

```bash
curl http://localhost:8080/actuator/health
```

Kết quả mong đợi:

```json
{"status":"UP","groups":["liveness","readiness"]}
```

> Backend tự động tạo/cập nhật các bảng trong database khi khởi động (nhờ Flyway). Anh không
> phải chạy file SQL nào bằng tay.

---

## Bước 3 — Bật frontend

Mở **cửa sổ terminal thứ ba**:

```bash
cd ~/Desktop/WorkSpace/stream-video/frontend
npm install     # chỉ cần chạy lần đầu, hoặc khi có thư viện mới
npm run dev
```

Thành công khi thấy:

```
VITE ready in 320 ms
➜  Local:   http://localhost:5173/
```

Mở trình duyệt vào **http://localhost:5173** — sẽ thấy màn hình đăng nhập.

> **Lưu ý về camera:** trình duyệt chỉ cho dùng camera/micro trên `localhost` hoặc HTTPS.
> Vào bằng `http://localhost:5173` thì được; vào bằng địa chỉ IP kiểu `http://192.168.1.5:5173`
> thì trình duyệt sẽ **chặn camera**.

---

## Kịch bản thử từng tính năng

Làm lần lượt từ trên xuống. Mỗi kịch bản ghi rõ **phải thấy gì** để biết đúng hay sai.

### Kịch bản 1 — Đăng ký và đăng nhập

1. Vào http://localhost:5173 → bấm **"Sign up"**
2. Điền: Full name = `Anh`, Email = `anh@test.com`, Password = `secret123`
3. Bấm **Sign up**

✅ **Đúng:** tự chuyển sang trang chính, góc phải trên hiện tên `Anh`.

**Thử thêm:**
- Bấm **Sign out** rồi đăng nhập lại → vào được.
- Đăng ký lại đúng email đó → hiện *"That email is already registered"*.
- Đăng nhập sai mật khẩu → hiện *"Incorrect email or password"*.
- Đang đăng nhập, bấm F5 tải lại trang → **vẫn đăng nhập**, không bị đá ra.

> Vì sao F5 vẫn đăng nhập? Refresh token nằm trong cookie httpOnly. Khi tải lại trang, ứng
> dụng tự động dùng cookie đó xin access token mới.

### Kịch bản 2 — Họp nhanh một mình

1. Ở trang chính bấm **"Meet now"**
2. Trình duyệt hỏi quyền camera/micro → bấm **Cho phép / Allow**
3. Thấy màn hình xem trước có hình mình → bấm **"Join"**

✅ **Đúng:** vào phòng, thấy ô video của mình, thanh dưới có nút mic, camera, chia sẻ màn
hình, giơ tay, ghi hình, kết thúc họp, rời phòng.

**Nhìn lên thanh địa chỉ**, sẽ thấy dạng `http://localhost:5173/m/abc-defg-hij/room`.
Đoạn `abc-defg-hij` là **mã phòng** — copy lại để dùng cho kịch bản sau.

**Thử thêm:** bấm tắt/bật mic và camera → ô video của mình thay đổi tương ứng.

### Kịch bản 3 — Hai người họp với nhau (quan trọng nhất)

Cần **2 cửa sổ trình duyệt tách biệt**. Cách đơn giản nhất: một cửa sổ thường + một cửa sổ
**ẩn danh** (Ctrl+Shift+N trên Chrome).

> Vì sao phải ẩn danh? Vì hai tab thường dùng chung phiên đăng nhập → sẽ là cùng một người.

**Ở cửa sổ 1 (đã là Anh):**
1. Về trang chính, điền Title = `Họp thử`, để nguyên Room type = **Private meeting**
2. Bấm **Schedule**
3. Ở dòng vừa tạo bấm **Members** → nhập email của người thứ hai: `binh@test.com`, chọn vai
   **Speaker** → bấm **Add** → bấm **Close**
4. Bấm **Join** ở dòng đó → bấm **Join** lần nữa ở màn hình xem trước
5. Copy mã phòng trên thanh địa chỉ

**Ở cửa sổ 2 (ẩn danh):**
6. Vào http://localhost:5173 → **Sign up** với email đúng bằng `binh@test.com`, tên `Bình`
7. Vào thẳng địa chỉ `http://localhost:5173/m/<mã-phòng>` (dán mã đã copy)
8. Bấm **Join**

✅ **Đúng:** **cả hai cửa sổ đều thấy 2 ô video**, hiện đúng tên `Anh` và `Bình`.

> **Đây là phép thử quan trọng nhất** — nó chứng minh toàn bộ chuỗi hoạt động: đăng nhập →
> phân quyền → cấp vé → kết nối WebRTC → máy chủ chuyển tiếp video hai chiều.

**Thử thêm:** ở cửa sổ 2, gõ tin nhắn vào ô **"Type a message..."** bên phải rồi Enter →
cửa sổ 1 thấy tin ngay lập tức.

### Kịch bản 4 — Webinar và khách vãng lai (không cần tài khoản)

1. Ở cửa sổ 1, trang chính: Title = `Hội thảo`, Room type chọn **Webinar** → **Schedule**
2. Bấm **Join** → vào phòng, copy mã phòng
3. Mở **cửa sổ ẩn danh mới** (chưa đăng nhập gì cả), vào `http://localhost:5173/m/<mã-phòng>`
4. Thấy dòng chữ *"You are joining as a guest — enter a display name below"*
5. Nhập tên `Khách` vào ô Username → bấm **Join**

✅ **Đúng, và đây là điểm cần soi kỹ:**
- Khách **KHÔNG có** cụm nút mic/camera/chia sẻ màn hình
- Khách **CÓ** nút giơ tay, ô chat, nút rời phòng
- Khách vẫn **xem và nghe được** host

> Đây là phân quyền thật, không phải ẩn nút cho đẹp. Tấm vé của khách ghi `canPublish: false`,
> nên **kể cả có can thiệp vào giao diện bằng công cụ lập trình viên** thì máy chủ LiveKit vẫn
> từ chối nhận video của họ.

### Kịch bản 5 — Host thăng quyền cho khách

Tiếp nối kịch bản 4:

1. Ở cửa sổ host, nhìn danh sách **Participants** bên phải, tìm dòng `Khách` (có ký hiệu 👁)
2. Bấm nút **🎤** (di chuột vào sẽ hiện chữ *"Allow to speak"*)

✅ **Đúng:** ở cửa sổ khách, **ngay lập tức**:
- Hiện dải băng xanh *"You can now speak 🎤"*
- Cụm nút mic/camera/chia sẻ màn hình **xuất hiện**
- Khách **không phải thoát ra vào lại**

> Backend gọi API của LiveKit để cấp lại quyền ngay lúc đang chạy, trình duyệt khách nhận
> được sự kiện đổi quyền và tự cập nhật giao diện.

**Thử thêm:** bấm **⬇️** để hạ khách về khán giả — cụm nút publish biến mất trở lại.

### Kịch bản 6 — Chat, giơ tay, xoá tin

Trong phòng có ít nhất 2 người:

1. Gõ vài tin nhắn ở cả hai cửa sổ → cả hai bên đều thấy tin của nhau ngay
2. Bấm nút **✋ Raise hand** → cả hai bên thấy dòng chữ vàng *"✋ Tên raised their hand"*
3. Ở cửa sổ **host**, di chuột vào một tin nhắn → hiện chữ **delete** màu đỏ → bấm vào

✅ **Đúng:** tin nhắn biến mất ở **cả hai** cửa sổ.

**Thử độ bền:** ở cửa sổ khách, tắt Wi-Fi vài giây rồi bật lại → chat tự kết nối lại và **tải
bù những tin đã lỡ** trong lúc mất mạng.

> Trong lúc chưa kết nối, ô nhập tin **bị khoá mờ đi** — cố tình như vậy để tin nhắn không bị
> mất âm thầm.

### Kịch bản 7 — Ghi hình và xem lại

1. Trong phòng, ở cửa sổ **host**, bấm **⏺ Record**
2. Nút đổi thành **⏹ Stop recording** màu đỏ nhấp nháy
3. **Nói vài câu, bật camera, chờ khoảng 20–30 giây** (cần đủ dài mới ra file có nội dung)
4. Bấm **⏹ Stop recording**
5. **Chờ 10–20 giây** để hệ thống đóng file và tải lên kho

**Kiểm tra file có thật không:**

- Mở http://localhost:9001 (giao diện MinIO), đăng nhập `minio` / `minio12345`
- Vào thùng **`meetly-recordings`** → thư mục theo mã phòng → thấy file `.mp4`

**Xem lại trong ứng dụng:**

6. Rời phòng, về trang chính
7. Bấm **End meeting** trước đó, hoặc chờ phòng tự kết thúc → dòng meeting chuyển trạng thái `ENDED`
8. Bấm **Recordings** ở dòng đó
9. Bản ghi hiện trạng thái `COMPLETED` → bấm **▶ Play**

✅ **Đúng:** video phát được ngay trên trang.

> **Nếu trạng thái mãi là `STARTING` hoặc `FAILED`:** xem mục [Xử lý sự cố](#sự-cố-4--ghi-hình-không-ra-file).

### Kịch bản 8 — Kiểm tra bảo mật (thử phá xem có phá được không)

Những phép thử này để anh yên tâm rằng bảo vệ nằm ở phía máy chủ:

**a) Vào phòng kín mà không được mời:**
- Tạo phòng **Private meeting** bằng tài khoản Anh
- Ở cửa sổ ẩn danh, đăng ký tài khoản thứ ba rồi vào thẳng link phòng đó

✅ Phải hiện: *"You have not been invited to this meeting."*

**b) Khách vào phòng kín:**
- Ở cửa sổ ẩn danh **chưa đăng nhập**, vào link phòng kín

✅ Phải hiện: *"This meeting requires you to sign in."*

**c) Người thường bấm nút của host:**
- Trong webinar, dùng công cụ lập trình viên gọi API điều khiển bằng tài khoản khách

✅ Backend trả lỗi 403 kèm mã `NOT_MEETING_HOST`.

---

## Chạy bộ test tự động

### Test backend (47 test)

```bash
cd ~/Desktop/WorkSpace/stream-video/backend
source ~/tools/env.sh
./mvnw test
```

Kết quả mong đợi:

```
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Lần chạy đầu mất khoảng 2–4 phút (phải tải image PostgreSQL và Redis cho Testcontainers).

> **Docker phải đang chạy.** Bộ test tự khởi động PostgreSQL và Redis riêng trong container
> (Testcontainers) — không dùng chung với database đang chạy, nên **không sợ mất dữ liệu** của
> anh khi đang thử tay.

### Test frontend (9 test)

```bash
cd ~/Desktop/WorkSpace/stream-video/frontend
npm run lint     # kiểm tra chất lượng code
npm test         # chạy test
npm run build    # kiểm tra biên dịch được
```

### Test đầu-cuối qua trình duyệt (3 kịch bản)

**Yêu cầu: cả 3 phần (hạ tầng, backend, frontend) phải đang chạy.**

```bash
cd ~/Desktop/WorkSpace/stream-video/frontend
npx playwright install chromium   # chỉ cần lần đầu
npm run e2e
```

Kết quả mong đợi: `3 passed`.

Bộ này tự động làm y hệt các kịch bản anh vừa thử tay: hai người vào phòng thấy nhau, khách
webinar bị chặn phát rồi được thăng quyền, và ghi hình thật ra file MP4.

**Muốn xem tận mắt nó thao tác** (mở trình duyệt thật, chạy chậm lại):

```bash
npx playwright test --headed --project=chromium
```

**Chỉ chạy một kịch bản:**

```bash
npx playwright test webinar-roles
```

---

## Xem dữ liệu bên trong hệ thống

### Xem database

```bash
docker exec -it meetly-dev-postgres-1 psql -U meetly
```

Vào rồi gõ các lệnh sau (nhớ dấu chấm phẩy cuối):

```sql
\dt                                  -- liệt kê các bảng
SELECT email, full_name FROM users;  -- danh sách người dùng
SELECT code, title, status FROM meetings ORDER BY created_at DESC LIMIT 10;
SELECT sender_display_name, content FROM chat_messages ORDER BY created_at DESC LIMIT 10;
SELECT status, s3_key, size_bytes FROM recordings;
\q                                   -- thoát
```

### Xem file ghi hình

Mở http://localhost:9001 → đăng nhập `minio` / `minio12345` → thùng `meetly-recordings`.

### Xem log

| Muốn xem | Lệnh |
|---|---|
| Backend | Nhìn thẳng vào cửa sổ terminal đang chạy `spring-boot:run` |
| LiveKit | `docker logs -f meetly-dev-livekit-1` |
| Egress (ghi hình) | `docker logs -f meetly-dev-egress-1` |
| PostgreSQL | `docker logs -f meetly-dev-postgres-1` |

> `-f` nghĩa là theo dõi liên tục. Bấm `Ctrl + C` để thoát khỏi chế độ xem (không làm chết
> container).

### Xem các con số vận hành

```bash
curl http://localhost:8080/actuator/prometheus | head -30
```

Đây là dữ liệu thô mà Prometheus sẽ thu thập trên production: số request, độ trễ, bộ nhớ JVM.

---

## Xử lý sự cố thường gặp

### Sự cố 1 — `JAVA_HOME environment variable is not defined`

**Nguyên nhân:** quên chạy `source ~/tools/env.sh` trong cửa sổ terminal đó.

```bash
source ~/tools/env.sh
```

Nhớ: mỗi cửa sổ terminal mới đều phải chạy lại lệnh này.

### Sự cố 2 — `Port 8080 is already in use` (cổng đang bị chiếm)

Có một backend cũ còn chạy. Tìm và tắt nó:

```bash
pkill -f MeetlyApplication
```

Tương tự với cổng 5173 (frontend): tìm cửa sổ đang chạy `npm run dev` và bấm `Ctrl + C`.

### Sự cố 3 — Vào phòng nhưng không thấy video của ai

Kiểm tra lần lượt:

1. **Trình duyệt đã được cấp quyền camera chưa?** Bấm vào biểu tượng ổ khoá 🔒 cạnh thanh
   địa chỉ để xem lại.
2. **Có vào bằng `localhost` không?** Vào bằng địa chỉ IP thì trình duyệt chặn camera.
3. **LiveKit còn sống không?**
   ```bash
   curl http://localhost:7880          # phải ra chữ OK
   docker logs --tail 20 meetly-dev-livekit-1
   ```
4. **Camera có đang bị ứng dụng khác chiếm không?** (Zoom, Teams đang mở?)

### Sự cố 4 — Ghi hình không ra file

Egress là phần dễ hỏng nhất vì nó phải chạy cả một trình duyệt ẩn.

```bash
docker logs --tail 40 meetly-dev-egress-1
```

**Nếu thấy `Start signal not received`:** Egress không kết nối được vào phòng. Trên **Linux**
đã xử lý bằng cách cho Egress dùng chung mạng với máy. Trên **macOS/Windows** cách này không
có tác dụng — cách khắc phục ghi trong `README.md` mục *Bản ghi (recording)*.

**Nếu Egress không chạy:**

```bash
docker compose -f ops/compose/docker-compose.dev.yml up -d --force-recreate egress
```

**Nếu bản ghi kẹt mãi ở `STARTING`:** hệ thống sẽ tự đánh dấu hỏng sau 5 phút để anh ghi lại
được, không bị kẹt vĩnh viễn.

### Sự cố 5 — Test backend đỏ với lỗi `Could not find a valid Docker environment`

Docker chưa chạy. Bật Docker lên rồi kiểm tra:

```bash
docker ps
```

### Sự cố 6 — Muốn xoá sạch dữ liệu làm lại từ đầu

```bash
docker compose -f ops/compose/docker-compose.dev.yml down -v
docker compose -f ops/compose/docker-compose.dev.yml up -d
```

> ⚠️ Cờ `-v` **xoá toàn bộ dữ liệu**: mọi tài khoản, phòng họp, tin nhắn, file ghi hình. Lần
> khởi động sau, Flyway sẽ tạo lại bảng trống.

### Sự cố 7 — Chat không hoạt động

1. Ô nhập tin có bị mờ không? Nếu mờ nghĩa là **chưa kết nối xong** — chờ vài giây.
2. Tiêu đề khung chat có chữ *"(connecting...)"* không?
3. Redis còn sống không?
   ```bash
   docker exec meetly-dev-redis-1 redis-cli ping   # phải trả lời PONG
   ```

---

## Tắt hệ thống

Theo thứ tự ngược lại:

```bash
# Cửa sổ frontend: bấm Ctrl + C
# Cửa sổ backend:  bấm Ctrl + C

# Hạ tầng — GIỮ nguyên dữ liệu:
docker compose -f ops/compose/docker-compose.dev.yml down

# Hoặc hạ tầng — XOÁ sạch dữ liệu:
docker compose -f ops/compose/docker-compose.dev.yml down -v
```

---

## Bảng tra cứu lệnh nhanh

| Việc cần làm | Lệnh |
|---|---|
| Bật hạ tầng | `docker compose -f ops/compose/docker-compose.dev.yml up -d` |
| Xem hạ tầng đang chạy | `docker compose -f ops/compose/docker-compose.dev.yml ps` |
| Bật backend | `cd backend && source ~/tools/env.sh && ./mvnw spring-boot:run` |
| Bật frontend | `cd frontend && npm run dev` |
| Test backend | `cd backend && source ~/tools/env.sh && ./mvnw test` |
| Test frontend | `cd frontend && npm test` |
| Test qua trình duyệt | `cd frontend && npm run e2e` |
| Kiểm tra backend sống | `curl http://localhost:8080/actuator/health` |
| Vào database | `docker exec -it meetly-dev-postgres-1 psql -U meetly` |
| Xem file ghi hình | http://localhost:9001 (`minio` / `minio12345`) |
| Tắt hạ tầng | `docker compose -f ops/compose/docker-compose.dev.yml down` |
| Xoá sạch làm lại | `docker compose -f ops/compose/docker-compose.dev.yml down -v` |

---

## Tài khoản và thông số dùng khi phát triển

> ⚠️ Toàn bộ thông tin dưới đây **chỉ dùng cho máy cá nhân**. Trên production, tất cả đều
> được thay bằng giá trị bí mật lấy từ kho quản lý bí mật của nhà cung cấp đám mây.

| Nơi dùng | Tài khoản | Mật khẩu |
|---|---|---|
| PostgreSQL | `meetly` | `meetly` |
| MinIO | `minio` | `minio12345` |
| LiveKit (khoá API) | `devkey` | `meetly_dev_secret_0123456789abcdef` |

---

Quay lại: [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md) — giải thích công nghệ.
