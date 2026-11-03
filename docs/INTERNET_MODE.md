# Internet Mode: hướng dẫn triển khai và sử dụng

Internet Mode cho phép 2 máy ở 2 mạng khác nhau (ví dụ Wi-Fi ở nhà và 4G/5G) kết nối, xem danh sách tài liệu, tìm kiếm và tải file của nhau. Hai máy **không** cần IP công khai và **không** cần mở cổng router. Đổi lại, cần 1 **Relay Server** chạy trên máy chủ có IP công khai (VPS).

> Đây là mô hình **P2P có hỗ trợ chuyển tiếp**: dữ liệu đi máy A → relay → máy B, relay không lưu file. Kiến trúc và giao thức: [NETWORK_ARCHITECTURE.md](NETWORK_ARCHITECTURE.md).

## 1. Cần chuẩn bị

| Thứ cần có | Ghi chú |
|---|---|
| 1 VPS Linux có IPv4 công khai | 1 vCPU, 512 MB–1 GB RAM là đủ. Băng thông VPS quyết định tốc độ truyền |
| 1 tên miền trỏ về IP VPS | Bản ghi `A relay.ten-mien.com → <IP VPS>`. Không có tên miền thì dùng `<ip-gạch-ngang>.sslip.io` (ví dụ `203-0-113-10.sslip.io`) |
| Mở cổng 80 và 443 (TCP) trên firewall của VPS / nhà cung cấp | Cổng 80 để Let's Encrypt cấp chứng chỉ, 443 cho `wss://` |
| Docker + Docker Compose trên VPS | Hoặc Java 17 + Nginx nếu không dùng Docker (mục 2.3) |
| 2 máy tính đã cài ứng dụng P2PFileShare bản này | `setup.bat` / `run.bat` như cũ |

## 2. Triển khai relay

### 2.1 Docker Compose + Caddy (khuyên dùng: TLS tự động)

```bash
# trên VPS
git clone <repo> p2p && cd p2p/deploy
cp relay.env.example relay.env
nano relay.env                 # sửa RELAY_DOMAIN và RELAY_LINK_SECRET
openssl rand -hex 32           # dùng kết quả này làm RELAY_LINK_SECRET
docker compose up -d --build
docker compose logs -f relay   # xem log
curl https://relay.ten-mien.com/health     # -> "ok online=0"
```

Caddy nhận `wss://relay.ten-mien.com/ws` ở cổng 443, tự xin và gia hạn chứng chỉ Let's Encrypt, rồi chuyển WebSocket vào relay qua mạng nội bộ Docker. Cổng 8080 của relay không mở ra ngoài.

### 2.2 Chạy thử relay trên máy cá nhân (không TLS, chỉ trong mạng nội bộ)

Bấm đúp **`relay.bat`** (cần chạy `setup.bat` trước). Script tự build relay, sinh `RELAY_LINK_SECRET` lưu ở `%LOCALAPPDATA%\P2PFileShare\relay-link-secret.txt` (ngoài thư mục dự án), rồi in các địa chỉ dùng được. Chạy tay:

```powershell
.\mvnw.cmd -f relay-server\pom.xml package
$env:RELAY_PORT=8080; java -jar relay-server\target\p2p-relay-server.jar
```

Trong ứng dụng nhập `ws://127.0.0.1:8080/ws` hoặc `ws://192.168.x.x:8080/ws`. Ứng dụng **chỉ chấp nhận `ws://` (không mã hoá) với địa chỉ localhost / mạng nội bộ**. Qua Internet bắt buộc dùng `wss://`.

### 2.2b Không có VPS: relay trên máy 1, công khai qua Cloudflare Tunnel

Trên máy 1, mở cmd/PowerShell trong thư mục dự án và chạy:

```powershell
.\relay.bat -Tunnel
```

Script chạy relay trên máy này (chỉ nghe `127.0.0.1`), tự tải `cloudflared` lần đầu (kiểm tra SHA-256 theo GitHub hoặc chữ ký số của Cloudflare), mở Cloudflare Quick Tunnel miễn phí (không cần tài khoản), rồi in ra địa chỉ dạng `wss://<tên-ngẫu-nhiên>.trycloudflare.com/ws` và chép vào clipboard. Nhập địa chỉ này trên **cả 2 máy**. Cả 2 máy chỉ mở kết nối ra ngoài, nên dùng được với Wi-Fi + 4G mà không cần mở cổng router. Địa chỉ đổi mỗi lần chạy; giữ cửa sổ mở suốt buổi demo.

