# Meetly — Đi sâu Frontend (đọc code React từng phần)

> Tài liệu này dẫn anh qua code React thật. Em giả định anh biết React cơ bản: component,
> `useState`, `useEffect`, props. Chỗ nào mới (TanStack Query, Zustand, interceptor, WebSocket)
> em dừng lại giải thích kỹ.
>
> Đọc kèm [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md) và
> [03-di-sau-backend.md](03-di-sau-backend.md).

---

## Mục lục

1. [Bản đồ thư mục](#1-bản-đồ-thư-mục)
2. [Điểm khởi đầu: main.tsx và App.tsx](#2-điểm-khởi-đầu)
3. [TypeScript trong dự án — đủ dùng](#3-typescript-đủ-dùng)
4. [Quản lý trạng thái đăng nhập: Zustand](#4-quản-lý-trạng-thái-đăng-nhập-zustand)
5. [Gọi API: axios và interceptor tự làm mới token](#5-gọi-api-và-interceptor)
6. [Lấy dữ liệu từ server: TanStack Query](#6-lấy-dữ-liệu-tanstack-query)
7. [Bảo vệ trang: ProtectedRoute](#7-bảo-vệ-trang)
8. [Vào phòng và render video LiveKit](#8-vào-phòng-và-render-video)
9. [Chat realtime: hook WebSocket tự viết](#9-chat-realtime)
10. [Thanh điều khiển theo vai trò](#10-thanh-điều-khiển-theo-vai-trò)
11. [Tổng kết: dữ liệu chảy thế nào](#11-dữ-liệu-chảy-thế-nào)

---

## 1. Bản đồ thư mục

```
frontend/src/
├── main.tsx              — điểm khởi động, gắn React vào trang HTML
├── App.tsx               — khai báo các trang (routing)
├── index.css             — nạp Tailwind
├── api/
│   ├── client.ts         — axios + tự động làm mới token khi hết hạn
│   └── types.ts          — các kiểu dữ liệu khớp với backend
├── stores/
│   ├── authStore.ts      — ai đang đăng nhập (Zustand)
│   └── roomStore.ts      — thông tin phòng đang vào
├── components/
│   └── ProtectedRoute.tsx — chặn trang cần đăng nhập
└── features/             — chia theo tính năng
    ├── auth/             — LoginPage, RegisterPage, useAuth
    ├── meetings/         — DashboardPage, MembersDialog, meetingApi
    ├── room/             — ★ lõi: PreJoin, Room, ChatPanel, ControlBar...
    └── recordings/       — trang xem lại bản ghi
```

Chia **theo tính năng** giống backend: mọi thứ về phòng họp nằm trong `room/`.

---

## 2. Điểm khởi đầu

### main.tsx — gắn React vào trang

```tsx
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

Chuyện quen thuộc: tìm thẻ `<div id="root">` trong `index.html`, render `<App/>` vào đó.
`StrictMode` là chế độ giúp React cảnh báo sớm các lỗi tiềm ẩn khi phát triển.

### App.tsx — khai báo các trang

```tsx
export default function App() {
  useEffect(() => {
    void bootstrapAuth();          // (1) khôi phục đăng nhập khi mở app
  }, []);

  return (
    <QueryClientProvider client={queryClient}>     {/* (2) bật TanStack Query */}
      <BrowserRouter>                              {/* (3) bật routing */}
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/m/:code" element={<PreJoinPage />} />       {/* (4) mở cho khách */}
          <Route path="/m/:code/room" element={<RoomPage />} />
          <Route element={<ProtectedRoute />}>                      {/* (5) cần đăng nhập */}
            <Route path="/" element={<DashboardPage />} />
            <Route path="/recordings/:meetingId" element={<RecordingsPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
```

- **(1)** `bootstrapAuth()` chạy một lần khi mở app — thử dùng refresh cookie để lấy lại phiên
  đăng nhập. Đây là lý do F5 không bị đá ra (mục 5).
- **(2)** `QueryClientProvider` — bọc ngoài để mọi component dùng được TanStack Query.
- **(3)** `BrowserRouter` + `Routes` + `Route` — react-router, ánh xạ URL → component. Anh có
  thể đã quen.
- **(4)** Chú ý: `/m/:code` (vào phòng) nằm **NGOÀI** `ProtectedRoute` — cố ý, để **khách chưa
  đăng nhập** cũng mở được link phòng webinar.
- **(5)** `/` (dashboard) và `/recordings/...` nằm **TRONG** `ProtectedRoute` — bắt buộc đăng nhập.

`:code` là **tham số động** — URL `/m/abc-defg-hij` thì `code = "abc-defg-hij"`, component lấy
ra bằng `useParams()`.

---

## 3. TypeScript đủ dùng

TypeScript = JavaScript + kiểu dữ liệu. Anh chỉ cần hiểu vài cú pháp là đọc được toàn bộ dự án.

**Khai báo kiểu cho biến:**
```ts
const email: string = 'a@b.c';    // email phải là chuỗi
const age: number = 30;
const ok: boolean = true;
```

**Định nghĩa hình dạng một object** (`api/types.ts`):
```ts
export type User = { id: string; email: string; fullName: string };
```
Từ giờ, biến kiểu `User` mà viết `user.fulname` (thiếu chữ) là **báo lỗi ngay khi gõ**.

**Kiểu "một trong các giá trị"** (union):
```ts
role: 'HOST' | 'SPEAKER' | 'ATTENDEE';    // chỉ được là 1 trong 3
```

**`| null`** — có thể là kiểu đó hoặc null:
```ts
user: User | null;    // hoặc là User, hoặc chưa đăng nhập (null)
```

**Generic `<T>`** — "kiểu để trống, điền sau":
```ts
api.get<Meeting[]>('/meetings')    // báo cho axios: kết quả là mảng Meeting
```

Chỉ cần nhớ bằng đấy. `api/types.ts` là nơi định nghĩa mọi kiểu dữ liệu **khớp với DTO của
backend** — hai bên phải cùng hình dạng thì frontend mới hiểu JSON backend trả về.

---

## 4. Quản lý trạng thái đăng nhập: Zustand

**Vấn đề:** thông tin "ai đang đăng nhập" cần dùng ở nhiều nơi — thanh header, dashboard,
trang phòng. Truyền qua props từng cấp thì mệt. Cần một "kho chung" mọi component lấy được.

Đó là việc của Zustand. Mở `stores/authStore.ts`:

```ts
type AuthState = {
  user: User | null;
  accessToken: string | null;
  ready: boolean;               // đã kiểm tra đăng nhập xong chưa
  setAuth: (user: User, accessToken: string) => void;
  setReady: () => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,                   // giá trị ban đầu
  accessToken: null,
  ready: false,
  setAuth: (user, accessToken) => set({ user, accessToken }),   // hành động lưu
  setReady: () => set({ ready: true }),
  clear: () => set({ user: null, accessToken: null, ready: get().ready }),
}));
```

- Phần đầu là **trạng thái** (dữ liệu): `user`, `accessToken`, `ready`.
- Phần sau là **hành động** để đổi trạng thái: `setAuth` (lưu khi đăng nhập), `clear` (xoá khi
  đăng xuất).
- `set({...})` cập nhật trạng thái. `get()` đọc trạng thái hiện tại.

Component dùng cực gọn:

```tsx
const user = useAuthStore((s) => s.user);         // lấy user
const setAuth = useAuthStore((s) => s.setAuth);   // lấy hành động
```

Điểm hay: khi `user` đổi, **chỉ những component đang dùng `user`** tự render lại. Không phải
truyền props, không phải viết Redux dài dòng.

> **`ready` để làm gì?** Khi mở app, ta chưa biết ngay người dùng đã đăng nhập chưa (phải hỏi
> server bằng refresh cookie). `ready = false` nghĩa là "đang kiểm tra", `true` là "kiểm tra
> xong". Nhờ nó, trang không vội đá người dùng ra login trong lúc còn đang xác minh (xem mục 7).

Dự án có 2 kho: `authStore` (đăng nhập) và `roomStore` (thông tin phòng đang vào — vé LiveKit,
tên hiển thị của khách).

---

## 5. Gọi API và interceptor

`api/client.ts` là file frontend thông minh nhất. Nó tự động: gắn token vào mỗi request, và
**tự làm mới token khi hết hạn** mà người dùng không hề hay biết.

### 5.1 Tạo axios và gắn token

```ts
export const api = axios.create({ baseURL: '/api/v1', withCredentials: true });

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
```

- `baseURL: '/api/v1'` — từ giờ gọi `api.get('/meetings')` là tự hiểu `/api/v1/meetings`.
- `withCredentials: true` — cho phép gửi kèm cookie (refresh token).
- **`interceptors.request`** — "trạm chặn" mọi request TRƯỚC khi gửi đi. Ở đây nó tự lấy access
  token từ kho và gắn vào header `Authorization`. Nhờ vậy, mọi nơi trong app chỉ việc gọi
  `api.get(...)`, không phải nhớ gắn token — interceptor lo hết.

> Đây là bản sao phía client của `JwtAuthFilter` phía server: một bên tự gắn token vào request,
> một bên tự đọc token ra khỏi request.

### 5.2 Tự làm mới token — phần hay nhất

Access token chỉ sống 15 phút. Sau 15 phút, request sẽ bị server trả **401** (chưa xác thực).
Thay vì đá người dùng ra, interceptor này **tự xử lý**:

```ts
api.interceptors.response.use(
  (res) => res,                                    // request thành công thì để yên
  async (error: AxiosError) => {                   // request lỗi thì vào đây
    const config = error.config;
    const isAuthRoute = config?.url?.startsWith('/auth');

    if (error.response?.status === 401 && config && !config._retry && !isAuthRoute) {
      config._retry = true;                         // (a) đánh dấu "đã thử lại 1 lần"
      refreshing ??= refreshAccessToken().finally(() => (refreshing = null));  // (b)
      const token = await refreshing;               // (c) chờ làm mới xong
      if (token) return api(config);                // (d) có token mới → gọi lại request cũ
      // (e) làm mới thất bại:
      const wasLoggedIn = useAuthStore.getState().user !== null;
      useAuthStore.getState().clear();
      if (wasLoggedIn) window.location.assign('/login');   // chỉ đá người đã đăng nhập
    }
    throw error;
  },
);
```

Kịch bản thực tế: anh đang xem dashboard, để yên 20 phút, rồi bấm vào một phòng.

1. Access token đã hết hạn → server trả **401**.
2. Interceptor bắt được, thấy 401.
3. **(a)** Đánh dấu `_retry` để không lặp vô hạn (nếu thử lại vẫn 401 thì thôi).
4. **(b)** Gọi `refreshAccessToken()` — dùng refresh cookie xin access token mới. Dấu `??=`
   nghĩa là "nếu đang có một lần làm mới chạy rồi thì dùng chung, đừng gọi 2 lần". Quan trọng
   khi nhiều request cùng hết hạn một lúc — chỉ làm mới **một lần** cho tất cả.
5. **(c)(d)** Có token mới → **gọi lại request ban đầu**. Người dùng chỉ thấy trang tải hơi lâu
   một chút, không biết vừa có một vòng làm mới token phía sau.
6. **(e)** Nếu làm mới thất bại (refresh token cũng hết hạn sau 14 ngày) → xoá phiên, đá về login.

**Chi tiết đã sửa quan trọng** — dòng `wasLoggedIn`:

> Khách vãng lai (chưa đăng nhập) trong webinar cũng gọi API bằng vé chat. Nếu vé hết hạn và ta
> đá **tất cả** về `/login`, thì khách — người **không có tài khoản để đăng nhập** — bị văng ra
> khỏi phòng họp. Nên chỉ đá người đã đăng nhập (`wasLoggedIn`), còn khách thì để yên.

### 5.3 Khôi phục phiên khi mở app

```ts
export async function bootstrapAuth(): Promise<void> {
  await refreshAccessToken();          // thử lấy access token từ refresh cookie
  useAuthStore.getState().setReady();  // dù thành công hay không, đánh dấu "đã kiểm tra xong"
}
```

Đây là hàm chạy một lần lúc `App` mở (mục 2). Refresh cookie sống 14 ngày và trình duyệt tự
giữ, nên dù đóng trình duyệt mở lại, hàm này vẫn lấy lại được phiên. **Đó là lý do F5 không bị
đăng xuất.**

---

## 6. Lấy dữ liệu TanStack Query

Cách "thủ công" lấy dữ liệu trong React anh có thể đã viết:

```tsx
const [meetings, setMeetings] = useState([]);
const [loading, setLoading] = useState(true);
useEffect(() => {
  api.get('/meetings').then(res => { setMeetings(res.data); setLoading(false); });
}, []);
```

Lặp đi lặp lại ở mọi trang, chưa kể còn phải tự xử lý lỗi, tự tải lại. TanStack Query gói hết.
Mở `features/meetings/meetingApi.ts`:

```ts
export function useMyMeetings() {
  return useQuery({
    queryKey: ['meetings'],                                    // "tên" của dữ liệu này
    queryFn: async () => (await api.get<Meeting[]>('/meetings')).data,   // cách lấy
  });
}
```

Dùng trong component:

```tsx
const { data: meetings, isLoading } = useMyMeetings();
```

Một dòng đó thay cho cả đống code thủ công bên trên. `data` là kết quả, `isLoading` là đang
tải hay chưa. Ngoài ra còn tự động:

- **Cache** — vào dashboard lần 2 hiện ngay dữ liệu cũ trong lúc lặng lẽ tải bản mới.
- **`queryKey: ['meetings']`** — như cái nhãn dán lên dữ liệu. Khi tạo phòng mới, ta bảo "dữ
  liệu nhãn `meetings` cũ rồi, tải lại đi" bằng `invalidateQueries`.

**Thay đổi dữ liệu** dùng `useMutation`:

```ts
export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input) => (await api.post<Meeting>('/meetings', input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['meetings'] }),   // tạo xong → tải lại danh sách
  });
}
```

`onSuccess` chạy khi tạo phòng thành công → làm mới danh sách để phòng mới hiện ra ngay.

**Một mẹo hay trong dự án** — theo dõi bản ghi hình đang xử lý:

```ts
export function useRecordings(meetingId, opts) {
  return useQuery({
    queryKey: ['recordings', meetingId],
    queryFn: ...,
    refetchInterval: opts?.poll ? 10_000 : false,   // cứ 10 giây hỏi lại một lần
    enabled: opts?.enabled ?? true,                 // chỉ HOST mới gọi
  });
}
```

`refetchInterval: 10_000` — tự động hỏi server mỗi 10 giây. Dùng để cập nhật trạng thái bản
ghi (STARTING → ACTIVE → COMPLETED) mà không cần người dùng bấm F5. `enabled` — chỉ bật cho
host (khách gọi API này sẽ bị 403).

> **Nguyên tắc phân công:** dữ liệu **từ server** → TanStack Query. Trạng thái **cục bộ của
> giao diện** (ai đang đăng nhập, đang mở dialog nào) → Zustand hoặc `useState`.

---

## 7. Bảo vệ trang

`components/ProtectedRoute.tsx` — chặn các trang cần đăng nhập:

```tsx
export function ProtectedRoute() {
  const { user, ready } = useAuthStore();
  if (!ready) return <div className="p-8 text-center text-gray-500">Loading...</div>;
  if (!user) return <Navigate to="/login" replace />;
  return <Outlet />;
}
```

Ba trường hợp, thứ tự quan trọng:

1. **`!ready`** — CHƯA kiểm tra xong đăng nhập (bootstrapAuth đang chạy). Hiện "Loading...".
   **Đây là bước then chốt:** nếu bỏ qua nó và kiểm `!user` ngay, thì lúc mới mở app `user`
   còn là `null` → đá thẳng ra login, dù người dùng thực ra đã đăng nhập (chỉ là chưa xác minh
   xong). Chờ `ready` rồi mới quyết định.
2. **`!user`** — đã kiểm tra xong, xác nhận chưa đăng nhập → chuyển hướng `/login`.
3. Còn lại — đã đăng nhập → `<Outlet/>` render trang con (dashboard...).

`<Outlet/>` là "chỗ trống" để react-router chèn component con vào. Nhớ trong `App.tsx`,
`ProtectedRoute` bọc ngoài dashboard — dashboard sẽ hiện ở vị trí `<Outlet/>`.

---

## 8. Vào phòng và render video

Đây là phần lõi. Có 2 trang: `PreJoinPage` (chuẩn bị) và `RoomPage` (phòng thật).

### 8.1 PreJoin — màn hình chuẩn bị

`features/room/PreJoinPage.tsx` dùng component `<PreJoin>` có sẵn của LiveKit:

```tsx
if (!ready) return <div>Loading...</div>;   // chờ bootstrapAuth (cho người đăng nhập mở link trực tiếp)

return (
  <PreJoin
    defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
    joinLabel="Join"
    onSubmit={(choices) => {
      if (!user && !choices.username.trim()) return;   // khách phải nhập tên
      setDisplayName(choices.username.trim());          // lưu vào roomStore
      navigate(`/m/${code}/room`, { state: { videoEnabled: choices.videoEnabled, ... } });
    }}
  />
);
```

`<PreJoin>` tự lo: xin quyền camera/micro, hiện hình xem trước, cho chọn thiết bị. Khi bấm
Join, `onSubmit` chạy: lưu tên (cho khách), rồi chuyển sang trang phòng, mang theo lựa chọn
bật/tắt cam.

> `if (!ready)` ở đầu là một lỗi đã sửa: `<PreJoin>` đọc `defaults.username` **một lần lúc
> mount**. Nếu người đã đăng nhập mở thẳng link phòng mà bootstrapAuth chưa xong, tên sẽ rỗng
> và họ kẹt (khách phải nhập tên mới Join được). Chờ `ready` là xong.

### 8.2 RoomPage — gọi vé rồi vào phòng

`features/room/RoomPage.tsx`:

```tsx
export function RoomPage() {
  const join = useJoinMeeting();       // mutation gọi POST /join

  useEffect(() => {
    if (!code) return;
    join.mutate(
      { code, displayName: user ? undefined : displayName },   // khách gửi kèm tên
      { onSuccess: (data) => setJoin(data) },                   // lưu vé vào roomStore
    );
    return () => clear();               // rời trang thì dọn roomStore
  }, [code]);

  if (join.isError) { ... }             // hiện thông báo lỗi tuỳ mã lỗi backend
  if (!join.data) return <div>Connecting...</div>;   // đang chờ vé

  return (
    <LiveKitRoom
      serverUrl={join.data.livekitUrl}    // địa chỉ LiveKit
      token={join.data.livekitToken}      // vé (do backend cấp)
      connect
      video={join.data.role !== 'ATTENDEE' && (choices.videoEnabled ?? true)}
      audio={join.data.role !== 'ATTENDEE' && (choices.audioEnabled ?? true)}
      onDisconnected={() => navigate(user ? '/' : `/m/${code}`)}
    >
      <RoomLayout meetingId={join.data.meetingId} role={join.data.role} />
    </LiveKitRoom>
  );
}
```

Luồng:

1. `useEffect` chạy khi vào trang → gọi API `/join`, nhận về vé.
2. Đang chờ vé → hiện "Connecting...".
3. Có lỗi (phòng chưa bắt đầu, không được mời...) → hiện thông báo tương ứng.
4. Có vé → render **`<LiveKitRoom>`**. Component này của LiveKit tự lo **toàn bộ** phần khó:
   kết nối tới máy chủ video, gửi/nhận track, tự kết nối lại khi rớt mạng.

Chú ý dòng:
```tsx
video={join.data.role !== 'ATTENDEE' && ...}
```
Khán giả (ATTENDEE) thì `video = false` — không bật camera. Nhưng nhớ: **đây chỉ là giao diện
cho hợp lý**. Bảo mật thật nằm ở vé (backend đặt `canPublish=false`). Kể cả dòng này bị bỏ,
LiveKit vẫn chặn khán giả phát.

`<RoomLayout>` bên trong là phần giao diện tự ghép: lưới video + danh sách người + chat + thanh
nút.

### 8.3 Vé chảy từ backend ra sao

Ghép với tài liệu 03: `join.data.livekitToken` chính là chuỗi vé mà `LiveKitTokenService`
(backend, mục 10) sinh ra. Frontend **không đọc, không sửa** vé — chỉ cầm đưa cho LiveKit.
LiveKit kiểm chữ ký và cho vào phòng đúng quyền.

---

## 9. Chat realtime

Chat không dùng axios (đó là cho HTTP request-response). Nó dùng WebSocket qua một hook tự
viết: `features/room/useChatSocket.ts`.

### 9.1 Kết nối và nhận tin

```ts
export function useChatSocket(meetingId: string, token: string | null) {
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!token) return;
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,   // kết nối tới /ws
      connectHeaders: { Authorization: `Bearer ${token}` },    // gửi token khi CONNECT
      reconnectDelay: 3000,                                    // rớt thì 3 giây thử lại
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/meetings/${meetingId}/chat`, (frame) => {   // đăng ký nghe
          const event = JSON.parse(frame.body);
          if (event.kind === 'MESSAGE') {
            setMessages((cur) => mergeMessages(cur, [event.message]));        // thêm tin mới
          } else if (event.kind === 'MESSAGE_DELETED') {
            setMessages((cur) => cur.filter((m) => m.id !== event.messageId)); // xoá tin
          }
        });
        // tải bù tin đã lỡ:
        const params = lastCreatedAtRef.current ? { after: ... } : { limit: 50 };
        api.get(`/meetings/${meetingId}/messages`, { params, headers: {...} })
           .then(({ data }) => setMessages((cur) => mergeMessages(cur, data)));
      },
      onDisconnect: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;
    return () => { void client.deactivate(); };   // rời phòng thì đóng kết nối
  }, [meetingId, token]);
```

So với backend (tài liệu 03 mục 12) khớp từng phần:

| Frontend | Backend tương ứng |
|---|---|
| `brokerURL: .../ws` | endpoint `/ws` trong `WebSocketConfig` |
| `connectHeaders: Authorization` | `StompAuthChannelInterceptor` kiểm lúc CONNECT |
| `client.subscribe('/topic/...')` | interceptor kiểm quyền lúc SUBSCRIBE |
| nhận `event.kind === 'MESSAGE'` | `ChatService` phát `ChatEvent.message(...)` |

- `token` là vé chat (khách) hoặc access token (thành viên) — gửi khi CONNECT để backend biết
  ai đang nối.
- `subscribe` — đăng ký nghe kênh của đúng phòng. Backend phát tin lên kênh này, hook nhận
  được qua callback.
- **Tải bù tin đã lỡ**: khi vừa kết nối (hoặc kết nối lại sau khi rớt mạng), gọi API lịch sử
  để lấy những tin sinh ra trong lúc mất kết nối. `after` = thời điểm tin cuối cùng đang có.

### 9.2 Gộp tin không trùng — hàm thuần

```ts
export function mergeMessages(current, incoming) {
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);              // trùng id thì ghi đè
  return [...byId.values()].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),  // sắp theo thời gian
  );
}
```

Vì sao cần? Tin có thể đến từ **hai nguồn**: realtime qua WebSocket, và tải bù qua API. Có thể
trùng. `Map` theo `id` đảm bảo mỗi tin chỉ xuất hiện một lần, rồi sắp xếp theo thời gian.

Hàm này **thuần** (chỉ nhận vào - trả ra, không đụng gì bên ngoài) nên **test được dễ dàng** —
xem `useChatSocket.test.ts`.

### 9.3 Gửi tin và chống mất tin

`features/room/ChatPanel.tsx`:

```tsx
function onSubmit(e) {
  e.preventDefault();
  if (!connected || !draft.trim()) return;    // ← CHƯA kết nối thì không cho gửi
  send(draft.trim());
  setDraft('');
}
```

Và ô nhập bị khoá khi chưa kết nối:

```tsx
<input disabled={!connected} placeholder="Type a message..." />
<button disabled={!connected}>Send</button>
```

> **Đây là một lỗi thật đã sửa.** Trước đây gõ tin ngay khi vừa vào phòng thì `send()` chạy
> trên client STOMP chưa kết nối xong → **tin biến mất không báo gì**. Chính lỗi này làm test
> e2e "chập chờn" (lúc pass lúc fail). Giờ ô nhập khoá mờ tới khi `connected = true`, khớp với
> chữ "(connecting...)" ở tiêu đề khung chat.

---

## 10. Thanh điều khiển theo vai trò

`features/room/ControlBar.tsx` — hiện nút khác nhau tuỳ vai:

```tsx
export function ControlBar({ meetingId, role, onRaiseHand, onEnd }) {
  const { localParticipant } = useLocalParticipant();
  const canPublish = localParticipant.permissions?.canPublish ?? role !== 'ATTENDEE';

  return (
    <div>
      {canPublish && (                          {/* chỉ ai được phát mới thấy cụm này */}
        <span data-testid="publish-controls">
          <TrackToggle source={Track.Source.Microphone} />
          <TrackToggle source={Track.Source.Camera} />
          <TrackToggle source={Track.Source.ScreenShare} />
        </span>
      )}
      <button onClick={onRaiseHand}>✋ Raise hand</button>       {/* ai cũng thấy */}
      {role === 'HOST' && (...record button...)}                 {/* chỉ host */}
      {role === 'HOST' && onEnd && <button>End meeting</button>}
      <DisconnectButton>Leave</DisconnectButton>
    </div>
  );
}
```

Điểm tinh tế nhất:

```tsx
const canPublish = localParticipant.permissions?.canPublish ?? role !== 'ATTENDEE';
```

Nút mic/camera **không hiện dựa trên `role` cố định**, mà dựa trên **`permissions.canPublish`
thật của LiveKit ngay lúc này**. Vì sao? Vì khi host **thăng quyền** cho một khách, LiveKit
cập nhật `permissions.canPublish` từ `false` thành `true` **ngay lập tức** — và cụm nút này tự
hiện ra, khách không phải tải lại trang.

`<TrackToggle source={Track.Source.Camera}>` — component có sẵn của LiveKit, tự lo bật/tắt
camera và cập nhật giao diện.

> Ghép với backend: host bấm "thăng quyền" → `ControlController` gọi LiveKit đổi quyền runtime
> → LiveKit báo cho trình duyệt khách qua sự kiện `ParticipantPermissionsChanged` →
> `localParticipant.permissions.canPublish` đổi thành `true` → cụm nút publish hiện ra. Cả
> vòng này diễn ra trong một hai giây, không rời trang.

---

## 11. Dữ liệu chảy thế nào

Ghép tất cả lại. Ba luồng, từ cú bấm chuột tới màn hình.

### Đăng nhập
```
Gõ email/mật khẩu, bấm Sign in
  → useAuth().login()  gọi  api.post('/auth/login')
  → interceptor request KHÔNG gắn token (chưa có)
  → backend trả { accessToken, user } + cookie refresh
  → setAuth(user, accessToken)  lưu vào authStore
  → navigate('/')  chuyển tới dashboard
  → dashboard đọc user từ authStore, gọi useMyMeetings() lấy danh sách phòng
```

### Vào phòng và thấy video
```
Bấm Join
  → RoomPage: useEffect gọi useJoinMeeting()
  → api.post('/meetings/{code}/join')  (interceptor tự gắn access token)
  → backend kiểm quyền, sinh vé LiveKit, trả { livekitUrl, livekitToken, role }
  → setJoin(data)  lưu vào roomStore
  → <LiveKitRoom token={vé}>  kết nối THẲNG tới LiveKit
  → LiveKit kiểm vé, cho vào phòng đúng quyền
  → <RoomLayout> render lưới video; video người khác chảy trực tiếp từ LiveKit về
```

### Gửi tin nhắn
```
Gõ tin, Enter  (chỉ khi connected = true)
  → useChatSocket.send()  publish tới  /app/meetings/{id}/chat  qua WebSocket
  → backend ChatController nhận, kiểm quyền, lưu DB, phát lên Redis
  → Redis phát cho MỌI server → server đẩy xuống các client đang nghe
  → useChatSocket nhận qua callback subscribe → mergeMessages → setMessages
  → ChatPanel render lại, tin hiện lên ở mọi cửa sổ
```

---

## Tự kiểm tra hiểu bài

1. Vì sao khi access token hết hạn, người dùng không bị đá ra mà request vẫn chạy được?
2. `ProtectedRoute` kiểm `ready` trước `user` — nếu đảo thứ tự thì lỗi gì?
3. Nút camera hiện/ẩn dựa trên `role` hay dựa trên cái gì? Vì sao chọn thế?
4. Hàm `mergeMessages` giải quyết vấn đề gì?
5. Vì sao ô nhập chat bị khoá lúc mới vào phòng?

*(Đáp án: mục 5.2, mục 7, mục 10, mục 9.2, mục 9.3.)*

---

Quay lại: [03-di-sau-backend.md](03-di-sau-backend.md) · [01-giai-thich-cong-nghe.md](01-giai-thich-cong-nghe.md)
