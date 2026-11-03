# Kiểm thử Internet Mode (tự động + 2 mạng thực tế)

## 1. Kết quả kiểm thử tự động

Lệnh: `.\mvnw.cmd clean test`. Kết quả ngày 03/11/2026 trên Windows 11, JDK Temurin 21.0.12:
**102 test, 0 lỗi, 2 bỏ qua** (BUILD SUCCESS). Hai test bị bỏ qua là phần symlink: Windows không cho tạo symlink khi chưa bật Developer Mode, test tự `assume` bỏ qua. Trên Linux/macOS hai test này chạy bình thường.

Integration test dùng **relay thật** (WebSocket qua TCP, cùng mã nguồn với bản triển khai) chạy cục bộ, cùng nhiều "ứng dụng" đầy đủ tầng nghiệp vụ (ControlServer, ChunkedFileServer, ConnectionService, InternetModeService, PeerHealthMonitor). Mất mạng đột ngột được giả lập bằng một TCP proxy ngừng chuyển dữ liệu nhưng **không đóng socket** (giống tắt Wi-Fi: không có gói FIN), có giới hạn băng thông 3 MiB/s để kịp "rút mạng" giữa chừng.

> **Lưu ý:** mọi test trong mục này chạy trên 1 máy (localhost). Chúng chứng minh giao thức và logic, **không** thay cho kiểm thử 2 mạng thực tế ở mục 2.