> Trạng thái: **chưa kiểm thử** (môi trường phát triển không được phép mở tunnel công khai). Dữ liệu đi qua hạ tầng Cloudflare: Cloudflare kết thúc TLS ở biên của họ giống một reverse proxy, nên Cloudflare cũng thấy dữ liệu chưa mã hoá đầu cuối, như relay.

### 2.3 Không dùng Docker: Java + Nginx + systemd

```bash
sudo apt install openjdk-17-jre-headless nginx certbot python3-certbot-nginx
# chép relay-server/target/p2p-relay-server.jar lên /opt/p2p-relay/
sudo cp deploy/nginx-relay.conf.example /etc/nginx/sites-available/p2p-relay   # sửa server_name
sudo ln -s /etc/nginx/sites-available/p2p-relay /etc/nginx/sites-enabled/
sudo certbot --nginx -d relay.ten-mien.com
sudo cp deploy/relay.env.example /etc/p2p-relay.env && sudo chmod 600 /etc/p2p-relay.env   # sửa khoá
sudo cp deploy/p2p-relay.service /etc/systemd/system/ && sudo systemctl enable --now p2p-relay
```

Nginx phải có `proxy_set_header Upgrade/Connection` và `proxy_buffering off` (đã có trong file mẫu).

### 2.4 Relay tự chạy TLS (không có reverse proxy)

```bash
RELAY_TLS_KEYSTORE=/certs/relay.p12 RELAY_TLS_KEYSTORE_PASSWORD=... RELAY_PORT=443 java -jar p2p-relay-server.jar
```

