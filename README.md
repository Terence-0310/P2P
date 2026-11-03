# P2PFileShare

Ứng dụng desktop (JavaFX) chia sẻ tài liệu học tập giữa các máy theo mô hình **Peer-to-Peer**, với 2 chế độ kết nối:

- **LAN**: các máy cùng mạng tìm nhau bằng UDP và truyền file TCP trực tiếp, không cần máy chủ.
- **Internet**: 2 máy ở 2 mạng khác nhau (Wi-Fi nhà, 4G/5G, sau NAT/CGNAT) kết nối qua một **Relay Server** công khai bằng WebSocket bảo mật (`wss://`). Đây là **P2P có hỗ trợ chuyển tiếp** (relay-assisted), không phải kết nối trực tiếp: relay chuyển tiếp dữ liệu theo luồng và không lưu file. Chi tiết: [docs/INTERNET_MODE.md](docs/INTERNET_MODE.md), [docs/NETWORK_ARCHITECTURE.md](docs/NETWORK_ARCHITECTURE.md).

## Tính năng

- **Internet Mode**: kết nối bằng **mã kết nối** 8 ký tự (hết hạn sau 10 phút, dùng 1 lần), relay xác thực từng máy bằng chữ ký Ed25519, người nhận vẫn phải bấm *Chấp nhận*. Không cần mở cổng router hay có IP công khai
- **Phát hiện mất kết nối**: heartbeat tới relay và tới từng peer, hiển thị *Online / Offline / Connecting / Reconnecting*, tự kết nối lại relay (exponential backoff); đang tải mà mất mạng thì tác vụ chờ và **tự tải tiếp** từ các chunk đã xác minh khi có mạng lại
- **Tìm peer trong LAN** bằng UDP Multicast, gửi và lắng nghe trên mọi card mạng (Wi-Fi, Ethernet, card ảo VMware/VirtualBox)
- **Kết nối có xác nhận**: peer nhận yêu cầu phải bấm *Chấp nhận* thì bên kia mới xem, tìm kiếm, tải và tóm tắt được file
- **Chia sẻ thư mục, kể cả thư mục con**. Nút *Thêm tài liệu...* chép file vào thư mục chia sẻ
- **Tự đồng bộ danh sách file**: thêm/xóa/sửa file (kể cả từ Explorer) thì bảng của mình và tab của các peer đang xem đều tự cập nhật
- **Tải file theo chunk 1 MB**:
  - tải song song 4 kết nối TCP, mỗi kết nối dùng lại cho nhiều chunk
  - kiểm tra SHA-256 từng chunk và cả file
  - tự thử lại khi lỗi mạng
  - **Tạm dừng / Tiếp tục / Hủy**, và **tải tiếp** sau khi tắt app hoặc mất kết nối (file `.part` + `.bitmap`)
  - hiển thị **tốc độ tải và thời gian còn lại**
  - không ghi đè file đã có: tự đặt tên `ten (1).ext`
- **Tìm kiếm file** trên các peer đã kết nối, lọc theo tên, môn học, peer
- **Phân loại môn học / loại tài liệu** tự động theo tên file
- **Tóm tắt nội dung** PDF/DOCX/TXT ngay trên máy peer sở hữu file (tóm tắt trích xuất theo tần suất từ khóa)
- **Lịch sử tải xuống**: nhấp đúp để mở file, chuột phải để mở thư mục chứa hoặc xóa mục, cột báo file còn hay đã bị xóa. Lưu tại `~/.p2pfileshare/download_history.json`
- Tab **Kết nối đến**: xem ai đang truy cập kho file của mình (kèm thời điểm kết nối) và chặn bất kỳ lúc nào
- Đổi tên hiển thị (báo cho mọi peer đang kết nối), thông báo khi bị ngắt kết nối

## Công nghệ

Java 17+, JavaFX 21 (FXML), Socket TCP/UDP Multicast, WebSocket (`java.net.http` phía ứng dụng, RFC 6455 tự cài đặt phía relay), TLS, Ed25519, đa luồng (thread pool, `wait/notify`), Gson, Apache PDFBox, Apache POI, JUnit 5.

## Kiến trúc mạng

Mỗi peer mở 3 kênh:

