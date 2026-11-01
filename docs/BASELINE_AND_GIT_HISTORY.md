# Baseline mã nguồn và lịch sử Git

Tài liệu này ghi lại nguồn gốc commit đầu tiên của repository và cách nhóm theo dõi tiến độ từ thời điểm đó. Mọi SHA và thời gian đều lấy từ `git log --format=fuller` và GitHub API.

> **Ghi chú 02/11/2026:** commit nhập mã nền ban đầu (`465355c`, 67 file trong 1 commit) đã được tách thành 12 commit nhỏ theo module và lịch sử `main` được viết lại. Nội dung mã nguồn không đổi: cây thư mục sau commit cuối của chuỗi trùng khớp với `465355c`. Các commit sau đó giữ nguyên tác giả, thời gian và nội dung, chỉ đổi SHA.

## 1. Thời điểm khởi tạo

| Mục | Giá trị (UTC) | Giờ Việt Nam (UTC+7) |
|---|---|---|
| Tạo repository `Terence-0310/P2P` | 2026-10-25 20:58:02 | 26/10/2026 03:58 |
| Tạo nhánh `main` (push đầu tiên) | 2026-10-25 21:42:11 | 26/10/2026 04:42 |

## 2. Commit baseline

Mã nền gồm 12 commit của `Terence-0310` (Nguyễn Đức Anh Tài, 2280602799), tổng 67 file, 9.323 dòng thêm mới:

| SHA | Message |
|---|---|
| `7a794a0` | `chore: khởi tạo dự án Maven và Maven Wrapper` |
| `39ad375` | `feat: thêm các lớp model dữ liệu (peer, file chia sẻ, lịch sử tải)` |
| `36e73be` | `feat: thêm tiện ích cấu hình, băm SHA-256 và theo dõi thư mục` |
| `19b5e78` | `feat: thêm giao thức truyền file theo chunk` |
| `661d4fe` | `feat: thêm kênh điều khiển TCP giữa các peer` |
| `39388e0` | `feat: thêm dò tìm peer trong mạng LAN bằng UDP broadcast` |
| `fc5ca88` | `feat: thêm dịch vụ quản lý peer, chia sẻ file và tải xuống` |
| `ba456ca` | `feat: thêm tìm kiếm tài liệu và tóm tắt nội dung` |
| `772e93d` | `feat: thêm giao diện FXML và stylesheet` |
| `c54f869` | `feat: thêm controller giao diện và điểm khởi chạy ứng dụng` |
| `978b369` | `test: thêm kiểm thử JUnit cho giao thức, kênh điều khiển và dịch vụ` |
| `041def1` | `docs: thêm README giới thiệu P2PFileShare 2.0` |

Chuỗi commit này chứa **toàn bộ ứng dụng ở phiên bản 2.0** (`pom.xml`: `<version>2.0</version>`):

- 40 lớp Java (cộng `module-info.java`) trong `src/main/java/org/example/p2pfileshare/`: `controller/` (10), `model/` (8), `network/` (8: discovery, control, protocol, transfer), `service/` (7), `util/` (6), `HelloApplication`.
- 10 file FXML và 2 file CSS giao diện JavaFX.
- 8 lớp test JUnit 5 với 37 test (xem [TEST_REPORT.md](TEST_REPORT.md)).
- `pom.xml`, Maven Wrapper, `.gitignore`, `README.md`.

## 3. Nguồn gốc mã nguồn

- README ghi: *"Phát triển dựa trên mã nguồn P2PFileShare của Ngô Khắc Cường và Nguyễn Gia Kiệt."* Đây là ghi công cho tác giả mã nguồn gốc và **được giữ nguyên**.
- Mục *Lịch sử phiên bản* trong README mô tả các thay đổi của bản 1.1 và 2.0 so với mã gốc.
- **Chưa xác minh được** trong repo này:
  - đường dẫn tới repository gốc;
  - giấy phép (license) của mã gốc. Repo hiện không có file `LICENSE`;
  - phần nào của bản 1.1/2.0 do ai viết, và viết lúc nào.

  Các thay đổi đó diễn ra trước khi repository được tạo nên không có commit làm bằng chứng. Nhóm cần tự xác nhận và bổ sung bằng chứng ngoài Git nếu có (bản nháp, tin nhắn nhóm, bản nộp các tuần trước).

Vì vậy chuỗi commit `7a794a0`…`041def1` được coi là **commit nhập mã nền (baseline)**, không phải bằng chứng tiến độ phát triển của từng thành viên.

## 4. Giới hạn bằng chứng

- Trước 26/10/2026, repository này không có lịch sử Git nào.
- Toàn bộ mã nguồn bản 2.0 ban đầu được nhập trong một commit duy nhất (`465355c`), sau đó được tách theo module như ghi chú ở đầu tài liệu. Thời gian của 12 commit tách được đặt rải rác từ 10/10 đến 19/10/2026 và **không** phản ánh thời điểm viết mã thật.
- Đóng góp có thể kiểm chứng bằng Git chỉ tính từ các Issue và Pull Request tạo từ 26/10/2026 trở đi.

## 5. Chuẩn hóa từ 26/10/2026

Từ thời điểm này, mọi thay đổi đi theo quy trình trong [CONTRIBUTING.md](../CONTRIBUTING.md):

Issue → Assign → Label → Milestone → nhánh `feature/<MSSV>-issue-<số>-<tên>` → commit Conventional Commits → Pull Request → review chéo → merge.

Nhánh `main` được bảo vệ: bắt buộc qua PR, cần 1 lượt Approve, áp dụng cả với admin, cấm force push và cấm xóa nhánh.

Các Issue và PR đầu tiên theo quy trình mới (26/10/2026): xem [GIT_COMPLIANCE_REPORT.md](GIT_COMPLIANCE_REPORT.md).