Chứng chỉ phải do CA được Java tin cậy cấp (Let's Encrypt). Ứng dụng không chấp nhận chứng chỉ tự ký, trừ khi máy chạy app thêm chứng chỉ đó vào truststore (`-Djavax.net.ssl.trustStore=...`).

## 3. Biến môi trường của relay

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `RELAY_DOMAIN` | (bắt buộc với Caddy) | Tên miền để Caddy xin chứng chỉ |
| `RELAY_LINK_SECRET` | ngẫu nhiên mỗi lần chạy | Khoá HMAC ký link token (≥ 32 ký tự). Không đặt thì liên kết mất khi relay khởi động lại |
| `RELAY_BIND` / `RELAY_PORT` | `0.0.0.0` / `8080` | Địa chỉ lắng nghe |
| `RELAY_TLS_KEYSTORE`, `RELAY_TLS_KEYSTORE_PASSWORD` | trống | Relay tự chạy TLS (mục 2.4) |
| `RELAY_TRUST_PROXY` | `false` (`true` trong compose) | Lấy IP thật từ `X-Forwarded-For` khi relay nằm sau proxy tin cậy |
| `RELAY_INVITE_TTL_SECONDS` | `600` | Thời hạn mã kết nối |
| `RELAY_INVITE_MAX_USES` | `1` | Số lần dùng của 1 mã |
| `RELAY_HEARTBEAT_SECONDS` / `RELAY_IDLE_TIMEOUT_SECONDS` | `15` / `45` | Chu kỳ ping; im lặng quá lâu thì coi máy đã mất mạng |
| `RELAY_MAX_SESSIONS` | `500` | Số máy online tối đa |
| `RELAY_MAX_CONN_PER_IP` | `20` | Số kết nối tối đa từ 1 IP |
| `RELAY_MAX_STREAMS_PER_SESSION` | `64` | Số luồng ảo đồng thời của 1 máy |
| `RELAY_SESSION_QUEUE_BYTES` | `4194304` | Byte tối đa chờ gửi tới 1 máy (backpressure) |
| `RELAY_STALL_TIMEOUT_SECONDS` | `20` | Máy không nhận dữ liệu quá lâu thì đóng phiên |
| `RELAY_RESOLVE_MAX_FAILS` / `..._PER_IP` | `5` / `20` | Số lần nhập sai mã: mỗi phiên trong 60 s / mỗi IP trong 10 phút |

## 4. Cấu hình phía ứng dụng

Địa chỉ relay nhập trong giao diện (tab *🌐 Kết nối mạng* → *🌍 Internet*) và được nhớ cho lần sau. Có thể đặt sẵn và chỉnh thời gian chờ bằng system property (`-D...`) hoặc biến môi trường:

| System property | Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|---|
| `p2p.relay.url` | `P2P_RELAY_URL` | (trống) | Địa chỉ relay, ví dụ `wss://relay.ten-mien.com/ws` |
| `p2p.mode` | | lần trước | `internet` để mở thẳng chế độ Internet |
| `p2p.relay.autoconnect` | | lần trước | `true` để tự kết nối relay khi mở app |
| `p2p.relay.heartbeatSeconds` | `P2P_RELAY_HEARTBEAT_SECONDS` | `10` | Chu kỳ PING tới relay |
| `p2p.relay.timeoutSeconds` | `P2P_RELAY_TIMEOUT_SECONDS` | `30` | Không nhận được gì từ relay quá lâu thì kết nối lại |
| `p2p.relay.maxReconnectAttempts` | `P2P_RELAY_MAX_RECONNECT_ATTEMPTS` | `30` | Số lần kết nối lại liên tiếp (0 = không giới hạn, backoff tối đa 60 s) |
| `p2p.peer.heartbeatSeconds` | `P2P_PEER_HEARTBEAT_SECONDS` | `5` | Chu kỳ PING tới từng peer |
| `p2p.peer.timeoutSeconds` | `P2P_PEER_TIMEOUT_SECONDS` | `15` | Peer không trả lời quá lâu thì hiện Offline |
| `p2p.download.retryMaxWaitSeconds` | `P2P_DOWNLOAD_RETRY_MAX_WAIT_SECONDS` | `600` | Tải xuống chờ mạng tối đa bao lâu trước khi báo thất bại |

Ví dụ (PowerShell): `$env:P2P_RELAY_URL="wss://relay.ten-mien.com/ws"; .\run.bat`, hoặc `.\mvnw.cmd exec:java -Dp2p.mode=internet -Dp2p.relay.url=wss://relay.ten-mien.com/ws`.

## 5. Sử dụng trên 2 laptop

1. Cả 2 máy mở app → tab **🌐 Kết nối mạng** → nút **🌍 Internet**.
2. Nhập địa chỉ relay → **🔌 Kết nối relay**. Góc phải hiện **🟢 Relay: Online**, ô *Mã của bạn* hiện mã dạng `K7QM-4XZ9` kèm thời gian còn lại.
3. **Máy B** (chia sẻ): tab *📂 Tài liệu chia sẻ* → chọn thư mục. Đọc / gửi *Mã của bạn* cho máy A (Zalo, đọc qua điện thoại…).
4. **Máy A**: nhập mã vào *Mã máy kia* → **🔗 Kết nối**.
5. **Máy B** hiện hộp thoại *Peer "Máy A (qua Internet)" muốn kết nối!* → **Chấp nhận** (45 giây, hết giờ coi như từ chối).
6. Hai máy có tab **Kết nối: …** để xem và tải file của nhau; tab *Tìm kiếm* và *Lịch sử tải* dùng như LAN. Bảng Internet hiện trạng thái *Online / Offline / Connecting / Reconnecting* của từng máy.
7. Ngắt: chọn máy trong bảng → **❌ Ngắt kết nối** (cả 2 máy cùng ngắt, relay huỷ liên kết).

Mã kết nối dùng 1 lần và hết hạn sau 10 phút. Mỗi lần mở lại ứng dụng, danh tính đổi, nên phải trao mã mới.

## 6. Lệnh kiểm tra kết nối

```powershell
# relay có chạy và có chứng chỉ hợp lệ không
curl.exe -v https://relay.ten-mien.com/health
# cổng 443 có tới được từ mạng hiện tại không
Test-NetConnection relay.ten-mien.com -Port 443
# DNS trỏ đúng IP VPS chưa
Resolve-DnsName relay.ten-mien.com
```

```bash
# trên VPS
docker compose ps
docker compose logs --tail=100 relay      # "Peer xxxxxxxx online", "Liên kết ... qua mã mời"
docker compose logs --tail=100 caddy      # lỗi xin chứng chỉ
ss -tlnp | grep -E ':80|:443'
```

## 7. Xử lý sự cố

| Hiện tượng | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| "Kết nối relay qua Internet phải dùng wss://" | Nhập `ws://` với địa chỉ Internet | Dùng `wss://ten-mien/ws` |
| Relay: Reconnecting mãi, chi tiết "PKIX path building failed" | Chứng chỉ tự ký / sai tên miền | Dùng Caddy/Let's Encrypt, URL đúng tên miền trong chứng chỉ |
| "Không kết nối được relay: Connection refused / timed out" | Firewall VPS chưa mở 443, relay chưa chạy, DNS sai | `Test-NetConnection`, `docker compose ps`, mở cổng ở nhà cung cấp VPS |
| Kết nối được nhưng rớt sau ~60 giây | Proxy cắt kết nối im lặng | Caddy mặc định không cắt. Nginx cần `proxy_read_timeout 1h` (đã có trong mẫu). Relay ping mỗi 15 s |
| "Mã kết nối sai hoặc đã hết hạn" | Mã đã dùng, quá 10 phút, gõ nhầm | Máy kia bấm *🔄 Tạo mã mới* |
| "Nhập sai quá nhiều lần" | Vượt giới hạn thử | Chờ 1 phút (theo máy) hoặc 10 phút (theo IP) |
| "Máy kia đang offline" | Máy kia chưa kết nối relay hoặc vừa mất mạng | Kiểm tra máy kia hiện *Relay: Online* |
| "Chưa liên kết với máy này" | Đã ngắt kết nối, hoặc relay khởi động lại khi chưa đặt `RELAY_LINK_SECRET` | Nhập lại mã. Đặt `RELAY_LINK_SECRET` cố định |
| Máy kia hiện 🔴 Offline nhưng relay của mình Online | Máy kia mất mạng / tắt app / ngủ | Chờ máy kia có mạng lại. Tải xuống đang chạy sẽ tự tải tiếp |
| Tải xuống "Mất kết nối, đang chờ kết nối lại…" | Mất mạng giữa chừng | Không cần làm gì: có mạng lại là tự tải tiếp từ chunk đã xong (tối đa chờ 10 phút) |
| Wi-Fi công ty / trường chặn | Chặn WebSocket hoặc cổng lạ | Relay dùng cổng 443 chuẩn HTTPS nên thường đi qua được. Nếu có proxy kiểm tra TLS, thử 4G |

Windows Firewall **không** cần mở cổng vào cho Internet Mode, vì ứng dụng chỉ mở kết nối ra ngoài. Rule tường lửa chỉ cần cho LAN Mode.

## 8. Hạn chế kỹ thuật

- Mọi dữ liệu đi qua relay: tốc độ bị giới hạn bởi băng thông VPS, và lưu lượng VPS tính cả 2 chiều.
- TLS chỉ bảo vệ đoạn máy ↔ relay. **Relay nhìn thấy dữ liệu ở dạng rõ (chưa có mã hoá đầu cuối).** Chỉ dùng relay do mình quản lý.
- Chưa thử kết nối trực tiếp (hole punching) khi NAT cho phép.
- Danh tính sinh mới mỗi lần mở app: mở lại phải ghép đôi lại.
- Relay giữ trạng thái trong RAM. Khởi động lại thì mã mời cũ mất hiệu lực; liên kết được khôi phục nếu đặt `RELAY_LINK_SECRET`.

## 9. Hướng phát triển

- Mã hoá đầu cuối giữa 2 peer (TLS/Noise trên `RelayStream`, xác thực bằng khoá Ed25519 sẵn có).
- Thử kết nối trực tiếp qua STUN/ICE, relay chỉ làm dự phòng.
- Lưu khoá danh tính để ghép đôi lâu dài, danh bạ peer.
- Nhiều relay, chọn relay gần nhất; số liệu giám sát (Prometheus).