| Kênh | Giao thức | Cổng | Vai trò |
|---|---|---|---|
| Discovery | UDP Multicast | `230.0.0.1:8888` | Tìm peer: `P2P_DISCOVER_REQUEST` → `P2P_DISCOVER_RESPONSE\|peerId\|tên\|filePort\|controlPort` |
| Control | TCP, text 1 dòng UTF-8 | 7000–7999 (cổng trống) | Kết nối/ngắt, danh sách file, tìm kiếm, tóm tắt, đổi tên |
| File | TCP, nhị phân (`DataInputStream`) | 6000–6999 (cổng trống) | Metadata + chunk |

### Kênh điều khiển (Control)

```
CONNECT_REQUEST|fromId|toId|tên|ctrlPort|filePort → CONNECT_ACCEPT / CONNECT_REJECT
LIST_FILES|fromId|toId                     → LIST_FILES_RESPONSE|...|tên\tđườngDẫn\tkíchThước<NL>...
DISCONNECT_REQUEST|fromId|toId             → DISCONNECT_NOTIFY
SEARCH_REQ|fromId|từKhóa                   → (kết nối mới) SEARCH_RES|fromId|tên\tđườngDẫn\tkíchThước\tmôn<NL>...
SUMMARIZE_REQ|fromId|đườngDẫn              → SUMMARIZE_RES|đườngDẫn|base64(UTF-8)
UPDATE_NAME|fromId|tênMới
CMD:FILES_CHANGED|fromId|*                 (1 chiều) báo peer đang xem kho file tải lại danh sách
```

Peer gửi kèm cổng của mình trong `CONNECT_REQUEST`, nên bên nhận biết cách liên lạc lại (đổi tên, ngắt kết nối, báo file thay đổi) mà không cần quét LAN thấy peer đó. Peer đã được chấp nhận thì lần kết nối sau không bị hỏi lại. Mọi kết nối điều khiển đều có timeout (3 giây khi mở kết nối), nên peer đã tắt máy không làm treo ứng dụng.

### Kênh truyền file

```
FILE_META_REQUEST|peerId|tênFile        → FILE_META_RESPONSE, tên, kíchThước, chunkSize, số chunk, sha256 file, sha256 từng chunk
GET_CHUNK|peerId|tênFile|chỉSốChunk     → CHUNK_DATA, chỉ số, độ dài, sha256, dữ liệu
(lỗi)                                   → ERROR, lý do
```

Server chỉ phục vụ `peerId` đã được chấp nhận kết nối, và chặn đường dẫn thoát khỏi thư mục chia sẻ (`../`, đường dẫn tuyệt đối, symlink trỏ ra ngoài).

### Internet Mode (qua relay)

```
 Máy A (Wi-Fi, sau NAT)                 Relay công khai (VPS)                Máy B (4G, sau CGNAT)
 ControlClient/ChunkedFileClient        Caddy :443 (TLS) -> relay :8080      ControlServer/ChunkedFileServer
        │ TransportRouter                                                          ▲ InternetModeService
        └── RelayClient ══ wss:// (A chủ động mở ra ngoài) ══╗   ╔══ wss:// (B chủ động mở ra ngoài) ══ RelayClient
                                                             ╚═══╝ ghép luồng ảo theo mã kết nối
```

Tầng nghiệp vụ (kết nối, cấp quyền, danh sách file, tìm kiếm, tải theo chunk) **dùng chung cho cả 2 chế độ**; chỉ tầng truyền thông khác nhau: `LanTcpTransport` mở socket TCP, `RelayTransport` mở một *luồng ảo* (có flow control kiểu credit) trên kết nối WebSocket tới relay. Giao thức relay: [docs/NETWORK_ARCHITECTURE.md](docs/NETWORK_ARCHITECTURE.md).

## Cấu trúc mã nguồn

