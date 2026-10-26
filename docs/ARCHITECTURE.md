# Kiến trúc 3 kênh mạng

Bảng cổng và cú pháp lệnh của từng kênh có trong [README → Kiến trúc mạng](../README.md#kiến-trúc-mạng). Tài liệu này mô tả phần README chưa nói tới: lớp nào xử lý kênh nào, chạy trên luồng nào, timeout bao nhiêu, và các giới hạn đã biết.

```
              Peer A                                         Peer B
 ┌──────────────────────────────┐              ┌──────────────────────────────┐
 │ RootController (JavaFX)      │              │ RootController (JavaFX)      │
 │  ├─ PeerService ─────────────┼── UDP 230.0.0.1:8888 ──┼─ PeerDiscovery responder │
 │  ├─ ControlClient ───────────┼── TCP 7000–7999 ───────┼─ ControlServer           │
 │  └─ DownloadJob              │              │                              │
 │      └─ ChunkedFileClient ───┼── TCP 6000–6999 ───────┼─ ChunkedFileServer       │
 └──────────────────────────────┘              └──────────────────────────────┘
```

Mỗi lần chạy app, `RootController` chọn ngẫu nhiên cổng file (`6000 + random(1000)`) và cổng điều khiển (`7000 + random(1000)`), rồi quảng bá hai cổng này qua kênh discovery.

## 1. Discovery: UDP Multicast (`network/discovery/PeerDiscovery`)

| Thành phần | Luồng | Chi tiết |
|---|---|---|
| Responder | 1 luồng daemon `discovery-responder` | `MulticastSocket(8888)`, `joinGroup` trên **mọi** card mạng đang bật, có IPv4 và hỗ trợ multicast. Nhận `P2P_DISCOVER_REQUEST` thì trả lời unicast `P2P_DISCOVER_RESPONSE\|peerId\|tên\|filePort\|controlPort` |
| Quét (`discoverPeers`) | Luồng gọi (từ `PeerService.scanPeers`, timeout 3000 ms) | Gửi request ra từng card mạng, gom phản hồi theo `peerId` (một peer trả lời qua nhiều card chỉ được giữ một lần), bỏ qua chính mình |

Tên hiển thị được lấy qua `Supplier`, nên đổi tên thì lần trả lời sau dùng tên mới ngay. Gói phản hồi là dữ liệu không tin cậy, nên được kiểm tra định dạng và port (PR #13).

## 2. Control: TCP văn bản (`network/control/`)

| Thành phần | Luồng | Chi tiết |
|---|---|---|
| `ControlServer` | 1 luồng `accept`, mỗi kết nối 1 luồng riêng | Mỗi message là 1 dòng UTF-8 `LỆNH\|from\|to\|...` (`ControlProtocol.parse`). Socket phía server có `soTimeout` 15 s |
| `ControlClient` | Luồng của nơi gọi | Mỗi lệnh mở một kết nối mới: connect timeout 3 s, đọc 10 s; chờ người kia bấm Chấp nhận tối đa 60 s; tóm tắt file tối đa 60 s |

**Quyền truy cập:** `ControlServer.acceptedPeers` (`ConcurrentHashMap.newKeySet()`) là nguồn duy nhất xác định peer nào đã được chấp nhận. `LIST_FILES`, `SEARCH_REQ`, `SUMMARIZE_REQ` và cả kênh file đều kiểm tra tập này. Ngắt kết nối hoặc chặn peer thì xóa peer khỏi tập trước, sau đó mới gửi `DISCONNECT_NOTIFY`. Nếu gửi thông báo thất bại (peer đã tắt), peer đó vẫn bị chặn.

Giá trị do người dùng nhập (tên hiển thị) đi qua `ControlProtocol.sanitize` để không chứa `|` hay ký tự xuống dòng.

## 3. File: TCP nhị phân (`network/transfer/`)

| Thành phần | Luồng | Chi tiết |
|---|---|---|
| `ChunkedFileServer` | 1 luồng `chunked-file-server` và thread pool 64 luồng | Chunk 1 MB. Metadata (SHA-256 cả file và từng chunk) được cache theo đường dẫn. `RandomAccessFile` nhảy thẳng tới chunk. Idle timeout 60 s. Chặn `../` và peer chưa được chấp nhận |
| `ChunkedFileClient` | Thread pool `chunk-worker-*`, mặc định 4 worker (cấu hình 1–16, PR #14) | Mỗi worker giữ 1 kết nối, lấy chunk còn thiếu từ hàng đợi chung `ConcurrentLinkedQueue`, ghi bằng `FileChannel.write(buf, position)`, thử lại tối đa 3 lần mỗi chunk. Connect 5 s, đọc 8 s |

**Tải tiếp (resume):** `<file>.part` được cấp phát trước đúng kích thước, `<file>.bitmap` đánh dấu chunk đã xong (ghi trong `synchronized (progress)`), `<file>.meta.properties` lưu metadata. Lần tải sau, nếu metadata khác (file nguồn đã đổi) thì xóa cả ba và tải lại từ đầu.

**Toàn vẹn dữ liệu:** mỗi chunk được so SHA-256 với hash trong metadata (không chỉ hash server gửi kèm chunk). Tải xong thì so SHA-256 cả file; sai thì xóa bitmap.

**Tạm dừng / Hủy:** `DownloadControl.checkpoint()` được gọi trước mỗi request và trước mỗi lần ghi. Pause sẽ chờ tại đó, Cancel ném `InterruptedException`, rồi dọn file tạm.

## 4. Giới hạn đã biết

| Giới hạn | Ảnh hưởng | Ghi chú |
|---|---|---|
| Cổng chọn ngẫu nhiên, không thử lại khi bị chiếm | Hiếm khi xảy ra, nhưng nếu trùng cổng thì server không khởi động | Có thể tạo issue riêng |
| `ControlServer` tạo 1 luồng cho mỗi kết nối, không giới hạn | Bị nhiều kết nối dồn dập có thể tạo nhiều luồng | Mỗi lệnh điều khiển là một kết nối ngắn |
| Multicast cần firewall cho phép Java trên mạng Private | Không tìm thấy peer | Có ghi trong README |
| Log dùng `System.out` / `System.err` | Khó lọc log khi chẩn đoán lỗi | Chưa dùng thư viện logging |
