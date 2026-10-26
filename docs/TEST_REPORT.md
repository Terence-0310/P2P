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
| #17 | `feature/2280602799-issue-16-two-peer-e2e-tests` | `mvn -B clean test`, và chạy riêng `-Dtest=TwoPeerEndToEndTest` 4 lần | 41 PASS (37 cũ và 4 test mới); 4/4 PASS cả 4 lần chạy riêng |

### Bản gộp tất cả PR (nhánh tạm chỉ có trên máy, không push)

Gộp `main` với PR #8, #12, #13, #14, #15, #17 (không có xung đột), rồi chạy `.\mvnw.cmd -B clean test surefire-report:report-only` (Maven Wrapper đã sửa, Maven 3.9.9):

| Lớp test | Số test | Kết quả |
|---|---|---|
| `TwoPeerEndToEndTest` | 4 | PASS |
| `network.control.ControlChannelTest` | 10 | PASS |
| `network.discovery.PeerDiscoveryTest` | 7 | PASS |
| `network.protocol.FileTransferProtocolTest` | 3 | PASS |
| `network.transfer.ChunkedTransferTest` | 7 | PASS |
| `service.DocumentSummaryServiceTest` | 4 | PASS |
| `service.FileShareServiceTest` | 5 | PASS |
| `service.SearchServiceTest` | 3 | PASS |
| `service.SimpleAIServiceTest` | 2 | PASS |
| `util.UtilTest` | 5 | PASS |
| **Tổng** | **50** | **50 PASS, 0 Failures, 0 Errors, 0 Skipped**, BUILD SUCCESS |

Báo cáo HTML: `target/reports/surefire.html` (thư mục `target/` không commit).

### Trên `main` sau khi merge (26/10/2026)

Sau khi PR #8, #12, #13, #14, #15, #17, #19 được review và merge, chạy lại `.\mvnw.cmd -B clean test` trên `main`: `Tests run: 50, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Kết quả khớp với bản gộp ở trên.

## 2. Kiểm thử tính năng mạng

| Tính năng | Có test tự động | Kiểm thử tay 2 peer |
|---|---|---|
| Discovery (UDP Multicast) | Có: phân tích gói tin (PR #13); responder và quét multicast thật trên 1 máy, qua 3 card mạng (`TwoPeerEndToEndTest`, PR #17) | **Chưa thực hiện** |
| Connect / Reject | Có (`ControlChannelTest`, `TwoPeerEndToEndTest`) | **Chưa thực hiện** |
| Disconnect, kể cả khi peer đã tắt | Có (`ControlChannelTest`) | **Chưa thực hiện** |
| Truyền file nhiều chunk | Có (`ChunkedTransferTest`, `TwoPeerEndToEndTest`) | **Chưa thực hiện** |
| SHA-256 từng chunk và cả file | Có: so `FileHashUtil.sha256` của file tải về với file gốc (`TwoPeerEndToEndTest`) | **Chưa thực hiện** |
| Pause / Resume | Có: tiến độ đứng yên khi tạm dừng, tiếp tục thì xong đúng hash (`TwoPeerEndToEndTest`). Hủy: `cancelledDownloadCleansUpTempFiles` | **Chưa thực hiện** |
| Quyền truy cập (peer chưa được chấp nhận, bị chặn) | Có (`rejectsPeerThatWasNotAccepted`, các test control, `TwoPeerEndToEndTest`) | **Chưa thực hiện** |
| Chặn `../` | Có (`blocksPathTraversal`) | Không cần |
| Tải tiếp sau khi bị gián đoạn | Có: bị chặn giữa chừng thì giữ `.part`/`.bitmap`, kết nối lại tải tiếp (`TwoPeerEndToEndTest`) |
| Peer không phản hồi | Có một phần (ngắt kết nối với peer đã tắt). Chưa giả lập được "peer tắt máy giữa lúc tải" trong cùng tiến trình | **Chưa thực hiện** |

## 3. Kịch bản kiểm thử tay đề xuất (chưa chạy)

Chạy 2 cửa sổ app trên cùng máy (`mvn clean javafx:run` hai lần), hoặc trên 2 máy cùng LAN:

1. A bấm *Quét LAN* và thấy B. Kiểm tra tên và IP.
2. A gửi kết nối, B bấm *Từ chối*: A nhận thông báo, không xem được file. Lặp lại, lần này B bấm *Chấp nhận*.
3. A tải một file trên 10 MB, bấm *Tạm dừng*, đợi, rồi bấm *Tiếp tục*: file tải về có SHA-256 trùng với file gốc (`certutil -hashfile <file> SHA256`).
4. Trong lúc A đang tải, tắt app B: A báo lỗi và giữ file `.part`. Mở lại B rồi tải lại: tải tiếp từ chỗ dừng.
5. B chặn A ở tab *Kết nối đến*: A không tải tiếp được nữa.

Kết quả và ảnh chụp màn hình sẽ được đính kèm vào PR liên quan khi thực hiện xong.
