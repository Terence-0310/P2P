# Báo cáo kiểm thử

Ngày chạy: 26/10/2026. Người chạy: Nguyễn Đức Anh Tài (2280602799).
Môi trường: Windows 11 Pro, OpenJDK 21.0.12.1, Maven 3.10.0 (cài trên máy), Maven Wrapper 3.3.4 / Maven 3.9.9 (sau PR #12).

Chỉ ghi kết quả của những lệnh đã thực sự chạy. Mục nào chưa chạy được đánh dấu **Chưa thực hiện**.

## 1. Kiểm thử tự động (JUnit 5)

### Trên `main` (commit `465355c`)

| Lệnh | Kết quả |
|---|---|
| `mvn -B clean test` | `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS |
| `mvn -B clean package` | 37 test PASS, tạo `target/P2PFileShare-2.0.jar` (không commit), BUILD SUCCESS |
| `.\mvnw.cmd clean test`, `./mvnw test` | **Lỗi** `Could not find or load main class org.apache.maven.wrapper.MavenWrapperMain`, vì thiếu `maven-wrapper.jar` (issue #9) |

Số test khớp với README ("Gồm 37 test"):

| Lớp test | Số test | Loại |
|---|---|---|
| `network.control.ControlChannelTest` | 10 | Tích hợp: `ControlServer` và `ControlClient` thật trên localhost |
| `network.protocol.FileTransferProtocolTest` | 3 | Đơn vị |
| `network.transfer.ChunkedTransferTest` | 5 | Tích hợp: `ChunkedFileServer` và `ChunkedFileClient` thật trên localhost |
| `service.DocumentSummaryServiceTest` | 4 | Đơn vị |
| `service.FileShareServiceTest` | 5 | Đơn vị và hệ thống file |
| `service.SearchServiceTest` | 3 | Đơn vị |
| `service.SimpleAIServiceTest` | 2 | Đơn vị |
| `util.UtilTest` | 5 | Đơn vị |
| **Tổng** | **37** | |

### Trên các nhánh của Pull Request đang mở

| PR | Nhánh | Lệnh | Kết quả |
|---|---|---|---|
| #12 | `feature/2280602799-issue-9-fix-maven-wrapper` | `.\mvnw.cmd -B clean test` (chưa có `~/.m2/wrapper`) và `sh ./mvnw -B test` | 37 PASS cả hai |
| #13 | `feature/2280602799-issue-10-discovery-validation` | `mvn -B clean test` | 44 PASS (37 cũ và 7 test mới trong `PeerDiscoveryTest`) |
| #14 | `feature/2280602799-issue-2-parallel-connections` | `mvn -B clean test` | 39 PASS (37 cũ và 2 test mới trong `ChunkedTransferTest`) |

Các nhánh được kiểm thử riêng rẽ, mỗi nhánh tách từ `main`. **Chưa** kiểm thử bản gộp cả ba. Sau khi merge cần chạy lại `.\mvnw.cmd clean test`; dự kiến có 46 test.

## 2. Kiểm thử tính năng mạng

| Tính năng | Có test tự động | Kiểm thử tay 2 peer |
|---|---|---|
| Discovery (UDP Multicast) | Chỉ phần phân tích gói tin (PR #13). Gửi/nhận multicast thật chưa có test | **Chưa thực hiện** |
| Connect / Reject | Có (`ControlChannelTest`) | **Chưa thực hiện** |
| Disconnect, kể cả khi peer đã tắt | Có (`ControlChannelTest`) | **Chưa thực hiện** |
| Truyền file nhiều chunk | Có (`ChunkedTransferTest`) | **Chưa thực hiện** |
| SHA-256 từng chunk và cả file | Gián tiếp: tải xong thì so khớp với file gốc | **Chưa thực hiện** |
| Pause / Resume | Chưa có test tự động. Hủy có test (`cancelledDownloadCleansUpTempFiles`) | **Chưa thực hiện** |
| Quyền truy cập (peer chưa được chấp nhận) | Có (`rejectsPeerThatWasNotAccepted` và các test control) | **Chưa thực hiện** |
| Chặn `../` | Có (`blocksPathTraversal`) | Không cần |
| Peer không phản hồi | Có một phần (ngắt kết nối với peer đã tắt) | **Chưa thực hiện** |

## 3. Kịch bản kiểm thử tay đề xuất (chưa chạy)

Chạy 2 cửa sổ app trên cùng máy (`mvn clean javafx:run` hai lần), hoặc trên 2 máy cùng LAN:

1. A bấm *Quét LAN* và thấy B. Kiểm tra tên và IP.
2. A gửi kết nối, B bấm *Từ chối*: A nhận thông báo, không xem được file. Lặp lại, lần này B bấm *Chấp nhận*.
3. A tải một file trên 10 MB, bấm *Tạm dừng*, đợi, rồi bấm *Tiếp tục*: file tải về có SHA-256 trùng với file gốc (`certutil -hashfile <file> SHA256`).
4. Trong lúc A đang tải, tắt app B: A báo lỗi và giữ file `.part`. Mở lại B rồi tải lại: tải tiếp từ chỗ dừng.
5. B chặn A ở tab *Kết nối đến*: A không tải tiếp được nữa.

Kết quả và ảnh chụp màn hình sẽ được đính kèm vào PR liên quan khi thực hiện xong.