```
src/main/java/org/example/p2pfileshare/
├── controller/   Giao diện JavaFX (RootController nối các service và tab)
├── model/        PeerInfo, SharedFileLocal, SearchResult, DownloadHistory, ...
├── network/
│   ├── discovery/  PeerDiscovery (UDP Multicast)
│   ├── control/    ControlServer, ControlClient, ControlProtocol
│   ├── protocol/   FileTransferProtocol
│   ├── transfer/   ChunkedFileServer, ChunkedFileClient, DownloadControl
│   ├── transport/  StreamConnection, PeerTransport, LanTcpTransport, TransportRouter, InboundOrigin
│   └── relay/      RelayClient (WebSocket), RelayStream (luồng ảo + credit), RelayTransport, IdentityKey (Ed25519)
├── service/      PeerService, FileShareService, SearchService, DownloadJob, ConnectionService,
│                 InternetModeService, PeerHealthMonitor (heartbeat), DocumentSummaryService, ...
└── util/         AppConfig (Preferences), NetworkSettings (timeout cấu hình được), SafePaths,
                  FileHashUtil, DownloadHistoryManager, FolderWatcher, TransferStats

relay-server/     Relay Server độc lập (Java 17 thuần, không thư viện ngoài), Dockerfile
  src/shared/java   RelayProtocol: giao thức dùng chung giữa ứng dụng và relay
deploy/           docker-compose.yml + Caddyfile (wss tự động), mẫu Nginx, systemd, relay.env.example
```

## Chạy ứng dụng

### Máy mới (chưa cài gì)

Chép cả thư mục dự án sang máy, rồi bấm đúp **`setup.bat`** (cần Internet ở lần đầu). Script [scripts/setup.ps1](scripts/setup.ps1) sẽ:

1. Kiểm tra Windows 10+ và kết nối tới Maven Central.
2. Tìm JDK 17+ có sẵn. Nếu không có, tải **Eclipse Temurin JDK 21** (bản zip, không cần quyền Admin, có kiểm tra SHA-256) vào `%LOCALAPPDATA%\P2PFileShare\jdk-21`.
3. Build bằng `mvnw`. Maven Wrapper tự tải Maven, Maven tự tải JavaFX và các thư viện.
4. Thêm rule Windows Firewall cho Java (Windows hỏi quyền Admin, bấm *Yes*).
5. In IP của máy trong LAN, in bảng tóm tắt, rồi mở app.

Các lần sau, bấm đúp **`run.bat`**. Muốn mở 2 cửa sổ trên 1 máy để thử: `setup.bat -Instances 2` hoặc `run.bat -Instances 2`.

### Máy đã có JDK

Yêu cầu JDK 17 trở lên.

- **NetBeans**: *File → Open Project…* chọn thư mục dự án (NetBeans nhận ra dự án Maven), rồi bấm **Run Project** (F6). Debug (Ctrl+F5) cũng dùng được. Cấu hình nằm trong `nbactions.xml`.
- **IntelliJ IDEA**: mở thư mục dự án và chạy lớp `Launcher`
- **Maven**: `mvnw exec:java` (Windows: `.\mvnw.cmd exec:java`). Chạy được cả khi đường dẫn thư mục có dấu tiếng Việt.
- `mvnw javafx:run` cũng chạy được, nhưng **chỉ khi đường dẫn thư mục không có dấu**: plugin truyền đường dẫn qua dòng lệnh, Windows làm mất ký tự có dấu và báo `InvalidPathException`.

Có thể mở nhiều cửa sổ trên cùng một máy để thử, vì mỗi lần chạy app dùng cổng và `peerId` khác nhau.

### Tìm peer giữa 2 máy

- Lần chạy đầu trên Windows, app hỏi có cho phép qua **Windows Firewall** không. Bấm *Cho phép*: một cửa sổ PowerShell mở ra và Windows hỏi quyền Admin, bấm *Yes*. Nếu không thấy hộp thoại, bấm biểu tượng khiên nhấp nháy trên thanh taskbar. App thêm rule cho `java.exe` đang chạy (mọi cổng, mọi loại mạng), **cho phép ping** (ICMPv4 echo) và xoá rule *Block* mà Windows tạo khi lỡ bấm *Cancel*. Có thể làm lại bằng nút **🛡 Tường lửa** trong tab *Mạng LAN*, hoặc tắt app rồi bấm đúp **`firewall.bat`**. **Cần làm trên cả 2 máy.**
- Kiểm tra đường mạng tới máy kia: bấm đúp **`kiemtra.bat`** và nhập IP máy kia. Script ping thử, cho biết 2 máy có cùng dải mạng không, và liệt kê các nguyên nhân cần kiểm tra nếu ping thất bại.
- *Quét LAN* gửi cả **multicast** (`230.0.0.1:8888`) lẫn **broadcast** của từng mạng con, vì nhiều router chặn multicast nhưng vẫn cho broadcast.
- Nếu Wi-Fi vẫn chặn cả hai cách, dùng **➕ Thêm theo IP** và nhập IP máy kia (xem bằng `ipconfig`). App nhớ IP đó cho các lần quét sau.
- Wi-Fi công cộng hoặc Wi-Fi trường thường bật *client isolation*, chặn mọi kết nối giữa các máy. Khi đó không app nào kết nối được. Hãy dùng hotspot điện thoại hoặc router riêng.

