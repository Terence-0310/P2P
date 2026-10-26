# P2PFileShare

Ứng dụng desktop (JavaFX) chia sẻ tài liệu học tập trực tiếp giữa các máy trong cùng mạng LAN theo mô hình **Peer-to-Peer**, không cần máy chủ trung tâm.

## Tính năng

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

Java 17, JavaFX 17 (FXML), Socket TCP/UDP Multicast, đa luồng (thread pool, `wait/notify`), Gson, Apache PDFBox, Apache POI, JUnit 5.

## Kiến trúc mạng

Mỗi peer mở 3 kênh:

| Kênh | Giao thức | Cổng | Vai trò |
|---|---|---|---|
| Discovery | UDP Multicast | `230.0.0.1:8888` | Tìm peer: `P2P_DISCOVER_REQUEST` → `P2P_DISCOVER_RESPONSE\|peerId\|tên\|filePort\|controlPort` |
| Control | TCP, text 1 dòng UTF-8 | 7000–7999 (ngẫu nhiên) | Kết nối/ngắt, danh sách file, tìm kiếm, tóm tắt, đổi tên |
| File | TCP, nhị phân (`DataInputStream`) | 6000–6999 (ngẫu nhiên) | Metadata + chunk |

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

Server chỉ phục vụ `peerId` đã được chấp nhận kết nối, và chặn đường dẫn thoát khỏi thư mục chia sẻ (`../`).

## Cấu trúc mã nguồn

```
src/main/java/org/example/p2pfileshare/
├── controller/   Giao diện JavaFX (RootController nối các service và tab)
├── model/        PeerInfo, SharedFile*, SearchResult, DownloadHistory, ...
├── network/
│   ├── discovery/  PeerDiscovery (UDP Multicast)
│   ├── control/    ControlServer, ControlClient, ControlProtocol
│   ├── protocol/   FileTransferProtocol
│   └── transfer/   ChunkedFileServer, ChunkedFileClient, DownloadControl
├── service/      PeerService, FileShareService, SearchService, DownloadJob,
│                 DocumentSummaryService, SimpleAIService, HistoryService
└── util/         AppConfig (Preferences), FileHashUtil, DownloadHistoryManager,
                  FolderWatcher (theo dõi thư mục), TransferStats (tốc độ, thời gian còn lại)
```

## Chạy ứng dụng

Yêu cầu JDK 17 trở lên.

- **IntelliJ IDEA**: mở thư mục dự án và chạy `HelloApplication`
- **Maven**: `mvn clean javafx:run` (hoặc `mvnw.cmd clean javafx:run` trên Windows)

Có thể mở nhiều cửa sổ trên cùng một máy để thử, vì mỗi lần chạy app dùng cổng và `peerId` khác nhau. Nếu không tìm thấy peer, hãy kiểm tra Windows Firewall đã cho phép Java nhận kết nối trên mạng Private.

### Kịch bản demo

1. Máy A và B mở app, nhập tên.
2. Máy B: tab *📂 Tài liệu chia sẻ* → chọn thư mục.
3. Máy A: tab *🌐 Mạng LAN* → *🔄 Quét LAN* → chọn B → *🔗 Kết nối...*; máy B bấm *Chấp nhận*.
4. Máy A: tab *Kết nối: B* → *⬇ Tải xuống* (thử Tạm dừng/Tiếp tục), hoặc chuột phải vào file → *✨ Tóm tắt file*.
5. Máy A: tab *Tìm kiếm* → nhập từ khóa → tải từ kết quả.

## Kiểm thử

```
mvn test
```

Gồm 37 test, trong đó có integration test chạy server và client thật trên localhost:

- **Kênh truyền file**: tải song song nhiều chunk, file rỗng, từ chối peer chưa được chấp nhận, chặn `../`, dọn file tạm khi hủy
- **Kênh điều khiển**: chấp nhận/từ chối, không hỏi lại peer cũ, danh sách file có thư mục con, tóm tắt giữ đúng tiếng Việt, chặn tìm kiếm/tóm tắt khi chưa được chấp nhận, ngắt kết nối cả khi peer đã tắt
- **Service/tiện ích**: chia sẻ thư mục con, bỏ qua file tạm/ẩn, đặt tên không ghi đè, FolderWatcher, lịch sử UTF-8 và file lịch sử hỏng, tốc độ/thời gian còn lại, tóm tắt, phân loại, giao thức

## Lịch sử phiên bản

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

| MSSV | Họ và tên | Công việc đã thực hiện | Merge Requests |
|---|---|---|---|
| 2280602799 | Nguyễn Đức Anh Tài | Khám phá peer bằng UDP Multicast; quản lý repository, tổng hợp báo cáo | … |
| 2380602094 | Trịnh Danh Thi | Kênh điều khiển TCP: kết nối, cấp quyền, ngắt kết nối | … |
| 2380600546 | Trần Hồng Đức | Truyền file theo chunk, SHA-256, tải song song, tạm dừng/tiếp tục | … |
| 2380602377 | Trần Anh Trung | Giao diện JavaFX, lịch sử tải xuống, đồng bộ danh sách file | … |
| 2380601944 | Lê Tấn Tài | Tìm kiếm, phân loại, tóm tắt tài liệu; kiểm thử JUnit | … |

---

<sub>Phát triển dựa trên mã nguồn P2PFileShare của Ngô Khắc Cường và Nguyễn Gia Kiệt.</sub>
