# Giải thích mã nguồn P2PFileShare và chuẩn bị vấn đáp

Tài liệu ôn tập cho buổi bảo vệ: giải thích từng phần code theo đúng luồng chạy, kèm các câu hỏi giảng viên hay hỏi và gợi ý trả lời.

**Mục lục**

1. [Giới thiệu trong 1 phút](#1-giới-thiệu-trong-1-phút)
2. [Những con số cần nhớ](#2-những-con-số-cần-nhớ)
3. [Kiến trúc tổng thể](#3-kiến-trúc-tổng-thể)
4. [App khởi động như thế nào](#4-app-khởi-động-như-thế-nào)
5. [Kênh 1: Khám phá peer (UDP Multicast)](#5-kênh-1-khám-phá-peer-udp-multicast)
6. [Kênh 2: Điều khiển (TCP văn bản)](#6-kênh-2-điều-khiển-tcp-văn-bản)
7. [Kênh 3: Truyền file (TCP nhị phân)](#7-kênh-3-truyền-file-tcp-nhị-phân)
8. [Tải file: tạm dừng, tiếp tục, tải tiếp](#8-tải-file-tạm-dừng-tiếp-tục-tải-tiếp)
9. [Các tính năng phụ](#9-các-tính-năng-phụ)
10. [Đa luồng và đồng bộ](#10-đa-luồng-và-đồng-bộ)
11. [Bảo mật và xử lý lỗi](#11-bảo-mật-và-xử-lý-lỗi)
12. [Kiểm thử](#12-kiểm-thử)
13. [Quy trình Git của đề tài](#13-quy-trình-git-của-đề-tài)
14. [Câu hỏi vấn đáp thường gặp](#14-câu-hỏi-vấn-đáp-thường-gặp)
15. [Hạn chế và hướng phát triển](#15-hạn-chế-và-hướng-phát-triển)
16. [Kịch bản demo 5 phút](#16-kịch-bản-demo-5-phút)

---

## 1. Giới thiệu trong 1 phút

> P2PFileShare là ứng dụng desktop Java/JavaFX cho sinh viên chia sẻ tài liệu **trực tiếp giữa các máy trong cùng mạng LAN**, không cần máy chủ. Mỗi máy vừa là client vừa là server, giao tiếp qua **3 kênh**:
> 1. **UDP Multicast** để tự tìm các máy khác.
> 2. **TCP văn bản** để xin kết nối, cấp quyền, lấy danh sách file, tìm kiếm, tóm tắt.
> 3. **TCP nhị phân** để tải file: chia khối 1 MB, tải song song nhiều kết nối, kiểm tra SHA-256, tạm dừng / tiếp tục / tải tiếp.
>
> Chỉ máy được người dùng **chấp nhận** mới xem và tải được file. Bộ kiểm thử có **50 test JUnit**, tất cả đều đạt.

---

## 2. Những con số cần nhớ

| Thông số | Giá trị | Ở đâu trong code |
|---|---|---|
| Nhóm multicast | `230.0.0.1:8888` | `PeerDiscovery` |
| Thời gian quét LAN | 3 giây | `PeerService.scanPeers` |
| Cổng điều khiển | 7000–7999 (ngẫu nhiên) | `RootController.CONTROL_PORT` |
| Cổng truyền file | 6000–6999 (ngẫu nhiên) | `RootController.FILE_PORT` |
| Kích thước khối | 1 MB | `ChunkedFileServer.DEFAULT_CHUNK_SIZE` |
| Số kết nối tải song song | mặc định 4, cho phép 1–16 | `ChunkedFileClient` |
| Số lần thử lại mỗi khối | 3 | `ChunkedFileClient.MAX_RETRIES` |
| Thread pool server file | 64 luồng | `ChunkedFileServer.POOL_SIZE` |
| Timeout mở kết nối điều khiển | 3 s | `ControlClient.CONNECT_TIMEOUT_MS` |
| Timeout đọc điều khiển | 10 s | `ControlClient.DEFAULT_READ_TIMEOUT_MS` |
| Chờ người kia bấm Chấp nhận | 60 s | `ControlClient.CONNECT_DECISION_TIMEOUT_MS` |
| Timeout tải khối (mở / đọc) | 5 s / 8 s | `ChunkedFileClient` |
| Server điều khiển chờ đọc | 15 s | `ControlServer.handleClient` |
| Server file đóng kết nối rảnh | 60 s | `ChunkedFileServer.IDLE_TIMEOUT_MS` |
| Số test tự động | 50 (10 lớp test) | `src/test/java` |

---

## 3. Kiến trúc tổng thể

```
        Peer A                                   Peer B
 ┌────────────────────┐                  ┌────────────────────┐
 │ Giao diện JavaFX   │                  │ Giao diện JavaFX   │
 │ PeerDiscovery  ────┼── UDP 8888 ──────┼── PeerDiscovery    │
 │ ControlClient  ────┼── TCP 7xxx ──────┼── ControlServer    │
 │ ChunkedFileClient ─┼── TCP 6xxx ──────┼── ChunkedFileServer│
 └────────────────────┘                  └────────────────────┘
```

**Vì sao tách 3 kênh?** Mỗi kênh dùng giao thức hợp nhất với việc của nó:

- Tìm máy: gửi 1 lần tới nhiều máy, mất gói cũng không sao → **UDP Multicast**.
- Lệnh điều khiển: ngắn nhưng phải đến chắc chắn → **TCP**, mỗi lệnh 1 dòng văn bản.
- File: dữ liệu lớn, nhị phân → **TCP** với `DataInputStream` / `DataOutputStream`.
- Đang tải file lớn vẫn gửi được lệnh khác, vì hai việc nằm trên hai kênh khác nhau.

**Tổ chức gói (package):**

| Gói | Vai trò |
|---|---|
| `controller` | Giao diện JavaFX. `RootController` khởi tạo và nối mọi thứ |
| `service` | Nghiệp vụ: `PeerService`, `FileShareService`, `SearchService`, `DownloadJob`, `DocumentSummaryService`, `SimpleAIService`, `HistoryService` |
| `network.discovery` | `PeerDiscovery` |
| `network.control` | `ControlServer`, `ControlClient`, `ControlProtocol` |
| `network.protocol` | `FileTransferProtocol` |
| `network.transfer` | `ChunkedFileServer`, `ChunkedFileClient`, `DownloadControl` |
| `model`, `util` | Dữ liệu (`PeerInfo`, `SharedFile`...) và tiện ích (`FileHashUtil`, `FolderWatcher`, `TransferStats`...) |

---

## 4. App khởi động như thế nào

Điểm vào là `HelloApplication`, nạp `MainView.fxml`. Phần việc chính nằm trong `RootController.initialize()`:

1. Hỏi tên hiển thị (lưu bằng `java.util.prefs.Preferences` qua `AppConfig`, lần sau không hỏi lại).
2. Sinh `myPeerId = UUID.randomUUID()`. **Mỗi lần mở app là một peerId mới.**
3. Chọn ngẫu nhiên `FILE_PORT = 6000 + random(1000)` và `CONTROL_PORT = 7000 + random(1000)`. Nhờ vậy mở được nhiều cửa sổ trên cùng một máy để thử.
4. `PeerService.start()` → bật **responder multicast** để máy khác tìm thấy mình.
5. Tạo `FileShareService` (server file), `ControlClient`, `ControlServer`.
6. Nối quyền: `fileShareService.setAccessChecker(controlServer::isAccepted)`. Server file hỏi `ControlServer` xem peer đã được chấp nhận chưa.
7. Đăng ký callback: thư mục chia sẻ thay đổi → gửi `CMD:FILES_CHANGED` cho các peer đang xem. Có `SEARCH_REQ` → tìm và trả lời. Có `DISCONNECT_NOTIFY` → hiện thông báo.
8. Truyền các service vào từng tab (`PeerTab`, `ShareTab`, `SearchTab`, `HistoryTab`, `IncomingConnectionTab`).
9. Khi đóng cửa sổ: `onExit()` dừng các server và responder rồi mới thoát.

---

## 5. Kênh 1: Khám phá peer (UDP Multicast)

File: `network/discovery/PeerDiscovery.java`

### 5.1 Responder (để người khác tìm thấy mình)

- Một luồng daemon `discovery-responder` mở `MulticastSocket(8888)`.
- **Tham gia nhóm `230.0.0.1` trên mọi card mạng** đang bật, không phải loopback, hỗ trợ multicast, có IPv4 (`multicastInterfaces()`).
  - Lý do: máy có Wi-Fi, Ethernet, card ảo VMware/VirtualBox/Hyper-V. Nếu chỉ dùng card mặc định, card đó có thể là card ảo → không thấy nhau.
- Vòng lặp: nhận gói. Nếu nội dung là `P2P_DISCOVER_REQUEST` → trả lời **unicast** về đúng IP:port của người hỏi:

```
P2P_DISCOVER_RESPONSE|peerId|tênHiểnThị|filePort|controlPort
```

- Tên hiển thị lấy qua `Supplier`, nên đổi tên thì lần trả lời sau dùng tên mới ngay.
- Gửi trả lời lỗi cho một peer → chỉ ghi log, responder **không dừng** (sửa ở PR #13).

### 5.2 Quét (`discoverPeers`)

1. Tạo `MulticastSocket` tạm, gửi `P2P_DISCOVER_REQUEST` **qua từng card mạng** (`setNetworkInterface`).
2. Nhận phản hồi đến hết 3 giây. Mỗi lần `receive` chỉ chờ phần thời gian còn lại.
3. `parseResponse` kiểm tra gói: đúng tiền tố, đúng số trường, peerId không rỗng, cổng là số trong 1–65535. Sai → bỏ qua, **không làm hỏng cả lần quét**.
4. Gom vào `LinkedHashMap` theo peerId: bỏ chính mình, bỏ trùng (một peer trả lời qua nhiều card).

### 5.3 Câu hỏi hay gặp

- **Vì sao dùng multicast, không dùng broadcast?** Multicast chỉ đến máy đã tham gia nhóm, đỡ làm phiền máy khác. Broadcast gửi cho mọi máy trong mạng con.
- **Vì sao trả lời bằng unicast?** Chỉ người hỏi cần câu trả lời, không cần gửi lại cả nhóm.
- **Mất gói UDP thì sao?** Người dùng bấm Quét lại. Thông điệp khám phá chấp nhận được việc mất gói.

---

## 6. Kênh 2: Điều khiển (TCP văn bản)

Files: `network/control/ControlServer.java`, `ControlClient.java`, `ControlProtocol.java`

### 6.1 Định dạng

Mỗi thông điệp là **1 dòng UTF-8**, các trường cách nhau bởi `|`:

```
LỆNH|từPeer|đếnPeer|ghiChú...
```

- Đọc bằng `BufferedReader.readLine()`, ghi bằng `PrintWriter.println()`. **Ký tự xuống dòng là dấu kết thúc thông điệp** (cách tách thông điệp trên dòng byte TCP).
- Dữ liệu người dùng nhập (tên) phải qua `ControlProtocol.sanitize()`: thay `|` bằng `/`, bỏ ký tự xuống dòng → không làm lệch trường.
- Mỗi lệnh **mở một kết nối ngắn**: kết nối → gửi → đọc trả lời → đóng.

### 6.2 Các lệnh

| Lệnh | Trả lời | Ý nghĩa |
|---|---|---|
| `CONNECT_REQUEST|A|B|tên|ctrlPort|filePort` | `CONNECT_ACCEPT` / `CONNECT_REJECT` | Xin kết nối; gửi kèm cổng để B liên lạc lại A |
| `LIST_FILES|A|B` | `LIST_FILES_RESPONSE` (tên `\t` đường dẫn `\t` kích thước, nối bằng `<NL>`) | Lấy danh sách file |
| `SEARCH_REQ|A|từKhóa` | B mở kết nối **mới** gửi `SEARCH_RES|B|...` | Tìm kiếm |
| `SUMMARIZE_REQ|A|đườngDẫn` | `SUMMARIZE_RES|đườngDẫn|base64` | Tóm tắt file trên máy B |
| `DISCONNECT_REQUEST|A|B` | `DISCONNECT_NOTIFY` | Ngắt kết nối |
| `UPDATE_NAME|A|tênMới` | không | Báo đổi tên |
| `CMD:FILES_CHANGED|A|*` | không | Báo "danh sách file của tôi vừa đổi" |

**Vì sao kết quả tóm tắt mã hóa Base64?** Nội dung tóm tắt có thể chứa xuống dòng và ký tự `|`. Base64 biến nó thành một chuỗi an toàn trên một dòng.

### 6.3 ControlServer xử lý thế nào

- Luồng `control-server` chạy `ServerSocket.accept()` trong vòng lặp.
- **Mỗi kết nối → 1 luồng `control-handler`** đọc 1 dòng, phân loại theo tiền tố hoặc tên lệnh.
- `setSoTimeout(15_000)`: client không gửi gì trong 15 s thì giải phóng luồng.

### 6.4 Kết nối có xác nhận (phần quan trọng)

1. A gửi `CONNECT_REQUEST`. ControlClient chờ trả lời tối đa **60 s** (thời gian người B quyết định).
2. Ở B, luồng mạng `control-handler` gọi callback `onIncomingConnect` trong `RootController`. Callback này:
   - dùng `Platform.runLater` để **hiện hộp thoại trên luồng giao diện** (JavaFX chỉ cho vẽ giao diện trên luồng giao diện);
   - dùng `CountDownLatch` để **luồng mạng đứng chờ** đến khi người dùng bấm;
   - lưu kết quả vào `AtomicBoolean` để luồng mạng đọc an toàn.
3. Chấp nhận → `registerAcceptedPeer`: thêm peerId vào `acceptedPeers` (một `ConcurrentHashMap.newKeySet()`), lưu thông tin và thời điểm kết nối.
4. Lần sau peer đó kết nối lại → **không hỏi lại**.

### 6.5 `acceptedPeers` là "chìa khóa"

- `LIST_FILES`, `SEARCH_REQ`, `SUMMARIZE_REQ` đều kiểm tra tập này.
- **Kênh file cũng kiểm tra tập này** qua `controlServer::isAccepted`.
- Chặn peer (`disconnectPeer`): **xóa khỏi tập trước**, rồi mới gửi `DISCONNECT_NOTIFY`. Peer đã tắt máy thì gửi thất bại, nhưng peer đó vẫn bị chặn.

---

## 7. Kênh 3: Truyền file (TCP nhị phân)

Files: `network/protocol/FileTransferProtocol.java`, `network/transfer/ChunkedFileServer.java`, `ChunkedFileClient.java`

### 7.1 Giao thức

```
FILE_META_REQUEST|peerId|tênFile
  -> FILE_META_RESPONSE, tên, kíchThước(long), chunkSize(int), sốKhối(int),
     sha256 cả file, sha256 từng khối
GET_CHUNK|peerId|tênFile|chỉSố
  -> CHUNK_DATA, chỉSố(int), độDài(int), sha256, dữ liệu(byte[])
(lỗi) -> ERROR, lýDo
```

- Chuỗi gửi bằng `writeUTF` (có 2 byte độ dài ở đầu), số bằng `writeInt` / `writeLong`, dữ liệu khối có trường độ dài → bên nhận biết chính xác phải đọc bao nhiêu byte (`readFully`).
- Tên file đặt giữa `peerId` và `chỉSố`. Khi phân tích, lấy `|` **cuối cùng** làm ranh giới → tên file chứa `|` vẫn đúng.

### 7.2 ChunkedFileServer

- Luồng `chunked-file-server` chạy `accept()`, giao kết nối cho **thread pool 64 luồng**.
- Một kết nối phục vụ **nhiều yêu cầu liên tiếp** (client giữ kết nối để tải nhiều khối).
- **Mỗi yêu cầu** đều kiểm tra `accessChecker.test(peerId)` → bị chặn giữa chừng thì khối tiếp theo bị từ chối ngay.
- `resolveSharedFile`: `root.resolve(tên).normalize()` rồi kiểm tra `startsWith(root)` → **chặn `../`** đọc file ngoài thư mục chia sẻ.
- **Cache metadata** theo đường dẫn, kèm kích thước và thời gian sửa. File không đổi thì không băm lại (băm file 1 GB tốn vài giây).
- `computeMeta` đọc file **một lượt**: vừa băm từng khối, vừa cập nhật băm cả file.
- Gửi khối: `RandomAccessFile.seek(i × 1MB)` → đọc đúng khối đó.

### 7.3 ChunkedFileClient: luồng tải (`downloadFile`)

1. Gửi `FILE_META_REQUEST`, nhận metadata.
2. Ba file tạm cạnh file đích:
   - `tên.part`: dữ liệu, **cấp phát trước đúng kích thước** (`setLength`);
   - `tên.bitmap`: đánh dấu khối nào đã xong;
   - `tên.meta.properties`: metadata. Metadata khác lần trước (file nguồn đã đổi) → xóa hết, tải lại từ đầu.
3. Đưa chỉ số các khối **chưa xong** vào `ConcurrentLinkedQueue`.
4. Tạo **thread pool N worker** (mặc định 4). Mỗi worker:
   - giữ **1 kết nối TCP**, lặp: `poll()` lấy chỉ số khối → `GET_CHUNK` → nhận dữ liệu;
   - **so SHA-256 của khối với hash trong metadata**, sai thì thử lại, tối đa 3 lần;
   - ghi bằng `FileChannel.write(buffer, chỉSố × 1MB)`;
   - đánh dấu bitmap trong `synchronized (progress)`, báo tiến độ.
5. Tất cả xong → tính **SHA-256 cả file `.part`**, so với metadata. Khớp → đổi tên `.part` thành file đích, xóa `.bitmap` và `.meta`.

### 7.4 Vì sao nhiều luồng ghi chung một file mà không lỗi?

`FileChannel.write(buffer, position)` ghi theo **vị trí tuyệt đối** và **không dùng con trỏ file chung**. Mỗi khối có vị trí riêng (`i × 1MB`), các vùng ghi không chồng lên nhau → không cần khóa.

Chỉ phần **bitmap** (dữ liệu dùng chung) mới cần `synchronized`.

### 7.5 Vì sao tải song song nhanh hơn?

- Một kết nối TCP phải chờ: gửi yêu cầu → server đọc đĩa và gửi → nhận xong mới yêu cầu khối tiếp. Nhiều kết nối thì lúc kết nối này chờ, kết nối khác vẫn đang truyền → tận dụng băng thông tốt hơn.
- **Không nên mở quá nhiều** (giới hạn 16): tốn luồng và socket ở cả hai bên, tranh chấp đọc đĩa và băng thông, không nhanh thêm.

---

## 8. Tải file: tạm dừng, tiếp tục, tải tiếp

### 8.1 `DownloadControl`: tạm dừng / tiếp tục / hủy

```java
public void checkpoint() throws InterruptedException {
    synchronized (this) {
        while (paused && !cancelled) wait();   // tạm dừng: luồng ngủ ở đây
        if (cancelled) throw new InterruptedException("Download cancelled");
    }
}
```

- Worker gọi `checkpoint()` **trước mỗi yêu cầu khối** và **trước mỗi lần ghi**.
- `pause()`: đặt `paused = true` → các worker dừng ở checkpoint kế tiếp (khối đang tải dở vẫn tải xong).
- `resume()`: `paused = false` + `notifyAll()` → đánh thức mọi worker.
- `cancel()`: `cancelled = true` + `notifyAll()` → worker ném exception, thoát, rồi dọn file tạm.
- Hai cờ khai báo `volatile` để các luồng thấy giá trị mới ngay.
- **Vì sao `while` mà không `if`?** Phòng trường hợp luồng bị đánh thức giả (spurious wakeup) hoặc bị đánh thức khi điều kiện chưa đổi. Dùng `while` để kiểm tra lại điều kiện.

### 8.2 `DownloadJob`: trạng thái của một lần tải

`NEW → RUNNING ⇄ PAUSED → COMPLETED / FAILED / CANCELLED`

- Chạy `downloadFile` trên luồng riêng `DownloadJob-<tên>` để giao diện không bị treo.
- `setState` là `synchronized`. Khi đã kết thúc thì không đổi trạng thái nữa (tránh báo 2 lần).
- Giao diện nhận tiến độ và trạng thái qua callback, rồi `Platform.runLater` để cập nhật.

### 8.3 Tải tiếp sau khi tắt app / mất kết nối

- Lỗi mạng sau 3 lần thử → `failed`, **giữ `.part`, `.bitmap`, `.meta`**.
- Lần sau tải đúng file đó vào đúng chỗ đó: metadata còn khớp → nạp bitmap → chỉ tải khối còn thiếu.
- Đặt tên tránh ghi đè: nếu đã có file trùng tên → `tên (1).ext`. **Nhưng nếu còn `.part` thì giữ nguyên tên** để tải tiếp.

### 8.4 Tốc độ và thời gian còn lại (`TransferStats`)

- Mỗi ≥ 300 ms tính tốc độ tức thời = byte tăng thêm / thời gian.
- Làm mượt: `tốc độ = 0.3 × tức thời + 0.7 × tốc độ cũ` (trung bình trượt) → con số không nhảy liên tục.
- Thời gian còn lại = byte còn lại / tốc độ.

---

## 9. Các tính năng phụ

### 9.1 Tìm kiếm trên nhiều peer

1. B gõ từ khóa → gửi `SEARCH_REQ|B|từKhóa` tới **từng peer đã kết nối**.
2. Peer A: `ControlServer` kiểm tra B đã được chấp nhận chưa → `RootController` gọi `SearchService.buildResponsePayload` (lọc file theo tên, không phân biệt hoa thường; `*` hoặc rỗng = tất cả).
3. A **mở kết nối mới** tới cổng điều khiển của B, gửi `SEARCH_RES|A|...` (tên, đường dẫn, kích thước, môn học).
4. B nhận, đưa vào bảng kết quả. Kết quả đến từ nhiều peer vào các thời điểm khác nhau → bất đồng bộ.

### 9.2 Tự đồng bộ danh sách file (`FolderWatcher`)

- Dùng `WatchService` của Java NIO theo dõi thư mục chia sẻ **và mọi thư mục con** (thư mục con mới tạo cũng được đăng ký).
- **Debounce**: chép 100 file sẽ sinh hàng trăm sự kiện. Mỗi sự kiện hủy lịch cũ và đặt lịch mới sau một khoảng ngắn → chỉ chạy callback **1 lần** khi thư mục đã "yên".
- Callback → gửi `CMD:FILES_CHANGED` cho mọi peer đang xem kho file của mình → bên kia tự tải lại danh sách.

### 9.3 Phân loại môn học (`SimpleAIService`)

- **Không phải AI thật**: là luật theo từ khóa trong **đường dẫn và tên file**.
- Bỏ dấu tiếng Việt (`Normalizer` NFD + xóa dấu kết hợp, `đ → d`) rồi so khớp.
- `contain` = khớp chuỗi con; `containWord` = khớp nguyên từ (tránh `os` khớp nhầm trong `cosodulieu`).
- Thứ tự `if` quan trọng: môn đứng trước được ưu tiên.

### 9.4 Tóm tắt tài liệu (`DocumentSummaryService`)

- Đọc văn bản: PDF bằng **PDFBox** (tối đa 10 trang), DOCX bằng **Apache POI**, TXT đọc UTF-8.
- **Tóm tắt trích xuất (extractive)**: không viết câu mới mà chọn câu quan trọng nhất trong bài.
  1. Tách câu theo `. ! ?` hoặc dòng trống.
  2. Đếm tần suất mỗi từ trong cả bài (bỏ **stop words** như "là", "của", "và", "the"...).
  3. Điểm câu = tổng tần suất các từ / số từ. Câu dưới 4 từ bị loại (tiêu đề, số trang).
  4. Lấy **3 câu điểm cao nhất**, giữ thứ tự xuất hiện gốc.
- Tóm tắt chạy **trên máy sở hữu file** → không phải tải file về mới tóm tắt được.

### 9.5 Lịch sử tải xuống

- Lưu JSON bằng **Gson** tại `~/.p2pfileshare/download_history.json`, đọc/ghi UTF-8, các hàm `synchronized`.
- File lịch sử hỏng → không crash, bắt đầu lại danh sách rỗng.

---

## 10. Đa luồng và đồng bộ

**Các luồng chính trong một peer:**

| Luồng | Việc |
|---|---|
| JavaFX Application Thread | Vẽ giao diện, xử lý bấm nút |
| `discovery-responder` | Trả lời gói khám phá |
| `control-server` + mỗi kết nối 1 `control-handler` | Kênh điều khiển |
| `chunked-file-server` + pool 64 luồng | Phục vụ tải file |
| `DownloadJob-*` + pool `chunk-worker-*` | Tải file |
| `folder-watcher` + `folder-watcher-debounce` | Theo dõi thư mục |

**Công cụ đồng bộ đã dùng:**

| Công cụ | Dùng ở đâu | Để làm gì |
|---|---|---|
| `Platform.runLater` | Mọi chỗ cập nhật giao diện từ luồng nền | JavaFX chỉ cho đổi giao diện trên luồng giao diện |
| `CountDownLatch` | Hộp thoại chấp nhận kết nối | Luồng mạng chờ người dùng bấm |
| `synchronized` + `wait/notifyAll` | `DownloadControl` | Tạm dừng / tiếp tục |
| `volatile` | Cờ `paused`, `cancelled`, `running` | Luồng khác thấy giá trị mới ngay |
| `ConcurrentHashMap`, `ConcurrentLinkedQueue`, `CopyOnWriteArrayList` | `acceptedPeers`, hàng đợi khối, danh sách listener | Nhiều luồng đọc ghi an toàn, không cần khóa tay |
| `AtomicBoolean` | Cờ `failed` của lần tải, kết quả hộp thoại | Cập nhật nguyên tử |
| `ExecutorService` | Server file, worker tải | Giới hạn và tái sử dụng luồng |
| Luồng daemon | Các luồng lắng nghe | Không giữ app sống khi đóng cửa sổ |

**Nếu thao tác mạng chạy trên luồng giao diện thì sao?** Giao diện **treo** trong lúc chờ mạng (có thể đến 60 s). Vì vậy mọi lệnh mạng chạy trên luồng nền.

---

## 11. Bảo mật và xử lý lỗi

**Đã có:**

- Chỉ peer được chấp nhận mới liệt kê, tìm kiếm, tóm tắt, tải được, và **kiểm tra ở từng yêu cầu**.
- Chặn path traversal `../` ở cả kênh file và lệnh tóm tắt.
- SHA-256 từng khối và cả file → phát hiện dữ liệu sai lệch.
- Mọi kết nối có timeout → peer tắt máy không làm treo app.
- Gói khám phá sai định dạng bị bỏ qua.
- Thống nhất **UTF-8** khi đọc ghi văn bản → tiếng Việt không lỗi giữa các máy.

**Chưa có (nói thật nếu bị hỏi):**

- Dữ liệu **chưa mã hóa** (chưa có TLS) → người cùng mạng có thể nghe lén.
- `peerId` do client tự khai → chưa xác thực danh tính thật.
- Chỉ hoạt động trong LAN có chuyển tiếp multicast.

---

## 12. Kiểm thử

Chạy: `mvnw clean test` (Windows: `.\mvnw.cmd clean test`) → **50 test, 0 lỗi**.

| Lớp test | Số | Kiểm tra |
|---|---|---|
| `TwoPeerEndToEndTest` | 4 | 2 peer thật trên 1 máy: multicast thật; từ chối/chấp nhận; tải + SHA-256; tạm dừng/tiếp tục; bị chặn giữa chừng rồi tải tiếp |
| `ControlChannelTest` | 10 | ControlServer/Client thật: chấp nhận, không hỏi lại, từ chối, danh sách file, tóm tắt, chặn `../`, tìm kiếm, ngắt kết nối |
| `PeerDiscoveryTest` | 7 | Phân tích gói khám phá đúng/sai, cổng sai, tên có `|` |
| `ChunkedTransferTest` | 7 | Tải nhiều khối, file rỗng, peer lạ bị từ chối, chặn `../`, hủy, 1 và 8 kết nối |
| `FileTransferProtocolTest` | 3 | Phân tích lệnh, tên file chứa `|` |
| `FileShareServiceTest` | 5 | Thư mục con, bỏ file tạm/ẩn, đặt tên không ghi đè |
| `DocumentSummaryServiceTest` | 4 | Tóm tắt giữ tiếng Việt |
| `SearchServiceTest` | 3 | Tìm kiếm |
| `SimpleAIServiceTest` | 2 | Phân loại |
| `UtilTest` | 5 | Lịch sử UTF-8, file lịch sử hỏng, tốc độ, thời gian còn lại |

**Kiểm thử tích hợp chạy thế nào?** Mỗi test xin một **cổng trống** (`new ServerSocket(0)`), chạy server thật trên `localhost`, client thật kết nối vào. Hai peer chạy trong cùng một tiến trình, mỗi peer có cổng riêng.

Kịch bản kiểm thử tay: [KICH_BAN_KIEM_THU.md](KICH_BAN_KIEM_THU.md).

---

## 13. Quy trình Git của đề tài

- Commit đầu `465355c` là **commit nhập mã nền** bản 2.0, phát triển tiếp từ mã của Ngô Khắc Cường và Nguyễn Gia Kiệt (ghi công trong README và [BASELINE_AND_GIT_HISTORY.md](BASELINE_AND_GIT_HISTORY.md)). Lịch sử không bị sửa ngày.
- Từ 26/10/2026: **Issue → nhánh `feature/<MSSV>-issue-<số>-<tên>` → commit Conventional Commits → Pull Request → review → merge**.
- Nhánh `main` được bảo vệ: không push trực tiếp, cần 1 Approve.

**Phần việc của 2280602799 trên Git (cần giải thích được):**

| PR | Nội dung | File chính |
|---|---|---|
| #12 | Sửa Maven Wrapper (thiếu `.jar` vì bị `.gitignore`) → chế độ `only-script` | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` |
| #13 | Discovery: kiểm tra gói phản hồi, responder không dừng khi gửi lỗi, quét đúng thời gian | `PeerDiscovery.java`, `PeerDiscoveryTest.java` |
| #14 | Cấu hình số kết nối tải song song 1–16 | `ChunkedFileClient.java`, `ChunkedTransferTest.java` |
| #17 | Test end-to-end 2 peer trên 1 máy | `TwoPeerEndToEndTest.java` |
| #8, #15, #19 | Mẫu PR, CONTRIBUTING, tài liệu kiến trúc, kiểm thử, kịch bản test | `.github/`, `docs/` |

---

## 14. Câu hỏi vấn đáp thường gặp

### Mạng

**H: TCP khác UDP thế nào? Vì sao chỗ này dùng TCP, chỗ kia dùng UDP?**
TCP có kết nối, tin cậy, đúng thứ tự (bắt tay 3 bước, báo nhận, truyền lại). UDP không kết nối, nhanh, có thể mất gói. Khám phá dùng UDP Multicast vì cần gửi tới nhiều máy và chấp nhận mất gói. Lệnh và file dùng TCP vì không được sai một byte.

**H: Multicast hoạt động thế nào?**
Địa chỉ nhóm thuộc dải 224.0.0.0–239.255.255.255. Máy muốn nhận phải `joinGroup` (thông báo bằng IGMP). Gói gửi tới địa chỉ nhóm thì mọi thành viên đều nhận.

**H: Vì sao phải join trên mọi card mạng?**
Máy có card ảo (VMware, Hyper-V). Nếu join hoặc gửi trên card mặc định mà card đó là card ảo thì các máy thật không thấy nhau.

**H: TCP là dòng byte, làm sao biết một thông điệp kết thúc ở đâu?**
Kênh điều khiển: mỗi thông điệp một dòng, kết thúc bằng xuống dòng. Kênh file: `writeUTF` có độ dài ở đầu, khối dữ liệu có trường độ dài, bên nhận dùng `readFully` đọc đúng số byte.

**H: `setSoTimeout` khác `connect(addr, timeout)` thế nào?**
`connect(..., timeout)` giới hạn thời gian **thiết lập** kết nối. `setSoTimeout` giới hạn thời gian **chờ đọc** dữ liệu sau khi đã kết nối. Hết hạn thì ném `SocketTimeoutException`.

**H: Vì sao cổng chọn ngẫu nhiên? Peer khác biết cổng bằng cách nào?**
Để mở nhiều cửa sổ trên một máy mà không trùng cổng. Cổng được gửi trong gói phản hồi khám phá và trong `CONNECT_REQUEST`.

### Truyền file

**H: Vì sao chia khối 1 MB?**
Để tải song song, kiểm tra và thử lại từng khối, tải tiếp từ chỗ dừng. Khối quá nhỏ thì tốn nhiều yêu cầu; khối quá lớn thì lỗi một khối phải tải lại nhiều.

**H: Làm sao biết file tải về đúng?**
So SHA-256 từng khối với hash trong metadata khi nhận, và so SHA-256 cả file khi xong.

**H: Vì sao so với hash trong metadata chứ không chỉ hash server gửi kèm khối?**
Metadata lấy một lần lúc bắt đầu. So với nó bảo đảm mọi khối thuộc **cùng một phiên bản file**, kể cả khi file nguồn bị sửa giữa chừng.

**H: Nhiều luồng ghi một file có bị đè nhau không?**
Không. `FileChannel.write(buffer, position)` ghi theo vị trí tuyệt đối, mỗi khối một vùng riêng. Chỉ bitmap dùng chung mới cần `synchronized`.

**H: Tải tiếp hoạt động thế nào?**
`.part` chứa dữ liệu, `.bitmap` đánh dấu khối đã xong, `.meta.properties` lưu metadata. Lần sau metadata khớp → chỉ tải khối thiếu.

**H: Tạm dừng có dừng ngay lập tức không?**
Không hẳn. Worker dừng ở `checkpoint()` kế tiếp. Khối đang tải dở sẽ tải xong rồi mới dừng.

### Đa luồng

**H: `wait()` / `notifyAll()` dùng thế nào? Vì sao phải trong `synchronized`?**
`wait()` nhả khóa và ngủ đến khi được `notify`. Phải giữ khóa của đối tượng mới gọi được (không thì `IllegalMonitorStateException`). Kiểm tra điều kiện bằng `while` để phòng đánh thức giả.

**H: `volatile` để làm gì?**
Bảo đảm luồng khác thấy giá trị mới nhất của biến (không đọc bản cũ trong cache của CPU). Không thay được khóa cho thao tác phức hợp.

**H: Vì sao dùng `Platform.runLater`?**
JavaFX chỉ cho thay đổi giao diện trên JavaFX Application Thread. Luồng mạng muốn cập nhật giao diện phải đẩy việc đó về luồng giao diện.

**H: Hộp thoại chấp nhận kết nối chạy ở luồng nào, luồng mạng chờ thế nào?**
Hộp thoại hiện trên luồng giao diện qua `Platform.runLater`. Luồng mạng chờ bằng `CountDownLatch.await()`. Người dùng bấm xong thì `countDown()`, kết quả đọc từ `AtomicBoolean`.

**H: Vì sao server file dùng thread pool mà server điều khiển tạo luồng mới cho mỗi kết nối?**
Kết nối tải file sống lâu, có thể nhiều → giới hạn 64 luồng để không cạn tài nguyên. Kết nối điều khiển rất ngắn. Đây cũng là một hạn chế có thể cải tiến: dùng pool cho cả kênh điều khiển.

### Bảo mật

**H: Một máy lạ có tải trộm được không?**
Không, nếu chưa được chấp nhận: server file kiểm tra `peerId` ở mọi yêu cầu. Nhưng `peerId` chưa được xác thực mật mã, và dữ liệu chưa mã hóa. Hướng phát triển là TLS và khóa công khai.

**H: Path traversal là gì, chặn thế nào?**
Gửi tên file kiểu `../../secret.txt` để đọc file ngoài thư mục chia sẻ. Chặn bằng `root.resolve(tên).normalize()` rồi kiểm tra kết quả vẫn `startsWith(root)`.

### Thiết kế và quy trình

**H: P2P khác client-server thế nào? Ứng dụng này là P2P thuần hay lai?**
P2P thuần trong LAN: không có máy chủ trung tâm, mỗi peer vừa phục vụ vừa tải. Khám phá phân tán qua multicast, không có tracker.

**H: Commit đầu tiên có toàn bộ code, có phải Big Bang Commit không?**
Đó là commit nhập mã nền bản 2.0 khi tạo repo (phát triển tiếp từ mã của Ngô Khắc Cường và Nguyễn Gia Kiệt, có ghi công). Nhóm không sửa lịch sử để che việc này. Từ khi tạo repo, mọi thay đổi đi theo Issue → nhánh → PR → review, có thể xem ở các PR #8–#21.

**H: Em đã làm phần nào trên Git? Giải thích một thay đổi.**
Chọn PR #13 hoặc #14 (mục 13): nói vấn đề, cách sửa, test chứng minh.

---

## 15. Hạn chế và hướng phát triển

| Hạn chế | Hướng phát triển |
|---|---|
| Chưa mã hóa, chưa xác thực peer | TLS + cặp khóa công khai |
| Chỉ chạy trong LAN có multicast | Tracker / relay, NAT traversal |
| Một file chỉ tải từ một peer | Tải từ nhiều peer cùng lúc như BitTorrent |
| Cổng ngẫu nhiên, chưa thử lại khi trùng | Thử cổng khác khi bind lỗi |
| Server điều khiển tạo luồng không giới hạn | Dùng thread pool |
| Log bằng `System.out` | Dùng thư viện logging |
| Tìm kiếm chỉ theo tên file, phân biệt dấu | Issue #5: tìm theo đường dẫn, bỏ dấu |
| Chưa có CI | GitHub Actions chạy test cho mỗi PR |

---

## 16. Kịch bản demo 5 phút

Chuẩn bị trước: clone repo vào đường dẫn **không dấu** (ví dụ `D:\code\P2P`, vì `javafx:run` lỗi khi đường dẫn có dấu), mở 2 cửa sổ `mvn javafx:run`, chuẩn bị 1 thư mục có file PDF/TXT và 1 file khoảng 1 GB.

1. **(30 s)** Giới thiệu đề tài: mục 1.
2. **(30 s)** Máy B bấm **Quét LAN** → thấy máy A. Nói: "UDP Multicast 230.0.0.1:8888, gửi trên mọi card mạng."
3. **(45 s)** B kết nối → A hiện hộp thoại. Bấm Từ chối trước để chứng minh kiểm soát quyền, rồi kết nối lại và Chấp nhận. Nói về `acceptedPeers`, `CountDownLatch`.
4. **(60 s)** B tải file 1 GB → **Tạm dừng** → **Tiếp tục**. Nói: khối 1 MB, 4 kết nối song song, `FileChannel` ghi theo vị trí, `wait/notifyAll`.
5. **(30 s)** So SHA-256: `certutil -hashfile <file> SHA256` ở hai bên → giống nhau.
6. **(30 s)** Tìm kiếm `socket`, tóm tắt một file TXT.
7. **(30 s)** A chặn B ở tab **Kết nối đến** → B không tải được nữa.
8. **(45 s)** Chạy `mvnw clean test` → 50 test pass. Mở GitHub: Issue, PR, review.

Mẹo: nếu demo mạng trục trặc, chuyển sang chạy `TwoPeerEndToEndTest` để chứng minh các luồng chính vẫn hoạt động.
