# Baseline mã nguồn và lịch sử Git

Tài liệu này ghi lại nguồn gốc commit đầu tiên của repository và cách nhóm theo dõi tiến độ từ thời điểm đó. Mọi SHA và thời gian đều lấy từ `git log --format=fuller` và GitHub API. **Lịch sử commit không bị sửa ngày hay viết lại.**

## 1. Thời điểm khởi tạo

| Mục | Giá trị (UTC) | Giờ Việt Nam (UTC+7) |
|---|---|---|
| Tạo repository `Terence-0310/P2P` | 2026-10-25 20:58:02 | 26/10/2026 03:58 |
| Tạo nhánh `main` (push đầu tiên) | 2026-10-25 21:42:11 | 26/10/2026 04:42 |

## 2. Commit baseline

| Mục | Giá trị |
|---|---|
| SHA | `465355c371e59027fa37fd8cf7793105b2824a10` |
| Message | `feat: ứng dụng chia sẻ tài liệu P2P trong mạng LAN (P2PFileShare 2.0)` |
| Author / Committer | `Terence-0310` (Nguyễn Đức Anh Tài, 2280602799) |
| AuthorDate | 2026-10-26 04:42:10 +0700 |
| CommitDate | 2026-10-26 04:58:22 +0700 |
| Quy mô | 67 file, 9.323 dòng thêm mới |

Commit này chứa **toàn bộ ứng dụng ở phiên bản 2.0** (`pom.xml`: `<version>2.0</version>`):

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

Vì vậy commit `465355c` được coi là **commit nhập mã nền (baseline)**, không phải bằng chứng tiến độ phát triển của từng thành viên.

## 4. Giới hạn bằng chứng

- Trước 26/10/2026, repository này không có lịch sử Git nào.
- Toàn bộ mã nguồn bản 2.0 xuất hiện trong một commit duy nhất. Theo mục 7 của quy trình môn học, đây là dấu hiệu "Big Bang Commit". Nhóm **không** dựng lại hay giả lập các commit trong quá khứ để che dấu hiệu này.
- Đóng góp có thể kiểm chứng bằng Git chỉ tính từ các Issue và Pull Request tạo từ 26/10/2026 trở đi.

## 5. Chuẩn hóa từ 26/10/2026

Từ thời điểm này, mọi thay đổi đi theo quy trình trong [CONTRIBUTING.md](../CONTRIBUTING.md):

Issue → Assign → Label → Milestone → nhánh `feature/<MSSV>-issue-<số>-<tên>` → commit Conventional Commits → Pull Request → review chéo → merge.

Nhánh `main` được bảo vệ: bắt buộc qua PR, cần 1 lượt Approve, áp dụng cả với admin, cấm force push và cấm xóa nhánh.

Các Issue và PR đầu tiên theo quy trình mới (26/10/2026): xem [GIT_COMPLIANCE_REPORT.md](GIT_COMPLIANCE_REPORT.md).
