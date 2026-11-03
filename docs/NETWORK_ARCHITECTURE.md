# Kiến trúc mạng P2PFileShare (LAN Mode và Internet Mode)

Tài liệu này mô tả kiến trúc mạng sau khi bổ sung Internet Mode: các tầng, giao thức, luồng xử lý, mô hình bảo mật và giới hạn.

## 1. Bài toán

| | LAN Mode | Internet Mode |
|---|---|---|
| Vị trí 2 máy | Cùng 1 mạng (cùng router / Wi-Fi / switch) | 2 mạng khác nhau: Wi-Fi nhà và 4G/5G, hoặc 2 Wi-Fi ở 2 nơi |
| Tìm nhau | UDP multicast / broadcast, hoặc nhập IP | Mã kết nối 8 ký tự do relay cấp |
| Đường truyền | TCP trực tiếp máy ↔ máy | Máy ↔ relay ↔ máy (WebSocket trên TLS) |
| Máy chủ trung gian | Không có | Relay Server có IP công khai |
| Mô hình | P2P trực tiếp | **P2P có hỗ trợ chuyển tiếp** (relay-assisted P2P) |

Trong Internet Mode, dữ liệu **không** đi trực tiếp giữa 2 máy. Mọi byte đều đi qua relay. Tuy vậy, file vẫn nằm trên máy của từng người, mỗi máy vẫn tự quyết định chia sẻ gì và cho ai xem, và relay không lưu file. Vì thế đây là P2P có chuyển tiếp, không phải mô hình client–server lưu file trên máy chủ.

## 2. Vì sao UDP LAN Discovery không đủ khi 2 máy ở 2 mạng khác nhau

1. **Multicast/broadcast không ra khỏi mạng con.** Gói `230.0.0.1:8888` (TTL nhỏ) và broadcast `x.x.x.255` bị router chặn ở biên mạng. Máy dùng 4G không bao giờ nhận được gói discovery của máy dùng Wi-Fi nhà.
2. **Địa chỉ trong gói discovery là IP riêng** (`192.168.x.x`, `10.x.x.x`). Ở mạng khác, IP này vô nghĩa hoặc trỏ tới một máy khác.
3. **NAT**: router gia đình dịch nhiều máy trong nhà ra 1 IP công khai. Kết nối *từ ngoài vào* bị chặn nếu không mở cổng (port forwarding).
4. **CGNAT** (Carrier-Grade NAT, phổ biến ở mạng 4G/5G và nhiều nhà mạng cáp quang tại Việt Nam): nhà mạng NAT thêm 1 lớp nữa, thuê bao chỉ có IP dạng `100.64.0.0/10` hoặc IP riêng. Người dùng **không thể** mở cổng vì không quản lý router của nhà mạng.
5. **Firewall** của hệ điều hành và mạng công cộng chặn kết nối vào.

Kết quả là "nhập IP công khai của máy kia" không giải quyết được vấn đề: ở 4G/CGNAT, không có địa chỉ nào để kết nối vào. Cách chắc chắn hoạt động: **cả hai máy chủ động mở kết nối ra ngoài** (NAT luôn cho phép chiều ra) tới một máy chủ có IP công khai, và máy chủ đó ghép hai kết nối lại. Đó là Relay Server.

## 3. Các khái niệm mạng liên quan