| # | Yêu cầu | Test (lớp#phương thức) | Kết quả |
|---|---|---|---|
| 1 | Hai peer đăng ký với relay | `InternetModeIntegrationTest#twoPeersRegisterWithRelayUsingSignatureDerivedIds` | PASS |
| 2 | A gửi yêu cầu kết nối B | `InternetModeIntegrationTest#connectByInviteAcceptedThenBothSidesListAndSearchFiles` | PASS |
| 3 | B chấp nhận | như trên (B được hỏi đúng 1 lần, kết nối 2 chiều) | PASS |
| 4 | B từ chối | `#rejectedConnectionLeavesNoAccessAndNoLink` | PASS |
| 5 | Peer chưa có quyền không đọc được file | `#linkedButUnacceptedPeerCannotReadFilesNorImpersonateAcceptedPeer` (kể cả mạo danh id của peer đã được chấp nhận, và dùng id đó từ LAN) | PASS |
| 6 | Liệt kê file qua Internet transport | `#connectByInviteAcceptedThenBothSidesListAndSearchFiles` (cả 2 chiều, kèm tìm kiếm) | PASS |
| 7 | Tải file nhỏ | `#downloadsSmallAndMultiChunkFilesOverRelayAndVerifiesSha256` | PASS |
| 8 | Tải file lớn nhiều chunk | như trên (12,3 MiB = 13 chunk, 4 luồng song song) | PASS |
| 9 | Kiểm tra SHA-256 | như trên + mọi test tải đều so SHA-256 với file gốc | PASS |
| 10 | Pause / Resume | `#pauseAndResumeOverRelay` | PASS |
| 11 | Mất kết nối khi đang tải | `#suddenNetworkLossIsDetectedAndDownloadResumesFromVerifiedChunks`: phát hiện < 8 s, tác vụ `WAITING_NETWORK`, không tạo file hoàn chỉnh, giữ `.part` + `.bitmap`, phía B thấy A Offline trong khi relay của B vẫn Online | PASS |
| 12 | Tiếp tục sau khi kết nối lại | như trên: tải tiếp từ chunk đã xác minh (không về 0), SHA-256 khớp | PASS |
| 13 | Relay restart | `#relayRestartRestoresLinksAndDownloadResumes`: tự kết nối lại, khôi phục liên kết bằng link token, tải xong | PASS |
| 14 | Peer offline | `#sharerGoingOfflineIsReportedAndDownloadContinuesWhenItReturns`, `#connectingToOfflinePeerFailsWithClearMessage` | PASS |
| 15 | Mã mời sai / hết hạn | `#wrongExpiredOwnAndBruteForcedInviteCodesAreRejected` (sai định dạng, không tồn tại, hết hạn, mã của chính mình, vượt số lần thử) | PASS |
| 16 | Nhiều peer truy cập đồng thời | `#severalPeersDownloadFromSameSharerConcurrently` (3 máy tải cùng lúc từ 1 máy) | PASS |
| 17 | Chặn đường dẫn ngoài thư mục chia sẻ | `#pathTraversalAbsolutePathAndSymlinkEscapeAreBlockedOverRelay` (`../`, `..\`, đường dẫn tuyệt đối, tóm tắt), `SafePathsTest` | PASS (phần symlink: SKIPPED trên Windows) |
| 18 | Test LAN cũ vẫn pass | `TwoPeerEndToEndTest`, `ControlChannelTest`, `ChunkedTransferTest`, `ConnectionServiceTest`, `PeerDiscoveryTest`, … (67 test cũ, không sửa / xoá test nào) | PASS |
| + | WSS/TLS | `#worksOverWssWithTls`: relay chạy TLS bằng keystore sinh tạm, client kiểm tra chứng chỉ; client không tin chứng chỉ thì không kết nối | PASS |
| + | Relay ở mức giao thức | `RelayServerProtocolTest` (chữ ký sai, lệnh trước xác thực, timeout xác thực, frame không mask, chuyển tiếp nhị phân, bên thứ 3 không chen vào luồng, PRESENCE OFFLINE), `RelayRegistriesTest` (mã mời, rate limit, link token HMAC, RFC 6455 accept key) | PASS |

Ghi chú: trong lúc phát triển có 1 lần `ConnectionServiceTest#acceptingMakesBothSidesConnectedAndBothCanBrowseFiles` thất bại do race có sẵn từ trước (test kiểm tra sự kiện "connected" phía B ngay khi A nhận được trả lời). Chạy lại 5 lần riêng lẻ và lần chạy toàn bộ cuối cùng đều PASS.

### Chưa kiểm thử tự động

| Hạng mục | Trạng thái |
|---|---|
| Build và chạy image Docker / `docker compose up` | NOT TESTED (máy phát triển không chạy Docker daemon) |
| `relay.bat` (relay trong mạng nội bộ) | Đã chạy thử: tìm JDK, dùng jar đã build, sinh khoá, `/health` trả `ok`, in đúng địa chỉ LAN |
| `relay.bat -Tunnel` (Cloudflare Tunnel) | NOT TESTED (môi trường phát triển không được phép mở tunnel công khai) |
| Caddy xin chứng chỉ Let's Encrypt thật | NOT TESTED (cần tên miền + VPS) |
| Giao diện JavaFX: thao tác bấm nút tự động | NOT TESTED tự động. Đã mở app thật kết nối relay cục bộ: trạng thái *Relay: Online*, mã kết nối, đếm ngược hiển thị đúng |

## 2. Kiểm thử thực tế: 2 máy, 2 mạng khác nhau

**Trạng thái: NOT TESTED.** Môi trường phát triển không có VPS công khai đang chạy và không có thiết bị thứ hai, nên chưa thực hiện được. Không được báo PASS mục này dựa trên các test localhost ở trên. Thực hiện theo các bước dưới đây và điền bảng kết quả.

### 2.1 Chuẩn bị

| Thành phần | Yêu cầu |
|---|---|
| Relay | Triển khai theo [INTERNET_MODE.md](INTERNET_MODE.md) mục 2.1 (VPS) hoặc 2.2b (`relay.bat -Tunnel` trên máy A, không cần VPS); `curl https://<địa-chỉ>/health` trả `ok` từ **cả 2 mạng** |
| Máy A | Laptop dùng **Wi-Fi nhà**. Ghi lại IP công khai: `curl.exe ifconfig.me` |
| Máy B | Laptop kết nối **hotspot 4G/5G của điện thoại**, **không** cùng router với A. Ghi lại IP công khai (thường là IP của nhà mạng, có thể dùng CGNAT) |
| Xác nhận khác mạng | `ipconfig` ở 2 máy cho 2 dải IP riêng khác nhau; IP công khai khác nhau; *Quét LAN* ở 2 máy **không** thấy nhau |
| File thử | Máy B: thư mục chia sẻ có 1 file ≥ 200 MB (vd. video) để kịp ngắt mạng giữa chừng. Ghi SHA-256: `Get-FileHash -Algorithm SHA256 <file>` |

### 2.2 Các bước

| Bước | Thao tác | Kết quả mong đợi |
|---|---|---|
| 1 | Cả 2 máy: *🌐 Kết nối mạng* → *🌍 Internet* → nhập `wss://<tên-miền>/ws` → *Kết nối relay* | 2 máy hiện *🟢 Relay: Online*. Log relay: 2 dòng `Peer xxxxxxxx online` với 2 IP khác nhau |
| 2 | Máy B đọc *Mã của bạn* cho A; A nhập → *🔗 Kết nối* | Máy B hiện hộp thoại "…(qua Internet) muốn kết nối" |
| 3 | Máy B bấm *Từ chối* | Máy A báo đã từ chối, không xem được file |
| 4 | Lặp lại bước 2 bằng mã mới, máy B bấm *Chấp nhận* | 2 máy có tab *Kết nối: …*; bảng Internet hiện *🟢 Online* |
| 5 | Máy A mở tab *Kết nối: B*, xem danh sách; tab *Tìm kiếm* tìm theo tên | Thấy đúng file của B |
| 6 | Máy A tải file ≥ 200 MB | Có phần trăm, tốc độ, thời gian còn lại |
| 7 | Khi đạt ~30%: **tắt Wi-Fi của máy A** | Trong ~30 s: A hiện *Relay: Reconnecting*, trạng thái tải "Mất kết nối, đang chờ kết nối lại…". Máy B: relay vẫn *Online*, máy A chuyển *🔴 Offline* (≤ 45 s + 15 s) |
| 8 | Chờ 1–2 phút rồi **bật lại Wi-Fi** máy A | A tự về *Relay: Online* (backoff ≤ 60 s), tải tự tiếp tục từ ~30% (không về 0%) |
| 9 | Tải xong, so SHA-256 file ở A với giá trị ghi ở bước chuẩn bị | Trùng khớp |
| 10 | (Tuỳ chọn) Tạm dừng / Tiếp tục khi đang tải; ngắt kết nối từ máy B | Tạm dừng thì tiến độ đứng yên; ngắt thì cả 2 máy cùng ngắt, tải dở báo bị từ chối |
| 11 | (Tuỳ chọn) Đổi vai: B tải file của A | Thành công (kết nối 2 chiều) |

### 2.3 Bảng ghi kết quả

| Mục | Giá trị / Kết quả | Ghi chú |
|---|---|---|
| Ngày, người thực hiện | | |
| Relay URL, nhà cung cấp VPS, vị trí | | |
| IP công khai máy A / mạng | | Wi-Fi nhà |
| IP công khai máy B / mạng | | 4G/5G (nhà mạng) |
| Bước 1–5 | NOT TESTED | |
| Tốc độ tải trung bình (MB/s) | | |
| Thời gian phát hiện mất mạng ở A / ở B (giây) | | |
| Tải tiếp từ % | | |
| SHA-256 khớp | | |

Chụp màn hình: bảng Internet ở 2 máy, hộp thoại chấp nhận, trạng thái *Reconnecting*, kết quả `Get-FileHash`, log relay (`docker compose logs relay`). Log relay chỉ ghi id rút gọn, không có mã kết nối hay nội dung file, nên đưa vào báo cáo được.
