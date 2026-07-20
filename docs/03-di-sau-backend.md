# Meetly — Đi sâu Backend (đọc code thật từng dòng)

> Tài liệu này dẫn anh qua **code thật** của backend, từng dòng một. Em giả định anh đã biết
> Spring Boot CRUD (Controller → Service → Repository → Entity), nên chỗ nào giống CRUD thường
> em lướt nhanh, chỗ nào mới em dừng lại giải thích kỹ.
>
> Đọc kèm [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md) khi gặp thuật ngữ.

---

## Mục lục

1. [Ôn lại: một request Spring Boot đi qua đâu](#1-ôn-lại-một-request-đi-qua-đâu)
2. [Các mảnh ghép mới so với CRUD thường](#2-các-mảnh-ghép-mới-so-với-crud-thường)
3. [Cấu hình bằng file YAML và class Properties](#3-cấu-hình-bằng-yaml-và-class-properties)
4. [Đăng ký tài khoản — đi từng dòng](#4-đăng-ký-tài-khoản--đi-từng-dòng)
5. [Đăng nhập và hai loại token](#5-đăng-nhập-và-hai-loại-token)
6. [Cơ chế bảo vệ request: Filter và Security](#6-cơ-chế-bảo-vệ-request)
7. [Làm mới token (refresh) và chống trộm](#7-làm-mới-token-và-chống-trộm)
8. [Tạo phòng và sinh mã phòng](#8-tạo-phòng-và-sinh-mã-phòng)
9. [Vào phòng — nơi khó nhất](#9-vào-phòng--nơi-khó-nhất)
10. [Sinh vé LiveKit](#10-sinh-vé-livekit)
11. [Xử lý lỗi tập trung](#11-xử-lý-lỗi-tập-trung)
12. [Chat qua WebSocket](#12-chat-qua-websocket)
13. [Tổng kết: bản đồ toàn bộ file](#13-bản-đồ-toàn-bộ-file)

---

## 1. Ôn lại: một request đi qua đâu

Trong Spring Boot CRUD anh quen, một request đi thế này:

```
HTTP request  →  Controller  →  Service  →  Repository  →  Database
                (nhận request)  (logic)    (câu SQL)      (lưu/lấy)
```

Meetly **vẫn giữ nguyên** cấu trúc đó. Ví dụ lấy danh sách phòng của mình:

```
GET /api/v1/meetings  →  MeetingController.listMine()
                      →  MeetingService.listMine()
                      →  MeetingRepository.findByHostId...()
                      →  bảng meetings
```

Nếu anh mở 3 file đó ra, anh sẽ thấy chúng **giống hệt** CRUD anh từng viết. Cái mới nằm ở
**những gì xảy ra TRƯỚC khi tới Controller** (xác thực, phân quyền) và **những công nghệ ngoài
database** (LiveKit, Redis, WebSocket). Đó là phần tài liệu này tập trung.

---

## 2. Các mảnh ghép mới so với CRUD thường

Trước khi đọc code, em liệt kê những "nhân vật mới" sẽ gặp, để anh không bị lạc:

| Nhân vật | Vai trò | File |
|---|---|---|
| **Filter** | Chặn request TRƯỚC Controller để kiểm tra token | `JwtAuthFilter`, `CorrelationIdFilter` |
| **SecurityConfig** | Khai báo đường nào cần đăng nhập, đường nào mở | `common/SecurityConfig.java` |
| **@ConfigurationProperties** | Đọc cấu hình từ file YAML vào một class Java | `AuthProperties`, `LiveKitProperties`... |
| **@RestControllerAdvice** | Bắt mọi lỗi ở một chỗ, trả về định dạng thống nhất | `GlobalExceptionHandler` |
| **@MessageMapping** | Như `@PostMapping` nhưng cho WebSocket/chat | `ChatController` |
| **ChannelInterceptor** | Như Filter nhưng cho WebSocket | `StompAuthChannelInterceptor` |
| **record** | Cách khai báo class chứa dữ liệu ngắn gọn (Java 17+) | Khắp nơi, ví dụ `AuthDtos` |

Em giải thích **record** ngay vì nó xuất hiện dày đặc. Trong CRUD cũ anh có thể viết:

```java
public class UserDto {
    private UUID id;
    private String email;
    // + constructor + getter + setter + equals + hashCode... (30 dòng)
}
```

Java hiện đại cho viết gọn lại thành **một dòng**:

```java
public record UserDto(UUID id, String email) {}
```

`record` tự sinh constructor, và tự sinh phương thức đọc dữ liệu — nhưng tên **không có `get`**:
lấy email là `userDto.email()` chứ không phải `userDto.getEmail()`. Dữ liệu trong record
**không sửa được sau khi tạo** (bất biến), rất hợp để truyền qua lại giữa các tầng.

---

## 3. Cấu hình bằng YAML và class Properties

### File cấu hình

Backend đọc cấu hình từ `backend/src/main/resources/application.yml`. Đây là phần liên quan
tới đăng nhập:

```yaml
meetly:
  auth:
    jwt-secret: meetly_dev_jwt_secret_min_32_chars_0123
    access-ttl: 15m
    refresh-ttl: 14d
    cookie-secure: false
```

- `jwt-secret`: chuỗi bí mật để ký token. **Chỉ server biết.** Đổi chuỗi này là mọi token cũ
  thành vô hiệu.
- `access-ttl`: access token sống 15 phút (`m` = minutes).
- `refresh-ttl`: refresh token sống 14 ngày (`d` = days).
- `cookie-secure: false`: khi phát triển dùng `http` nên để `false`. Production dùng `https`
  thì đặt `true` (cookie chỉ đi qua kết nối mã hoá).

### Đọc YAML vào class Java

Thay vì rải rác `@Value("${meetly.auth.jwt-secret}")` khắp nơi, dự án gom cấu hình vào **một
class**. Mở `common/AuthProperties.java`:

```java
@ConfigurationProperties(prefix = "meetly.auth")
public record AuthProperties(String jwtSecret, Duration accessTtl,
                             Duration refreshTtl, boolean cookieSecure) {}
```

Giải thích:

- `@ConfigurationProperties(prefix = "meetly.auth")` — bảo Spring: "lấy nhánh `meetly.auth`
  trong YAML, rót vào record này".
- Spring tự khớp tên: YAML viết `jwt-secret` (gạch ngang), Java viết `jwtSecret` (camelCase).
  Nó tự hiểu hai cái là một. Cơ chế này gọi là *relaxed binding* (khớp lỏng).
- `Duration` — kiểu dữ liệu thời lượng của Java. Spring tự đổi `"15m"` thành 15 phút. Không
  phải tự parse chuỗi.

Từ đây, chỗ nào cần cấu hình chỉ việc tiêm class này vào rồi gọi `props.jwtSecret()`. Gọn và
an toàn (viết sai tên là báo lỗi ngay lúc biên dịch).

Dự án có 4 class như vậy: `AuthProperties`, `LiveKitProperties`, `StorageProperties`,
`CorsProperties`. Tất cả được "kích hoạt" ở `MeetlyApplication.java`:

```java
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class,
        LiveKitProperties.class, StorageProperties.class})
```

---

## 4. Đăng ký tài khoản — đi từng dòng

Ta lần theo request `POST /api/v1/auth/register` từ đầu đến cuối.

### 4.1 Controller nhận request

Mở `auth/AuthController.java`:

```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
    User user = authService.register(req.email(), req.password(), req.fullName());
    return respondWithTokens(user);
}
```

Chỗ này giống CRUD anh quen, chỉ có 2 điểm đáng chú ý:

**`@Valid`** — bảo Spring kiểm tra dữ liệu đầu vào TRƯỚC khi vào hàm. Luật kiểm tra nằm ngay
trong định nghĩa `RegisterRequest` (mở `auth/AuthDtos.java`):

```java
public record RegisterRequest(@NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String password,
                              @NotBlank String fullName) {}
```

- `@NotBlank` — không được rỗng.
- `@Email` — phải đúng định dạng email.
- `@Size(min = 8, max = 72)` — mật khẩu 8–72 ký tự. (72 là giới hạn của BCrypt.)

Nếu dữ liệu sai (email thiếu, mật khẩu ngắn), Spring **không gọi vào hàm**, mà ném lỗi. Lỗi
đó được bắt ở `GlobalExceptionHandler` (mục 11) và trả về mã `VALIDATION_FAILED`.

**`ResponseEntity`** — anh dùng khi cần điều khiển cả header và body của response, không chỉ
body. Ở đây cần đặt cookie vào header (xem tiếp).

### 4.2 Service tạo user

Mở `auth/AuthService.java`, phương thức `register`:

```java
@Transactional
public User register(String email, String password, String fullName) {
    if (users.existsByEmail(email)) {
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_TAKEN,
                "Email is already registered");
    }
    User u = new User();
    u.setEmail(email);
    u.setPasswordHash(passwordEncoder.encode(password));   // ← điểm mấu chốt
    u.setFullName(fullName);
    return users.save(u);
}
```

- `@Transactional` — như anh quen: cả hàm là một giao dịch database. Có lỗi giữa chừng thì
  mọi thay đổi bị hoàn tác.
- `users.existsByEmail(email)` — kiểm tra trùng email. `users` chính là repository (`UserRepository`),
  và phương thức này Spring **tự sinh** từ tên (anh chỉ khai báo, không viết SQL).
- **`passwordEncoder.encode(password)`** — đây là chỗ khác CRUD thường. Không lưu mật khẩu
  `"secret123"` mà lưu chuỗi băm BCrypt `"$2a$10$..."`. `passwordEncoder` là một `BCryptPasswordEncoder`
  được khai báo ở `SecurityConfig` (mục 6).

### 4.3 Trả về token + cookie

Quay lại `AuthController`, phương thức dùng chung `respondWithTokens`:

```java
ResponseEntity<AuthResponse> respondWithTokens(User user) {
    AuthService.TokenPair pair = authService.issueTokens(user);

    ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, pair.rawRefreshToken())
            .httpOnly(true)                    // JavaScript không đọc được
            .secure(props.cookieSecure())      // production: chỉ đi qua HTTPS
            .path("/api/v1/auth")              // chỉ gửi cookie tới các URL auth
            .maxAge(props.refreshTtl())        // sống 14 ngày
            .sameSite("Lax")                   // chống tấn công CSRF
            .build();

    AuthResponse body = new AuthResponse(pair.accessToken(),
            new UserDto(user.getId(), user.getEmail(), user.getFullName()));

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(body);
}
```

Đây là phần **quan trọng nhất của bảo mật đăng nhập**, giải thích kỹ:

Nhớ ở tài liệu 01: có **hai token**. Đoạn code này gửi chúng đi **theo hai đường khác nhau**,
có chủ đích:

| Token | Đi bằng | Vì sao |
|---|---|---|
| **access token** | Trong body JSON (`AuthResponse`) | JavaScript cần đọc để gắn vào mỗi request |
| **refresh token** | Trong **cookie httpOnly** | JavaScript **không được** đọc, để nếu web dính lỗ hổng thì kẻ xấu không lấy được |

Các thuộc tính cookie:

- **`httpOnly(true)`** — cốt lõi. Cookie này trình duyệt tự gửi kèm request, nhưng
  `document.cookie` trong JavaScript **không nhìn thấy**. Kẻ xấu chèn được mã độc vào trang
  (lỗ hổng XSS) vẫn không trộm được refresh token.
- **`path("/api/v1/auth")`** — cookie chỉ được gửi khi gọi các URL bắt đầu bằng `/api/v1/auth`.
  Request lấy danh sách phòng (`/api/v1/meetings`) **không** kèm cookie này → giảm rủi ro lộ.
- **`sameSite("Lax")`** — chống CSRF: trang web khác không thể lén dùng cookie của anh để gọi
  API thay anh.

### 4.4 Sinh token thật

`AuthService.issueTokens`:

```java
@Transactional
public TokenPair issueTokens(User user) {
    byte[] buf = new byte[48];
    random.nextBytes(buf);                                    // 48 byte ngẫu nhiên
    String raw = Base64.getUrlEncoder().withoutPadding()
                       .encodeToString(buf);                   // → chuỗi text

    RefreshToken rt = new RefreshToken();
    rt.setUserId(user.getId());
    rt.setTokenHash(sha256(raw));                             // ← lưu BĂM, không lưu raw
    rt.setExpiresAt(Instant.now().plus(props.refreshTtl()));
    refreshTokens.save(rt);

    return new TokenPair(
        jwtService.generateAccessToken(user.getId(), user.getEmail()),   // access
        raw);                                                            // refresh (raw)
}
```

Hai loại token sinh khác nhau về bản chất:

**Access token** là **JWT** — tự chứa thông tin (userId, email, hạn dùng), ký số. Server không
lưu gì cả, chỉ cần kiểm tra chữ ký khi nhận lại.

**Refresh token** là **chuỗi ngẫu nhiên 48 byte** — KHÔNG phải JWT, không chứa thông tin gì.
Server **phải lưu** để còn thu hồi được. Nhưng lưu ý dòng `sha256(raw)`: server lưu **mã băm**
của token, không lưu token gốc. Giống như lưu mật khẩu — kẻ đọc trộm database cũng vô dụng vì
từ mã băm không suy ngược ra token gốc.

> **Vì sao access dùng JWT còn refresh dùng chuỗi ngẫu nhiên?** Access token cần kiểm tra thật
> nhanh, hàng nghìn lần mỗi giây, nên tự chứa thông tin để khỏi phải hỏi database. Refresh
> token chỉ dùng 15 phút một lần (khi access hết hạn), nên tra database một phát không sao,
> đổi lại được khả năng thu hồi.

### 4.5 JWT được ký thế nào

`auth/JwtService.java`:

```java
public String generateAccessToken(UUID userId, String email) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(userId.toString())                       // "chủ thể" của token = userId
            .claim("email", email)                            // thêm thông tin email
            .issuedAt(Date.from(now))                         // cấp lúc nào
            .expiration(Date.from(now.plus(props.accessTtl()))) // hết hạn 15 phút sau
            .signWith(key)                                    // KÝ bằng khoá bí mật
            .compact();                                        // đóng thành chuỗi
}
```

`claim` là "một mẩu thông tin" trong token. `subject` là claim đặc biệt chỉ danh tính. `signWith(key)`
là bước tạo chữ ký — `key` được tạo từ `jwt-secret` trong YAML.

Chiều ngược lại, khi nhận token về:

```java
public AccessTokenClaims parse(String token) {
    Claims c = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();
    return new AccessTokenClaims(UUID.fromString(c.getSubject()),
                                 c.get("email", String.class));
}
```

`verifyWith(key)` — kiểm tra chữ ký bằng đúng khoá đã ký. Nếu token bị sửa dù chỉ một ký tự,
hoặc hết hạn, dòng này **ném exception** ngay. Không có chuyện token giả lọt qua.

> Đây là toàn bộ "phép màu" của JWT: server không cần nhớ đã cấp token nào, chỉ cần chữ ký
> đúng là tin. Test kiểm tra điều này ở `JwtServiceTest.java`: token do khoá khác ký, hoặc
> chuỗi rác, đều bị từ chối.

---

## 5. Đăng nhập và hai loại token

`POST /api/v1/auth/login` gần giống register, chỉ khác bước kiểm mật khẩu. `AuthService.authenticate`:

```java
@Transactional(readOnly = true)
public User authenticate(String email, String password) {
    return users.findByEmail(email)
            .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                    ErrorCode.INVALID_CREDENTIALS, "Incorrect email or password"));
}
```

Đọc như tiếng Anh: "tìm user theo email, **lọc** những ai có mật khẩu khớp, nếu không còn ai
thì ném lỗi".

- `passwordEncoder.matches(password, hash)` — BCrypt băm mật khẩu vừa nhập rồi so với mã băm
  đã lưu. Không giải mã ngược (không thể), mà băm xuôi rồi so sánh.
- **Chi tiết bảo mật tinh tế:** dù sai email hay sai mật khẩu, đều trả về **cùng một thông
  báo** "Incorrect email or password". Nếu phân biệt ("email không tồn tại" vs "sai mật khẩu")
  thì kẻ xấu dò được email nào đã đăng ký.
- `@Transactional(readOnly = true)` — chỉ đọc, không ghi. Đánh dấu vậy giúp database tối ưu.

---

## 6. Cơ chế bảo vệ request

Đây là phần khác CRUD thường nhiều nhất. Câu hỏi: khi anh gọi `GET /api/v1/meetings` kèm
access token, làm sao server biết anh là ai?

### 6.1 Filter — trạm kiểm soát trước Controller

Mọi request đi qua một **chuỗi filter** trước khi tới Controller. Meetly có 2 filter tự viết.

**`common/CorrelationIdFilter.java`** — gắn mã theo dõi:

```java
@Override
protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                FilterChain chain) throws ... {
    String correlationId = req.getHeader(HEADER);
    if (correlationId == null || correlationId.isBlank()) {
        correlationId = UUID.randomUUID().toString();   // chưa có thì tự sinh
    }
    MDC.put(MDC_KEY, correlationId);                     // nhét vào "ngữ cảnh log"
    res.setHeader(HEADER, correlationId);               // trả lại cho client
    try {
        chain.doFilter(req, res);                       // ← cho request đi tiếp
    } finally {
        MDC.remove(MDC_KEY);                            // dọn dẹp
    }
}
```

`MDC` (Mapped Diagnostic Context) là một "cuốn sổ tạm" gắn với luồng xử lý hiện tại. Đặt
`correlationId` vào đó thì **mọi dòng log** sinh ra trong lúc xử lý request này đều tự động
kèm mã đó. Khi có sự cố, lọc log theo mã là thấy toàn bộ hành trình của đúng request đó.

`chain.doFilter(req, res)` là dòng quan trọng: nó nói "xong phần tôi, cho request đi tiếp tới
filter sau, rồi tới Controller". Khối `finally` chạy SAU khi Controller trả kết quả — dọn sổ
tạm, vì một luồng sẽ được tái sử dụng cho request khác.

**`auth/JwtAuthFilter.java`** — kiểm token và nhận diện người dùng:

```java
@Override
protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                FilterChain chain) throws ... {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
        try {
            Object principal = jwtService.parsePrincipal(header.substring(7));  // bỏ "Bearer "
            String role = principal instanceof GuestUser ? "ROLE_GUEST" : "ROLE_USER";
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);   // ← "ghi danh"
        } catch (JwtException | IllegalArgumentException ignored) {
            // token hỏng → đi tiếp KHÔNG ghi danh; request coi như chưa đăng nhập
        }
    }
    chain.doFilter(req, res);
}
```

Từng bước:

1. Lấy header `Authorization`. Client gửi kèm dạng `Authorization: Bearer eyJhbGc...`.
2. `header.substring(7)` — cắt bỏ 7 ký tự đầu `"Bearer "`, còn lại là token.
3. `jwtService.parsePrincipal(...)` — xác thực chữ ký và lấy ra danh tính (userId + email, hoặc
   thông tin khách).
4. `SecurityContextHolder...setAuthentication(auth)` — **"ghi danh" người dùng vào ngữ cảnh
   bảo mật của Spring**. Từ đây, Controller có thể lấy ra bằng `@AuthenticationPrincipal`.
5. Nếu token hỏng, khối `catch` **cố tình nuốt lỗi** và đi tiếp mà không ghi danh. Request sẽ
   bị chặn ở bước sau (nếu đường đó cần đăng nhập). Vì sao không chặn luôn ở đây? Vì có những
   đường **không cần** đăng nhập (như trang login) — quyết định chặn hay không là việc của
   `SecurityConfig`, không phải của filter này.

### 6.2 SecurityConfig — luật ai được vào đâu

`common/SecurityConfig.java`, trái tim của phân quyền HTTP:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/auth/**", "/actuator/health", "/actuator/health/**",
                    "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/meetings/*/join").permitAll()
            .requestMatchers("/api/v1/livekit/webhook").permitAll()
            .requestMatchers("/ws/**").permitAll()
            .anyRequest().authenticated())          // ← mọi đường khác PHẢI đăng nhập
        .exceptionHandling(...)
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

Đọc từng dòng:

- **`csrf().disable()`** — tắt bảo vệ CSRF. Nghe đáng sợ nhưng đúng ở đây: CSRF chỉ nguy hiểm
  khi dùng cookie để xác thực. Ta xác thực bằng token trong header (không phải cookie), nên
  CSRF không áp dụng cho các API thường. (Refresh cookie đã được bảo vệ riêng bằng `SameSite=Lax`.)
- **`SessionCreationPolicy.STATELESS`** — bảo Spring: "đừng tạo session, đừng nhớ ai đăng nhập".
  Mỗi request tự mang token của nó. Đây chính là điều khiến chạy nhiều server song song không
  sao — không server nào phải "nhớ" gì.
- **`permitAll()`** — các đường cho vào tự do không cần đăng nhập:
  - `/api/v1/auth/**` — trang đăng ký/đăng nhập (chưa đăng nhập thì làm sao có token).
  - `/api/v1/meetings/*/join` — vào phòng, vì **khách vãng lai** cũng phải gọi được (họ chưa
    có tài khoản).
  - `/api/v1/livekit/webhook` — LiveKit gọi vào, nó đâu có tài khoản; bảo mật bằng chữ ký riêng.
  - `/ws/**` — bắt tay WebSocket; xác thực thật diễn ra lúc kết nối STOMP (mục 12).
  - `/actuator/health`, `/actuator/prometheus` — để Kubernetes và Prometheus thăm dò.
- **`.anyRequest().authenticated()`** — TẤT CẢ đường còn lại bắt buộc đăng nhập. Đây là "mặc
  định an toàn": quên khai báo một đường thì nó tự động bị bảo vệ, chứ không bị hở.
- **`.addFilterBefore(jwtAuthFilter, ...)`** — chèn filter kiểm token vào đúng vị trí trong
  chuỗi.

### 6.3 Controller lấy ra người dùng

Sau khi filter đã "ghi danh", Controller lấy người dùng cực gọn. Mở `user/UserController.java`:

```java
@GetMapping("/me")
public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
    User u = users.findById(principal.id()).orElseThrow();
    return new UserDto(u.getId(), u.getEmail(), u.getFullName());
}
```

`@AuthenticationPrincipal AuthenticatedUser principal` — Spring **tự tiêm** vào đây cái danh
tính mà `JwtAuthFilter` đã ghi danh. Anh không phải tự đọc token, tự parse — filter làm hết
rồi. Từ đây chỉ việc dùng `principal.id()`.

> **Đây là toàn bộ luồng xác thực.** Ghép lại: Filter đọc token → ghi danh vào ngữ cảnh →
> SecurityConfig quyết định đường này có cần đăng nhập không → Controller lấy danh tính qua
> `@AuthenticationPrincipal`. Hiểu 3 mảnh này là hiểu bảo mật của toàn bộ backend.

---

## 7. Làm mới token và chống trộm

Access token chết sau 15 phút. Client dùng refresh token (trong cookie) để xin cái mới, qua
`POST /api/v1/auth/refresh`. Logic ở `AuthService.rotate` — đây là code bảo mật tinh vi nhất
dự án, đọc chậm:

```java
@Transactional(noRollbackFor = ApiException.class)
public User rotate(String rawRefreshToken) {
    RefreshToken current = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
            .orElseThrow(this::invalidRefreshToken);

    if (current.getRevokedAt() != null) {
        // Token đã bị thu hồi mà vẫn có người dùng lại → dấu hiệu bị đánh cắp
        int revoked = revokeAllSessions(current.getUserId());
        log.warn("Refresh token reuse detected for user {} — revoked {} sessions",
                current.getUserId(), revoked);
        throw invalidRefreshToken();
    }
    if (!current.isActive()) throw invalidRefreshToken();   // hết hạn tự nhiên

    current.setRevokedAt(Instant.now());                     // thu hồi token vừa dùng
    return users.findById(current.getUserId()).orElseThrow(...);
}
```

Kịch bản bình thường: tìm thấy token, chưa bị thu hồi, chưa hết hạn → thu hồi nó (đánh dấu
`revokedAt`) và trả về user để cấp cặp token mới. Đây là **rotation**: token cũ dùng một lần
rồi bỏ.

Kịch bản bị đánh cắp — phần thông minh:

> An đăng nhập, có refresh token X. Kẻ trộm chép được X.
> An dùng X để refresh → X bị thu hồi, An nhận token Y mới.
> Kẻ trộm (chậm chân) dùng X → hệ thống thấy **X đã bị thu hồi mà vẫn có người dùng**.
> → Chắc chắn có gì bất thường. Hệ thống **thu hồi TẤT CẢ token của An** (`revokeAllSessions`),
>   buộc An đăng nhập lại. Kẻ trộm mất luôn Y.

Nếu không có cơ chế này, kẻ trộm dùng X trước thì An bị đăng xuất còn kẻ trộm ung dung dùng Y.

**Cái bẫy đã suýt làm hỏng chính bản vá này** (đáng nhớ):

Chú ý `@Transactional(noRollbackFor = ApiException.class)`. Bình thường, khi một hàm
`@Transactional` **ném exception**, Spring **hoàn tác mọi thay đổi database** trong hàm đó. Ở
đây, nhánh chống trộm vừa `revokeAllSessions(...)` (ghi database) vừa `throw invalidRefreshToken()`.
Nếu để mặc định, cái `throw` sẽ **hoàn tác luôn việc thu hồi** — bản vá thành vô nghĩa, kẻ
trộm vẫn giữ nguyên phiên. `noRollbackFor` bảo Spring: "gặp `ApiException` thì ĐỪNG hoàn tác".

> Lỗi này ban đầu không lộ ra vì API vẫn trả 401 đúng như mong đợi. Chỉ có **test** viết ra để
> kiểm tra "sau khi bị đánh cắp, token mới có còn dùng được không" mới bắt được. Xem
> `RefreshTokenReuseIT.java`.

### Dọn rác token

Bảng `refresh_tokens` mỗi lần đăng nhập/refresh lại thêm một dòng → phình mãi. `RefreshTokenCleanupJob.java`:

```java
@Scheduled(cron = "0 30 3 * * *")   // 03:30 mỗi ngày
@Transactional
public void purgeExpired() {
    int removed = refreshTokens.deleteByExpiresAtBefore(Instant.now());
    if (removed > 0) log.info("Purged {} expired refresh tokens", removed);
}
```

`@Scheduled(cron = ...)` — chạy tự động theo lịch (đây là 3h30 sáng mỗi ngày, giờ vắng). Chỉ
xoá token **đã hết hạn**. Token đã thu hồi nhưng chưa hết hạn thì **giữ lại** — vì cơ chế
chống trộm ở trên cần chúng để phát hiện việc dùng lại.

---

## 8. Tạo phòng và sinh mã phòng

`POST /api/v1/meetings` là CRUD gần như thuần. Điểm mới duy nhất: **sinh mã phòng duy nhất**.
`meeting/MeetingCodeGenerator.java`:

```java
private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();
private final SecureRandom random = new SecureRandom();

public String newCode() {
    return segment(3) + "-" + segment(4) + "-" + segment(3);   // abc-defg-hij
}
```

Dùng `SecureRandom` (ngẫu nhiên chất lượng cao, khó đoán) chứ không phải `Random` thường — để
người ta không đoán được mã phòng của người khác.

`MeetingService`:

```java
private String uniqueCode() {
    for (int i = 0; i < 5; i++) {
        String code = codeGenerator.newCode();
        if (!meetings.existsByCode(code)) return code;   // chưa trùng thì dùng
    }
    throw new IllegalStateException("Could not generate a unique room code after 5 attempts");
}
```

Sinh mã, kiểm tra trùng trong database, trùng thì sinh lại (tối đa 5 lần). Với 26³ × 26⁴ ≈
8 tỷ tổ hợp, khả năng trùng gần như bằng không, nhưng vẫn kiểm tra cho chắc.

---

## 9. Vào phòng — nơi khó nhất

`POST /api/v1/meetings/{code}/join` là nơi hội tụ mọi thứ: phân quyền, thời gian, và sinh vé.
`MeetingService.join`:

```java
@Transactional
public MeetingDtos.JoinResponse join(String code, UUID userId) {
    Meeting m = getByCode(code);                        // (1) tìm phòng, không thấy → 404
    var user = users.findById(userId).orElseThrow();

    MeetingRole role = memberService.resolveRole(m, userId, user.getEmail())   // (2) xác định vai
            .orElseGet(() -> {
                if (m.getRoomType() == RoomType.WEBINAR) return MeetingRole.ATTENDEE;   // (3)
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER,
                        "You have not been invited to this meeting");            // (4)
            });

    validateJoinable(m, role == MeetingRole.HOST);      // (5) kiểm trạng thái + giờ
    String token = liveKitTokenService.createToken(     // (6) sinh vé
            m.getCode(), userId.toString(), user.getFullName(), role, tokenExpiry(m));
    return new MeetingDtos.JoinResponse(m.getId(), liveKitTokenService.wsUrl(),
            token, role.name(), null);                  // (7) trả về
}
```

Từng bước có đánh số:

**(2) Xác định vai** — `resolveRole` trong `MemberService`:

```java
public Optional<MeetingRole> resolveRole(Meeting meeting, UUID userId, String email) {
    if (meeting.getHostId().equals(userId)) return Optional.of(MeetingRole.HOST);   // chủ phòng
    Optional<MeetingMember> byUser = members.findByMeetingIdAndUserId(...);
    if (byUser.isPresent()) return byUser.map(MeetingMember::getRole);              // đã là member
    Optional<MeetingMember> byEmail = members.findByMeetingIdAndInvitedEmail(...);
    byEmail.ifPresent(mm -> mm.setUserId(userId));                                  // mời bằng email
    return byEmail.map(MeetingMember::getRole);
}
```

Thứ tự ưu tiên: chủ phòng → HOST; đã có trong danh sách theo userId → vai đã gán; được mời
bằng email (lần đầu vào) → gán userId luôn cho lần sau. Không thuộc nhóm nào → trả về rỗng
(`Optional.empty`).

**(3)(4) Xử lý người lạ** — nếu `resolveRole` trả rỗng (người lạ):
- Phòng **WEBINAR** (mở) → cho vào làm **ATTENDEE** (khán giả).
- Phòng **MEETING** (kín) → **từ chối** với `NOT_A_MEMBER`.

**(5) Kiểm tra vào được không** — `validateJoinable`:

```java
void validateJoinable(Meeting m, boolean isHost) {
    if (m.getStatus() == MeetingStatus.ENDED || m.getStatus() == MeetingStatus.CANCELLED) {
        throw new ApiException(..., ErrorCode.MEETING_ENDED, ...);       // đã kết thúc
    }
    Instant earliestJoin = m.getScheduledStartAt().minus(15, ChronoUnit.MINUTES);
    if (!isHost && Instant.now().isBefore(earliestJoin)) {
        throw new ApiException(..., ErrorCode.MEETING_NOT_STARTED, ...); // quá sớm
    }
}
```

Phòng đã kết thúc/huỷ → chặn. Chưa tới giờ (được vào sớm tối đa 15 phút) → chặn, **nhưng host
được miễn** (host cần vào sớm chuẩn bị).

**(6) Sinh vé** — gọi sang `LiveKitTokenService`, xem mục 10.

---

## 10. Sinh vé LiveKit

`livekit/LiveKitTokenService.java` — file nhỏ nhưng **nhạy cảm bảo mật nhất dự án**, vì nó
quyết định ai phát được video:

```java
public String createToken(String roomCode, String identity, String displayName,
                          MeetingRole role, Instant expiresAt) {
    AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());   // (1)
    token.setIdentity(identity);
    token.setName(displayName);
    token.setExpiration(Date.from(expiresAt));

    boolean canPublish = role != MeetingRole.ATTENDEE;                        // (2)
    token.addGrants(new RoomJoin(true), new RoomName(roomCode),
            new CanPublish(canPublish), new CanSubscribe(true),
            new CanPublishData(false));                                       // (3)
    if (role == MeetingRole.HOST) token.addGrants(new RoomAdmin(true));       // (4)

    return token.toJwt();                                                     // (5)
}
```

**(1)** Vé được tạo với `apiKey` và `apiSecret` — khoá bí mật mà **chỉ backend và LiveKit
chia sẻ**. Đây là chỗ ký số. Người dùng không có khoá này nên không tự chế được vé.

**(2)** Đây là **một dòng quyết định toàn bộ hệ thống phân quyền**:
```java
boolean canPublish = role != MeetingRole.ATTENDEE;
```
Ai không phải ATTENDEE (tức HOST và SPEAKER) thì `canPublish = true`. ATTENDEE thì `false`.

**(3)** Các quyền (grants) được nhét vào vé:
- `RoomJoin(true)` — được vào phòng.
- `RoomName(roomCode)` — vé này **chỉ dùng cho đúng phòng này**. Không mang vé phòng A vào
  phòng B được.
- `CanPublish(canPublish)` — **được phát video/tiếng hay không**. Đây là điểm mấu chốt.
- `CanSubscribe(true)` — được xem/nghe người khác (mọi vai đều được).
- `CanPublishData(false)` — không được gửi dữ liệu qua kênh của LiveKit. Vì sao? Vì chat đi
  qua backend của ta (để lưu lịch sử, kiểm duyệt), không đi qua LiveKit.

**(4)** Chỉ HOST thêm quyền `RoomAdmin(true)` — quyền quản trị phòng: tắt mic người khác,
đuổi người, kết thúc phòng.

**(5)** `token.toJwt()` — đóng gói tất cả thành chuỗi JWT có chữ ký.

> **Vì sao đây là bảo mật thật, không phải ẩn nút:** khi trình duyệt khách nối vào LiveKit và
> đưa vé này, LiveKit đọc `CanPublish(false)` từ vé đã ký, và **từ chối nhận video** của họ.
> Kể cả khách dùng công cụ lập trình viên bật nút camera lên, LiveKit vẫn chặn ở phía máy chủ.
> Vé không sửa được vì sửa là hỏng chữ ký.
>
> Test kiểm tra từng vai ở `LiveKitTokenServiceTest.java`: SPEAKER có `canPublish=true` không
> `roomAdmin`, ATTENDEE có `canPublish=false`, HOST có cả `roomAdmin`.

---

## 11. Xử lý lỗi tập trung

Trong CRUD thường, mỗi controller tự xử lý lỗi kiểu `try/catch` rải rác. Meetly gom về **một
chỗ** bằng `@RestControllerAdvice`. Mở `common/GlobalExceptionHandler.java`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode().name());
        return pd;
    }
    ...
}
```

`@RestControllerAdvice` là một "lớp bao" toàn cục: bất kỳ controller nào ném exception, nó
bắt được. `@ExceptionHandler(ApiException.class)` — "khi có `ApiException`, chạy hàm này".

`ApiException` là exception tự định nghĩa của dự án (`common/ApiException.java`), mang theo 3
thứ: mã HTTP (403, 404...), mã lỗi ổn định (`NOT_A_MEMBER`...), và câu thông báo. Nhờ vậy, ở
bất kỳ đâu trong service chỉ cần:

```java
throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NOT_A_MEMBER, "...");
```

và `GlobalExceptionHandler` tự biến nó thành response JSON đúng chuẩn RFC 7807 (xem tài liệu 01).

**Điểm đã sửa quan trọng** — phân biệt lỗi của client và lỗi của server:

```java
@ExceptionHandler({
        HttpMessageNotReadableException.class,       // JSON gửi lên bị hỏng
        MethodArgumentTypeMismatchException.class,   // tham số sai kiểu (chờ số, gửi chữ)
        MissingServletRequestParameterException.class
})
ProblemDetail handleBadRequest(Exception ex) {
    return ...HttpStatus.BAD_REQUEST...              // 400, KHÔNG phải 500
}
```

Nếu không có khối này, những lỗi do client gửi sai sẽ rơi vào khối bắt-tất-cả và trả **500**.
Mà 500 nghĩa là "server hỏng" → kích hoạt báo động gọi người trực lúc nửa đêm, dù thực ra chỉ
là ai đó gõ sai. Giờ chúng trả **400** ("anh gửi sai"), đúng bản chất.

---

## 12. Chat qua WebSocket

Chat không dùng Controller REST thường mà dùng cơ chế WebSocket + STOMP.

### 12.1 Khai báo WebSocket

`chat/WebSocketConfig.java`:

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws")
            .setAllowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new));
}

@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");                     // client NGHE ở /topic/...
    registry.setApplicationDestinationPrefixes("/app");        // client GỬI tới /app/...
}
```

- `/ws` — địa chỉ trình duyệt kết nối WebSocket vào.
- `/topic/...` — kênh client **đăng ký nghe** (ví dụ `/topic/meetings/{id}/chat`).
- `/app/...` — địa chỉ client **gửi tin** tới (ví dụ `/app/meetings/{id}/chat`).

### 12.2 Nhận tin nhắn

`chat/ChatController.java` (đã đọc ở đầu):

```java
@Controller
public class ChatController {
    @MessageMapping("/meetings/{meetingId}/chat")
    public void send(@DestinationVariable UUID meetingId,
                     @Payload SendChatRequest req,
                     Principal principal) {
        Object user = ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        ChatMessageType type = req.type() == ChatMessageType.RAISE_HAND
                ? ChatMessageType.RAISE_HAND : ChatMessageType.TEXT;
        chatService.saveAndPublish(meetingId, user, req.content(), type);
    }
}
```

Để ý sự tương đồng với REST:

| REST thường | WebSocket/STOMP |
|---|---|
| `@RestController` | `@Controller` |
| `@PostMapping("/...")` | `@MessageMapping("/...")` |
| `@PathVariable` | `@DestinationVariable` |
| `@RequestBody` | `@Payload` |

Anh đã quen REST thì đọc cái này thấy quen ngay. Khác biệt: nó xử lý tin đến qua kết nối
WebSocket đang mở sẵn, không phải request HTTP mới.

`type() == RAISE_HAND ? RAISE_HAND : TEXT` — chốt chặn: client chỉ được gửi loại TEXT hoặc
RAISE_HAND. Loại `SYSTEM` (thông báo hệ thống) chỉ server tự sinh, client không giả mạo được.

### 12.3 Xác thực WebSocket — ChannelInterceptor

WebSocket không đi qua `JwtAuthFilter` (đó là filter cho HTTP). Nó có "trạm kiểm soát" riêng:
`chat/StompAuthChannelInterceptor.java`. Nó kiểm 2 thời điểm:

```java
if (StompCommand.CONNECT.equals(accessor.getCommand())) {          // lúc KẾT NỐI
    String header = accessor.getFirstNativeHeader("Authorization");
    Object principal = jwtService.parsePrincipal(header.substring(7));
    accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, ...));
}

if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {         // lúc ĐĂNG KÝ NGHE
    Matcher m = CHAT_TOPIC.matcher(destination);   // /topic/meetings/{id}/chat
    accessGuard.check(auth.getPrincipal(), UUID.fromString(m.group(1)));
}
```

- Lúc **CONNECT** (mở kết nối): kiểm token, xác định người dùng.
- Lúc **SUBSCRIBE** (đăng ký nghe một phòng): kiểm quyền — người này có được nghe chat phòng
  này không?

> **Vì sao phải kiểm cả lúc SUBSCRIBE?** Nếu chỉ kiểm lúc CONNECT, một khách của phòng A có
> thể kết nối hợp lệ rồi **đăng ký nghe lén chat của phòng B**. Kiểm lúc SUBSCRIBE chặn đúng
> lỗ hổng đó. `ChatAccessGuard.check` là "nguồn sự thật duy nhất" về quyền chat, dùng chung ở
> cả 3 nơi: đăng ký nghe, gửi tin, và đọc lịch sử.

### 12.4 Lưu và phát tin ra nhiều server

`chat/ChatService.saveAndPublish` — đã giải thích luồng ở tài liệu 01 mục 5.3:

```java
public void saveAndPublish(UUID meetingId, Object principal, String content, ChatMessageType type) {
    accessGuard.check(principal, meetingId);        // (1) kiểm quyền lần nữa
    ...
    chatMessages.save(msg);                          // (2) lưu PostgreSQL
    publish(meetingId, ChatEvent.message(...));      // (3) phát lên Redis
}

void publish(UUID meetingId, ChatEvent event) {
    redis.convertAndSend("chat:" + meetingId, objectMapper.writeValueAsString(event));
}
```

(1) Kiểm quyền lần nữa (không tin tưởng chỉ vì đã qua SUBSCRIBE). (2) Lưu database để sau còn
xem lại. (3) Phát lên kênh Redis `chat:<id phòng>`.

Ở đầu kia, `chat/RedisChatRelay.java` lắng nghe kênh Redis và đẩy tin xuống những client đang
kết nối với **chính server này**:

```java
container.addMessageListener(chatListener(), new PatternTopic("chat:*"));   // nghe mọi kênh chat:*
...
simp.convertAndSend("/topic/meetings/" + meetingId + "/chat", event);       // đẩy xuống client
```

Nhờ vòng "Redis phát → mọi server nghe → mỗi server đẩy cho client của mình", tin nhắn tới
được mọi người dù họ nối vào server khác nhau. Đây là điều đã kiểm chứng thật bằng cách chạy
2 server ở cổng 8080 và 8081.

---

## 13. Bản đồ toàn bộ file

Giờ anh đã hiểu các cơ chế, đây là bản đồ để tra khi cần. Mỗi module là một thư mục trong
`backend/src/main/java/com/meetly/`.

### `auth/` — xác thực

| File | Việc |
|---|---|
| `AuthController` | Nhận `/register`, `/login`, `/refresh`, `/logout` |
| `AuthService` | Logic: tạo user, kiểm mật khẩu, sinh/xoay token, chống trộm |
| `JwtService` | Ký và kiểm JWT (access token + vé khách) |
| `JwtAuthFilter` | Đọc token từ mỗi request HTTP, ghi danh người dùng |
| `AuthenticatedUser` | Bản ghi danh tính người đã đăng nhập (record) |
| `GuestUser` | Bản ghi danh tính khách (record) |
| `RefreshToken` | Entity bảng `refresh_tokens` |
| `RefreshTokenCleanupJob` | Dọn token hết hạn 3h30 sáng |
| `AuthDtos` | Các record request/response (RegisterRequest, AuthResponse...) |

### `meeting/` — phòng họp (module lớn nhất)

| File | Việc |
|---|---|
| `Meeting` | Entity bảng `meetings` |
| `MeetingMember` | Entity bảng `meeting_members` (ai được mời, vai gì) |
| `ParticipantSession` | Entity bảng `participant_sessions` (điểm danh) |
| `MeetingController` | CRUD phòng: tạo, sửa, xoá, xem |
| `MeetingService` | Logic tạo phòng, **vào phòng** (mục 9) |
| `MeetingCodeGenerator` | Sinh mã `abc-defg-hij` |
| `MemberController` / `MemberService` | Quản lý danh sách mời, **xác định vai** |
| `JoinController` | Nhận `/join` (cả người đăng nhập và khách) |
| `ControlController` | Nút của host: tắt mic, thăng/hạ quyền, đuổi, kết thúc |
| `MeetingDtos` | Các record request/response |

### `livekit/` — cầu nối tới máy chủ video

| File | Việc |
|---|---|
| `LiveKitTokenService` | **Sinh vé phân quyền** (mục 10) — nhạy cảm nhất |
| `LiveKitProperties` | Đọc cấu hình LiveKit từ YAML |
| `RoomControlService` | Gọi API LiveKit để tắt mic/đuổi/đổi quyền runtime |
| `WebhookController` / `WebhookHandler` | Nhận sự kiện LiveKit gọi ngược về |

### `chat/` — tin nhắn realtime

| File | Việc |
|---|---|
| `WebSocketConfig` | Khai báo endpoint `/ws`, kênh `/topic`, `/app` |
| `StompAuthChannelInterceptor` | Xác thực WebSocket lúc CONNECT + SUBSCRIBE |
| `ChatAccessGuard` | Nguồn sự thật duy nhất về quyền chat |
| `ChatController` | Nhận tin qua `@MessageMapping` |
| `ChatService` | Lưu DB + phát lên Redis |
| `RedisChatRelay` | Nghe Redis, đẩy tin xuống client |
| `ChatRestController` | Đọc lịch sử, host xoá tin (qua REST) |
| `ChatMessage` | Entity bảng `chat_messages` |

### `recording/` — ghi hình

| File | Việc |
|---|---|
| `RecordingController` / `RecordingService` | Bắt đầu/dừng ghi, cấp link xem lại |
| `EgressClient` | Gọi API Egress của LiveKit |
| `StorageService` | Tạo presigned URL cho S3/MinIO |
| `Recording` | Entity bảng `recordings` |

### `common/` — dùng chung

| File | Việc |
|---|---|
| `SecurityConfig` | Luật đường nào cần đăng nhập (mục 6.2) |
| `GlobalExceptionHandler` | Bắt lỗi tập trung, trả RFC 7807 (mục 11) |
| `ApiException` / `ErrorCode` | Exception + danh sách mã lỗi ổn định |
| `CorrelationIdFilter` | Gắn mã theo dõi cho mỗi request |
| `AuthProperties` / `CorsProperties` | Đọc cấu hình từ YAML |

### `user/` — người dùng

| File | Việc |
|---|---|
| `User` | Entity bảng `users` |
| `UserController` | `GET /users/me` |

---

## Tự kiểm tra hiểu bài

Nếu trả lời được những câu này mà không cần tra lại, anh đã nắm chắc backend:

1. Vì sao access token đi trong body JSON còn refresh token đi trong cookie httpOnly?
2. Dòng code nào quyết định một người có phát được video hay không? Ở file nào?
3. Nếu bỏ `@Transactional(noRollbackFor = ApiException.class)` ở hàm `rotate`, lỗ hổng gì xuất hiện?
4. Vì sao chat phải phát qua Redis thay vì server tự đẩy thẳng cho client?
5. Khách của phòng A làm sao bị chặn không nghe lén được chat phòng B?

*(Đáp án nằm rải trong tài liệu — mục 4.3, mục 10, mục 7, mục 12.4, mục 12.3.)*

---

Tiếp theo: [04-di-sau-frontend.md](04-di-sau-frontend.md) — đọc code React từng phần.