- **TCP**: kết nối tin cậy, có thứ tự, kiểm soát luồng. Cả LAN Mode và WebSocket đều chạy trên TCP.
- **WebSocket (RFC 6455)**: bắt đầu bằng 1 request HTTP `GET /ws` có `Upgrade: websocket`; server trả `101 Switching Protocols` kèm `Sec-WebSocket-Accept = base64(SHA-1(key + GUID))`. Sau đó hai bên trao đổi *frame* text/binary 2 chiều trên cùng kết nối TCP. Vì bắt đầu như HTTP(S) trên cổng 443, WebSocket đi qua được proxy và firewall công ty/nhà mạng mà TCP cổng lạ thường bị chặn.
- **TLS / WSS**: `wss://` là WebSocket chạy trong TLS, giống `https://`. TLS mã hoá và xác thực máy chủ bằng chứng chỉ (Let's Encrypt), chống nghe lén và giả mạo relay trên đường truyền.
- **NAT traversal trực tiếp (hole punching, STUN/TURN/ICE)**: có thể thử để 2 máy nói chuyện trực tiếp, nhưng thất bại với CGNAT/NAT đối xứng, nên luôn cần TURN (relay) làm phương án dự phòng. Dự án chọn làm relay trước (chắc chắn chạy). Kết nối trực tiếp là hướng phát triển (mục 10).

## 4. Kiến trúc tổng thể

```
┌──────────────────────────── Ứng dụng P2PFileShare (mỗi máy) ────────────────────────────┐
│ Giao diện JavaFX: Kết nối mạng [LAN | Internet] · Tài liệu chia sẻ · Tìm kiếm · Lịch sử   │
│                                                                                          │
│ TẦNG NGHIỆP VỤ (dùng chung cho 2 chế độ)                                                 │
│   ConnectionService   kết nối 2 chiều, chấp nhận/từ chối, ngắt                           │
│   ControlClient/Server  CONNECT_REQUEST, LIST_FILES, SEARCH, SUMMARIZE, PING ...         │
│   ChunkedFileClient/Server  metadata, GET_CHUNK, SHA-256, tải song song, .part/.bitmap  │
│   DownloadJob         RUNNING / PAUSED / WAITING_NETWORK / COMPLETED / FAILED           │
│   PeerHealthMonitor   heartbeat tới từng peer: Online / Offline / Reconnecting           │
│   InternetModeService mã kết nối, nối luồng relay vào ControlServer / ChunkedFileServer  │
│                                                                                          │
│ TẦNG TRUYỀN THÔNG (PeerTransport -> StreamConnection)                                    │
│   TransportRouter ──► LanTcpTransport : Socket TCP tới ip:controlPort / ip:filePort      │
│                  └──► RelayTransport  : RelayStream (luồng ảo) trên RelayClient (wss)    │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                                            │ wss:// (TLS, cổng 443)
                                            ▼
┌────────────────────────── Relay Server (VPS có IP công khai) ────────────────────────────┐
│ Caddy/Nginx: TLS + reverse proxy WebSocket ──► RelayServer (Java, cổng 8080 nội bộ)       │
│   RelaySession: 1 luồng đọc + 1 luồng ghi, hàng đợi gửi giới hạn theo byte (backpressure)│
│   Xác thực Ed25519 · InviteRegistry (mã mời) · LinkRegistry (liên kết + link token HMAC)  │
│   Bảng luồng ảo sid -> (phiên A, phiên B) · heartbeat · rate limit · giới hạn tài nguyên  │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Điểm thiết kế chính:** tầng nghiệp vụ chỉ làm việc với `StreamConnection` (giống socket: `getInputStream`, `getOutputStream`, `setReadTimeout`, `close`). Mọi giao thức sẵn có (kênh điều khiển dạng dòng text, kênh file nhị phân `DataInputStream`) chạy **không đổi** trên luồng ảo qua relay. Không có hai codebase riêng cho LAN và Internet.

| Interface / lớp | Vai trò |
|---|---|
| `StreamConnection` | 1 kết nối 2 chiều dạng luồng byte |
| `PeerTransport.open(peer, Channel)` | Mở kết nối tới peer trên kênh `CONTROL` hoặc `FILE` |
| `LanTcpTransport` | `Socket` TCP, timeout mở 3 giây (control) / 5 giây (file) |
| `RelayTransport` | `RelayClient.openStream(peerId, channel)` |
| `TransportRouter` | Chọn theo `PeerInfo.Route` (`LAN` hoặc `RELAY`) |
| `InboundOrigin` | Nguồn kết nối đến: IP (LAN) hoặc danh tính relay đã xác thực |

## 5. Giao thức relay

Mã nguồn: [relay-server/src/shared/java/org/example/p2prelay/protocol/RelayProtocol.java](../relay-server/src/shared/java/org/example/p2prelay/protocol/RelayProtocol.java). Lớp này dùng chung giữa ứng dụng và relay.

### 5.1 Khung dữ liệu

- **Text frame** = lệnh điều khiển, các trường cách nhau 1 dấu cách, tối đa 4 KiB. Tên hiển thị được mã hoá URL để không chứa dấu cách.
- **Binary frame** = dữ liệu của 1 luồng ảo: `[0x01][sid: int32 big-endian][payload ≤ 64 KiB]`. Relay chuyển nguyên frame sang phía bên kia, không giải mã, không lưu.

### 5.2 Xác thực (không dựa vào peerId tự khai)

```
relay → máy : CHALLENGE <nonce ngẫu nhiên 24 byte> 1
máy  → relay: HELLO <khoá công khai Ed25519> <chữ ký Ed25519 trên "p2pfileshare-relay-auth-v1:"+nonce> <tên>
relay → máy : WELCOME <peerId> <heartbeat> <idleTimeout> <cửa sổ luồng>
```

`peerId = hex(16 byte đầu của SHA-256(khoá công khai))`. Relay tự tính peerId từ khoá, nên chỉ máy giữ khoá bí mật mới đăng nhập được với id đó. Mỗi lần mở ứng dụng sinh khoá mới (giống UUID trước đây). Khoá bí mật không rời khỏi máy.

### 5.3 Mã kết nối và liên kết

```
B → relay: CREATE_INVITE 1           relay → B: INVITE 1 K7QM4XZ9 <hết hạn epoch>
A → relay: RESOLVE 2 K7QM4XZ9        relay → A: RESOLVED 2 <peerId B> <tên B> <link token>
                                     relay → B: LINKED <peerId A> <tên A> <link token>
```

- Mã gồm 8 ký tự trong bảng 31 ký tự dễ đọc (bỏ `0 O 1 I L`), khoảng 2^39 khả năng. Hết hạn sau 10 phút, dùng 1 lần, mỗi máy chỉ có 1 mã còn hiệu lực.
- Nhập sai bị đếm: tối đa 5 lần/60 giây mỗi phiên và 20 lần/10 phút mỗi IP, vượt thì trả `RATE_LIMITED`.
- Tra đúng mã tạo một **liên kết** A–B trên relay. Relay **chỉ chuyển tiếp luồng giữa 2 máy đã liên kết**, nên người không có mã không mở được kết nối tới ai (`NOT_LINKED`).
- **Link token** `v1.<peerA>.<peerB>.<hết hạn>.<HMAC-SHA256>` ký bằng `RELAY_LINK_SECRET`. Khi mất mạng hoặc relay khởi động lại, ứng dụng gửi `RESTORE <peerId> <token>` để khôi phục liên kết mà không phải nhập lại mã.
- Liên kết **không** cho quyền xem file. Sau khi liên kết, A vẫn gửi `CONNECT_REQUEST` như LAN Mode và **B phải bấm Chấp nhận**.

### 5.4 Luồng ảo (thay cho kết nối TCP)

```
A → relay: OPEN 7 <peerId B> file
relay → B: INCOMING 42 <peerId A> file      (peerId A do relay điền theo phiên đã xác thực)
B → relay: ACCEPT 42
relay → A: OPENED 7 42
A ⇄ B   : binary [01][42][dữ liệu]  ·  CREDIT 42 <số byte>  ·  CLOSE 42 / RESET 42 <lý do>
```

Lỗi mở luồng: `OPEN_FAILED <req> PEER_OFFLINE | NOT_LINKED | LIMIT | REJECTED | TIMEOUT`.

### 5.5 Kiểm soát luồng và bộ nhớ (backpressure)

Có 2 lớp:

1. **Cửa sổ credit mỗi luồng (end-to-end, giống HTTP/2):** mỗi bên chỉ được gửi tối đa 1 MiB chưa được bên kia đọc. Bên nhận đọc xong thì gửi `CREDIT` (theo lô ≥ 256 KiB). Bộ đệm nhận của 1 luồng không bao giờ vượt 1 MiB, và luồng đang tạm dừng không làm nghẽn các luồng khác trên cùng WebSocket. Bên gửi vượt cửa sổ thì luồng bị huỷ (`RESET LIMIT`).
2. **Hàng đợi gửi của relay giới hạn theo byte** (mặc định 4 MiB mỗi phiên): luồng đọc của phiên nguồn bị chặn khi hàng đợi phiên đích đầy, nên ngừng đọc socket và TCP tự báo máy gửi chậm lại. Nếu phiên đích không nhận gì trong 20 giây thì relay đóng phiên đích. RAM relay ≈ số phiên × 4 MiB, có chặn trên.

Phía ứng dụng, `RelayStream.write` chờ WebSocket nhận xong frame trước khi gửi frame tiếp. WebSocket của JDK chỉ cho 1 lệnh gửi đang chờ, nên các lệnh gửi được xếp nối tiếp bằng `CompletableFuture`, không chặn luồng nhận.

### 5.6 Heartbeat và trạng thái

| Cơ chế | Chu kỳ / timeout mặc định | Phát hiện |
|---|---|---|
| Ứng dụng gửi `PING` text tới relay | 10 s / 30 s không nhận gì | Mất kết nối relay → `RECONNECTING` |
| Relay gửi WebSocket ping | 15 s / 45 s không nhận gì | Máy mất mạng đột ngột → đóng phiên, báo `PRESENCE <id> OFFLINE` cho peer đã liên kết |
| `PeerHealthMonitor` gửi `PING\|from\|to` qua kênh control tới từng peer đang kết nối | 5 s / 15 s | Peer mất mạng / tắt app → `OFFLINE`; peer quên mình (đã mở lại app) → gỡ kết nối |

Phân biệt **relay còn sống** với **peer còn sống**: khi relay của mình mất thì peer Internet hiển thị `Reconnecting` (chưa biết peer còn hay không). Khi relay vẫn sống mà peer không trả lời PING, hoặc relay báo `PRESENCE OFFLINE`, thì peer hiển thị `Offline`.

Kết nối lại relay dùng exponential backoff: 1 s, 2 s, 4 s … tối đa 60 s, ±20% ngẫu nhiên, dừng sau 30 lần liên tiếp (trạng thái `Offline`, người dùng bấm *Kết nối relay* để thử lại). Không có vòng lặp kết nối lại tần suất cao.

## 6. Luồng xử lý chính

### 6.1 Kết nối bằng mã

```
A (Wi-Fi)                         Relay                               B (4G)
 │──── wss + HELLO (ký) ──────────►│◄────────── wss + HELLO (ký) ───────│
 │                                 │◄── CREATE_INVITE ──────────────────│  B đọc mã K7QM-4XZ9 cho A
 │──── RESOLVE K7QM4XZ9 ──────────►│── LINKED A ───────────────────────►│
 │◄─── RESOLVED B + token ─────────│                                    │
 │──── OPEN control ──────────────►│── INCOMING ───────────────────────►│
 │════ CONNECT_REQUEST|A|B|tên ═══════════════════════════════════════►│ hộp thoại "… (qua Internet)"
 │◄═══ CONNECT_ACCEPT / REJECT ════════════════════════════════════════│ B bấm Chấp nhận / Từ chối
```

Từ chối thì A huỷ liên kết trên relay (`UNLINK`).

### 6.2 Tải file qua relay

Giống hệt LAN Mode: `FILE_META_REQUEST` (kích thước, SHA-256 cả file và từng chunk 1 MiB), sau đó 4 luồng ảo song song, mỗi luồng lặp `GET_CHUNK`. Chunk chỉ được ghi vào `.part` và đánh dấu trong `.bitmap` sau khi khớp SHA-256 trong metadata. Cuối cùng băm lại cả file trước khi đổi tên. Mỗi lần tải tiếp đều hỏi lại metadata: file nguồn đã đổi thì metadata khác, `.part` cũ bị bỏ và tải lại từ đầu. Nếu file đổi ngay trong lúc tải (SHA-256 cả file sai) thì tải lại từ đầu tối đa 1 lần rồi báo lỗi.

### 6.3 Mất mạng khi đang tải

```
RUNNING ──(lỗi mạng / timeout / relay mất)──► WAITING_NETWORK ──(thử lại: 2s,4s,8s,15s… hoặc ngay khi
   ▲                                              │              heartbeat thấy peer online lại)──┐
   └──────────── tải tiếp các chunk còn thiếu ◄────┘                                              │
WAITING_NETWORK quá 10 phút (không tải thêm được chunk nào) ──► FAILED (giữ .part để tải tiếp sau) │
Peer từ chối (mất quyền) / relay báo NOT_LINKED ──► FAILED ngay                                   │
```

Không bao giờ đánh dấu hoàn thành sai: chỉ đổi `.part` thành file thật khi SHA-256 cả file khớp metadata.

## 7. Mô hình bảo mật

| Mối đe doạ | Biện pháp |
|---|---|
| Nghe lén / sửa dữ liệu trên Internet | TLS (`wss://`) giữa mỗi máy và relay. Ứng dụng từ chối `ws://` tới địa chỉ Internet (chỉ cho phép localhost / mạng nội bộ để thử nghiệm) |
| Giả mạo relay | Chứng chỉ TLS được kiểm tra bằng kho chứng chỉ của Java (không tắt kiểm tra) |
| Mạo danh peer trên relay | peerId suy ra từ khoá công khai + chữ ký challenge. Relay tự điền id người gửi vào `INCOMING`. `ControlServer`/`ChunkedFileServer` bỏ mọi lệnh có id khai báo khác id đã xác thực |
| Dùng lại id của peer Internet từ LAN | Quyền gắn với đường đã chấp nhận (`isAcceptedFrom(peerId, origin)`): peer chấp nhận qua relay chỉ được phục vụ qua relay |
| Người lạ làm phiền / dò quét | Phải có mã mời mới liên kết được. Mã ngắn hạn, dùng 1 lần, giới hạn số lần thử theo phiên và IP |
| Truy cập trái phép vào file | Người nhận bấm Chấp nhận/Từ chối (giữ nguyên LAN Mode). Kiểm tra quyền ở mọi lệnh: LIST, SEARCH, SUMMARIZE, metadata, chunk |
| Đọc file ngoài thư mục chia sẻ | `SafePaths.resolveSharedFile`: chặn `../`, đường dẫn tuyệt đối, NUL, giải `toRealPath()` để chặn symlink/junction ra ngoài, không phục vụ file ẩn / `.git` / `.part`. Không cho chia sẻ cả ổ đĩa hoặc nguyên thư mục người dùng |
| Cạn tài nguyên relay | Giới hạn số phiên, kết nối/IP, luồng/phiên, kích thước frame, hàng đợi theo byte, timeout xác thực / mở luồng / phiên im lặng |
| Lộ bí mật trong log / Git | Log chỉ ghi 8 ký tự đầu của id, không ghi mã mời, token, chữ ký, nội dung file. `RELAY_LINK_SECRET` đặt qua biến môi trường, `deploy/relay.env` nằm trong `.gitignore` |

**Giới hạn quan trọng (không có mã hoá đầu cuối):** TLS chỉ bảo vệ 2 đoạn *máy A ↔ relay* và *relay ↔ máy B*. Bên trong relay, dữ liệu ở dạng rõ: người quản trị relay có thể đọc lệnh điều khiển và nội dung chunk file. Dự án **chưa** cài đặt và kiểm thử mã hoá đầu cuối (E2EE), nên không tuyên bố có E2EE. Hãy tự triển khai relay trên máy chủ mình kiểm soát.

Ở LAN Mode, kênh TCP vẫn không mã hoá và id do peer tự khai (giữ nguyên hành vi cũ, phạm vi mạng nội bộ tin cậy).

## 8. Đa luồng phía relay

- Mỗi phiên dùng 1 luồng đọc (blocking IO) và 1 luồng ghi. Một phiên chậm không chặn luồng chính (`relay-accept`) hay phiên khác, chỉ chặn phiên đang gửi dữ liệu cho nó (đúng ý đồ backpressure).
- Luồng `relay-maintenance` chạy định kỳ: gửi ping, đóng phiên im lặng / chưa xác thực, huỷ luồng mở quá hạn, dọn mã mời hết hạn, bảng rate limit, liên kết rỗng.
- Thông báo quan trọng (`RESET`, `PRESENCE OFFLINE`, `OPEN_FAILED`) được gửi kể cả khi hàng đợi đầy, để bên kia không phải chờ timeout.

## 9. Ánh xạ mã nguồn

| Chức năng | File |
|---|---|
| WebSocket server RFC 6455 | `relay-server/src/main/java/org/example/p2prelay/WebSocketConnection.java` |
| Điều phối relay | `RelayServer.java`, `RelaySession.java` |
| Mã mời / liên kết / rate limit | `InviteRegistry.java`, `LinkRegistry.java`, `RateLimiter.java` |
| Cấu hình relay (biến môi trường) | `RelayConfig.java` |
| WebSocket client, heartbeat, kết nối lại | `src/main/java/.../network/relay/RelayClient.java` |
| Luồng ảo + credit | `network/relay/RelayStream.java` |
| Danh tính Ed25519 | `network/relay/IdentityKey.java` |
| Trừu tượng tầng truyền thông | `network/transport/*` |
| Nối relay vào nghiệp vụ | `service/InternetModeService.java` |
| Heartbeat peer | `service/PeerHealthMonitor.java` |
| Chờ mạng + tải tiếp | `service/DownloadJob.java`, `network/transfer/ChunkedFileClient.java` |
| Kiểm tra đường dẫn | `util/SafePaths.java` |
| Giao diện Internet Mode | `controller/InternetPanelController.java`, `resources/.../InternetPanel.fxml` |

## 10. Hạn chế và hướng phát triển

- **Băng thông bị giới hạn bởi relay**: mọi byte đi qua VPS (tính cả chiều vào và ra). Tốc độ ≈ min(upload máy gửi, băng thông VPS, download máy nhận).
- **Chưa có kết nối trực tiếp (hole punching / ICE)**: có thể bổ sung STUN để thử kết nối UDP/TCP trực tiếp khi NAT cho phép, và giữ relay làm dự phòng (giống TURN).
- **Chưa có E2EE**: hướng làm là bọc mỗi `RelayStream` bằng TLS/Noise giữa 2 peer, dùng khoá Ed25519 đã có để xác thực lẫn nhau (relay chỉ thấy bản mã).
- **Danh tính đổi mỗi lần mở app**: sau khi mở lại ứng dụng phải nhập mã mới. Có thể lưu khoá (mã hoá) để ghép đôi lâu dài.
- **Relay đơn lẻ, trạng thái trong RAM**: khởi động lại thì mất mã mời (liên kết được khôi phục bằng token). Chưa hỗ trợ nhiều relay / cân bằng tải.
- LAN Mode chưa mã hoá kênh TCP.