### Kết nối 2 máy ở 2 mạng khác nhau (Internet Mode)

1. Chạy relay ở nơi cả 2 máy cùng truy cập được (chi tiết: [docs/INTERNET_MODE.md](docs/INTERNET_MODE.md)):
   - VPS có IP công khai: `cd deploy && cp relay.env.example relay.env` (sửa tên miền, khoá bí mật) rồi `docker compose up -d --build`;
   - không có VPS: trên máy 1 chạy `.\relay.bat -Tunnel` để công khai relay qua Cloudflare Tunnel miễn phí, dùng địa chỉ `wss://….trycloudflare.com/ws` mà script in ra (chế độ này chưa được kiểm thử);
   - chỉ thử trong cùng mạng: bấm đúp `relay.bat`.
2. Trên cả 2 máy (máy mới chạy `setup.bat` trước): tab *🌐 Kết nối mạng* → *🌍 Internet* → nhập địa chỉ relay → *🔌 Kết nối relay*.
3. Máy B đọc **Mã của bạn** (vd. `K7QM-4XZ9`) cho máy A. Máy A nhập vào *Mã máy kia* → *🔗 Kết nối*. Máy B bấm *Chấp nhận*.
4. Hai máy dùng tab *Kết nối: …*, *Tìm kiếm*, *Lịch sử* như ở chế độ LAN.

Kịch bản kiểm thử thật Wi-Fi + 4G: [docs/CROSS_NETWORK_TEST.md](docs/CROSS_NETWORK_TEST.md).

### Kịch bản demo

1. Máy A và B mở app, nhập tên.
2. Máy B: tab *📂 Tài liệu chia sẻ* → chọn thư mục.
3. Máy A: tab *🌐 Kết nối mạng* (chế độ *🏠 LAN*) → *🔄 Quét LAN* → chọn B → *🔗 Kết nối...*. Máy B bấm *Chấp nhận* trong 45 giây (hộp thoại tự đóng và coi như từ chối khi hết giờ). Kết nối là **2 chiều**: cả A và B đều hiện *Đã kết nối*, đều có tab *Kết nối: …* để xem và tải file của nhau. Bấm *Ngắt kết nối* ở bất kỳ máy nào, ở bất kỳ tab nào, thì cả 2 máy cùng ngắt.
4. Máy A: tab *Kết nối: B* → *⬇ Tải xuống* (thử Tạm dừng/Tiếp tục), hoặc chuột phải vào file → *✨ Tóm tắt file*.
5. Máy A: tab *Tìm kiếm* → nhập từ khóa → tải từ kết quả.

## Kiểm thử

```
.\mvnw.cmd clean test          (Windows)     ./mvnw clean test     (Linux/macOS)
.\mvnw.cmd package
.\mvnw.cmd -f relay-server\pom.xml package   -> relay-server\target\p2p-relay-server.jar
```

Gồm 102 test (2 test symlink tự bỏ qua trên Windows khi không có quyền tạo symlink), trong đó có integration test chạy server và client thật trên localhost:

- **Internet Mode** ([InternetModeIntegrationTest](src/test/java/org/example/p2pfileshare/internet/InternetModeIntegrationTest.java)): relay thật chạy cục bộ; đăng ký, mã kết nối, chấp nhận/từ chối, chặn peer chưa được chấp nhận và mạo danh, liệt kê/tìm kiếm, tải file nhỏ/lớn + SHA-256, tạm dừng/tiếp tục, **rút mạng đột ngột** (proxy giữ socket nhưng ngừng chuyển dữ liệu) rồi tải tiếp, peer offline, relay khởi động lại, mã sai/hết hạn/đoán mò, nhiều peer tải cùng lúc, chặn `../`, WSS/TLS
- **Relay Server** ([relay-server/src/test](relay-server/src/test/java/org/example/p2prelay)): handshake RFC 6455, chữ ký sai, lệnh trước khi xác thực, frame không mask, chuyển tiếp nhị phân, mã mời, giới hạn số lần thử, link token

- **Kênh truyền file**: tải song song nhiều chunk, file rỗng, từ chối peer chưa được chấp nhận, chặn `../`, dọn file tạm khi hủy
- **Kênh điều khiển**: chấp nhận/từ chối, không hỏi lại peer cũ, danh sách file có thư mục con, tóm tắt giữ đúng tiếng Việt, chặn tìm kiếm/tóm tắt khi chưa được chấp nhận, ngắt kết nối cả khi peer đã tắt
- **Service/tiện ích**: chia sẻ thư mục con, bỏ qua file tạm/ẩn, đặt tên không ghi đè, FolderWatcher, lịch sử UTF-8 và file lịch sử hỏng, tốc độ/thời gian còn lại, tóm tắt, phân loại, giao thức

## Lịch sử phiên bản

**3.0**
- Internet Mode qua Relay Server (wss), mã kết nối ngắn hạn, xác thực Ed25519, flow control theo luồng
- Tầng truyền thông tách khỏi tầng nghiệp vụ (`PeerTransport`), LAN Mode giữ nguyên
- Heartbeat relay + heartbeat peer, tự kết nối lại có backoff, tải tiếp tự động sau khi mất mạng
- Chặn symlink ra ngoài thư mục chia sẻ, không cho chia sẻ cả ổ đĩa / thư mục người dùng

**2.0**
- Tự đồng bộ danh sách file, chia sẻ thư mục con, *Thêm tài liệu...* chép file thật
- Tốc độ tải và thời gian còn lại, không ghi đè file trùng tên
- Lịch sử: mở file, mở thư mục chứa, xóa từng mục; lưu UTF-8 trong thư mục người dùng
- Sửa lỗi:
  - tab *Kết nối đến* thiếu peer chưa quét thấy, thời gian kết nối sai
  - thông báo xóa file không bao giờ tới được peer
  - mở trùng tab khi kết nối lại
  - không ngắt được kết nối với peer đã tắt máy
  - nút tải bị kẹt khi tải lỗi
  - lý do lỗi bị ghi đè; *Hủy* phải chờ 2–3 giây
  - một số thao tác mạng chạy trên luồng giao diện gây treo

**1.1**: bảo mật quyền truy cập, tải song song 4 kết nối, multicast trên mọi card mạng, sửa tóm tắt/UTF-8/tag/tìm kiếm, thêm test

## Quy trình làm việc trên Git

1. Cấu hình định danh: `git config user.name "MSSV_HoVaTen"` và `git config user.email "<email sinh viên>"`.
2. Tạo Issue cho mỗi đầu việc, gán cho đúng 1 người, gắn label (`bug`, `enhancement`, `frontend`, `backend`, `test`, `refactor`, `documentation`) và milestone.
3. Tạo nhánh từ `main`: `feature/<MSSV>-issue-<số>-<tên-ngắn>`, ví dụ `feature/2280602799-issue-7-pr-template`.
4. Commit theo Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`.
5. Mở Pull Request theo mẫu, ghi `Closes #<số>`, đính kèm ảnh chụp màn hình; cần ít nhất 1 thành viên khác Approve. Nhánh `main` được bảo vệ, không push trực tiếp.

Chi tiết xem [CONTRIBUTING.md](CONTRIBUTING.md).

## Nhóm thực hiện

Nhóm 12, lớp 23DTHA6, học phần Lập trình mạng máy tính.

| MSSV | Họ và tên | Công việc đã thực hiện | Pull Request (đã merge) |
|---|---|---|---|
| 2280602799 | Nguyễn Đức Anh Tài | Kiến trúc và giao thức 3 kênh; khám phá peer UDP Multicast; truyền file theo chunk; tích hợp hệ thống | #6, #8, #12, #13, #14, #15, #17, #19 |
| 2380602094 | Trịnh Danh Thi | Kênh điều khiển TCP: kết nối, cấp quyền, ngắt kết nối | Chưa có |
| 2380600546 | Trần Hồng Đức | Tìm kiếm file trên nhiều peer, đồng bộ danh sách file | Chưa có |
| 2380602377 | Trần Anh Trung | Giao diện JavaFX, lịch sử tải xuống | Chưa có |
| 2380601944 | Lê Tấn Tài | Phân loại, tóm tắt tài liệu; kiểm thử JUnit | Chưa có |
