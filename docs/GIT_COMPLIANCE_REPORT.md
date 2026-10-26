# Báo cáo tuân thủ quy trình Git/GitHub

Đối chiếu với tài liệu *Quy trình và ràng buộc thực hiện dự án trên Git/GitHub* của học phần.
Thời điểm đánh giá: 26/10/2026. Phạm vi xử lý: chỉ thành viên **Nguyễn Đức Anh Tài (2280602799, `Terence-0310`)**. Phần của các thành viên khác chỉ được ghi nhận hiện trạng.

Báo cáo này là tự đánh giá của nhóm, **không** khẳng định giảng viên đã chấp nhận hay nhóm sẽ đạt điểm nào.

## 1. Ma trận tuân thủ

| # | Tiêu chí | Hiện trạng ban đầu | Đã xử lý | Bằng chứng | Còn thiếu | Rủi ro |
|---|---|---|---|---|---|---|
| 1 | Git identity | `user.name = Terence-0310`, email noreply của GitHub | `user.name = 2280602799_NguyenDucAnhTai` (cấu hình riêng trong repo) | Commit `693da20` trở đi | Email vẫn là địa chỉ noreply của GitHub, chưa phải email sinh viên. 4 thành viên còn lại chưa có commit | Trung bình |
| 2 | Issue và Assignee | #1–#5 có ghi người phụ trách trong mô tả, chưa gán Assignee | Gán #2 → `Terence-0310`, #5 → `tranhongduc2k4`. Tạo #7, #9, #10, #11, mỗi issue gán đúng 1 người | Trang Issues | #1, #3, #4 chưa gán: Trịnh Danh Thi và Trần Anh Trung chưa có quyền vào repo, lời mời cho `letantai281005` và `GiaLong2803` chưa được chấp nhận | Trung bình |
| 3 | Label và Milestone | Có `bug`, `enhancement`, `frontend`, `backend`, milestone *Tuần 1* | Thêm label `test`, `refactor`, milestone *Tuần 2*. Mọi issue và PR mới đều có label và milestone | Trang Labels, Milestones | PR #6 không có label, milestone hay issue liên kết | Thấp |
| 4 | Quy tắc đặt tên nhánh | `docs/cap-nhat-phan-cong` sai mẫu | Các nhánh mới đều theo `feature/2280602799-issue-<số>-<tên>` | `git branch -a` | PR #6 vẫn dùng nhánh sai mẫu (xem mục 3) | Thấp |
| 5 | Conventional Commits | 2 commit đúng định dạng | Mọi commit mới dùng `feat`, `fix`, `refactor`, `docs` | `git log --all --oneline` | Không | Thấp |
| 6 | `.gitignore` | Đã chuẩn cho Maven/IDE, không có file rác bị track | Thêm `*.jar`, `*.zip`, `bin/`, `node_modules/`, `.env*`, `*.pem`, `*.key` | `git ls-files -i -c --exclude-standard` rỗng. PR #8 | Không | Thấp |
| 7 | Pull Request | PR #6 mô tả ngắn, chưa có bằng chứng kiểm thử | Mẫu PR `.github/pull_request_template.md`. PR #8, #12, #13, #14 và PR tài liệu có đủ: issue liên quan, đã làm gì, ảnh hưởng, bằng chứng test | Mô tả từng PR | Chưa có ảnh chụp màn hình chạy app (chưa kiểm thử tay) | Trung bình |
| 8 | Review chéo | Bảo vệ `main`: bắt buộc PR, 1 Approve, áp dụng cả admin, cấm force push. Chưa PR nào được review | Không thay đổi (đã đúng). GitHub không cho tác giả tự Approve PR của mình | API `branches/main/protection` | **Tất cả PR đang mở đều chưa có review.** Chưa có CI bắt buộc test PASS | **Cao** |
| 9 | Bảng đóng góp README | Bảng 5 thành viên, cột "Merge Requests" toàn `…`. PR #6 đề xuất phân công khác | Chưa sửa. Chỉ điền PR sau khi đã merge | README trên `main` | Nhóm phải chốt danh sách phân công chính thức (mục 3) | Trung bình |
| 10 | Big Bang Commit | `main` có 1 commit 9.323 dòng, 67 file | Ghi rõ đây là commit nhập mã nền, **không** viết lại lịch sử ([BASELINE_AND_GIT_HISTORY.md](BASELINE_AND_GIT_HISTORY.md)). Công việc mới chia thành nhiều issue/PR nhỏ | SHA `465355c` | Dấu hiệu Big Bang vẫn còn trong lịch sử và không thể xóa một cách trung thực | **Cao** |
| 11 | Mức độ xác thực đóng góp | Không thể xác định ai viết phần nào của bản 2.0 | Từ 26/10, mỗi thay đổi gắn với 1 issue, 1 người, 1 PR | Issue/PR #7–#14 | 4 thành viên còn lại chưa có commit nào | **Cao** |
| 12 | Kết quả kiểm thử | README ghi 37 test. `mvnw` không chạy được | 37/37 PASS trên `main` (`mvn`). Sửa wrapper (PR #12). Thêm 13 test (PR #13, #14, #17). Bản gộp tất cả PR: 50/50 PASS bằng `mvnw` | [TEST_REPORT.md](TEST_REPORT.md) | Chưa kiểm thử trên 2 máy LAN thật | Thấp |

## 2. Issue và Pull Request của thành viên 2280602799 (26/10/2026)

| Issue | Nội dung | Nhánh | PR | Trạng thái |
|---|---|---|---|---|
| #7 | Mẫu PR, CONTRIBUTING.md, quy trình Git trong README, `.gitignore` | `feature/2280602799-issue-7-pr-template` | #8 | Chờ review |
| #9 | Sửa Maven Wrapper (only-script) | `feature/2280602799-issue-9-fix-maven-wrapper` | #12 | Chờ review |
| #10 | Discovery: kiểm tra gói phản hồi, responder không dừng khi gửi lỗi | `feature/2280602799-issue-10-discovery-validation` | #13 | Chờ review |
| #2 | Cấu hình số kết nối tải song song | `feature/2280602799-issue-2-parallel-connections` | #14 | Chờ review |
| #11 | Tài liệu baseline, kiến trúc, kiểm thử, báo cáo tuân thủ | `feature/2280602799-issue-11-docs-baseline-architecture` | #15 | Chờ review |
| #16 | Kiểm thử end-to-end 2 peer trên 1 máy | `feature/2280602799-issue-16-two-peer-e2e-tests` | #17 | Chờ review |

Thành viên chịu trách nhiệm phải đọc hiểu và tự giải thích được từng thay đổi khi vấn đáp (mục 7 của quy trình).

Toàn bộ công việc trên được làm trong cùng một ngày. Để tránh lặp lại dấu hiệu dồn cục, các việc tiếp theo nên được rải đều đến hạn nộp.

## 3. Vấn đề cần nhóm quyết định

**Phân công thành viên khác nhau giữa các nguồn:**

| Thành viên | README trên `main` | PR #6 và file Word phiếu quy trình |
|---|---|---|
| Nguyễn Đức Anh Tài | UDP Multicast; quản lý repo, tổng hợp báo cáo | Kiến trúc 3 kênh; UDP Multicast; truyền file theo chunk; tích hợp |
| Trần Hồng Đức | Truyền file theo chunk, SHA-256, tải song song, tạm dừng/tiếp tục | Tìm kiếm trên nhiều peer, đồng bộ danh sách file |
| Trần Anh Trung | JavaFX, lịch sử tải xuống, đồng bộ danh sách file | JavaFX, lịch sử tải xuống |
| Lê Tấn Tài | Tìm kiếm, phân loại, tóm tắt; JUnit | Phân loại, tóm tắt; JUnit |

PR #6 được giữ nguyên ở trạng thái chờ cho đến khi cả nhóm xác nhận bản chính thức.

PR #6 dùng nhánh `docs/cap-nhat-phan-cong`, sai mẫu đặt tên. Cách chuyển an toàn, không phá lịch sử: tạo issue cho việc cập nhật phân công, tạo nhánh `feature/2280602799-issue-<số>-cap-nhat-phan-cong` từ cùng commit `ac65e2b`, mở PR mới tham chiếu issue, rồi đóng PR #6 kèm ghi chú dẫn sang PR mới. Không force push.

## 4. Việc tiếp theo

1. Một thành viên khác review và Approve các PR #8, #12, #13, #14 và PR tài liệu, rồi mới merge.
2. Mời Trịnh Danh Thi và Trần Anh Trung vào repo. Nhắc `letantai281005` và `GiaLong2803` chấp nhận lời mời. Gán #1, #3, #4.
3. Mỗi thành viên tự làm issue của mình từ tài khoản của mình (hướng dẫn trong [CONTRIBUTING.md](../CONTRIBUTING.md)).
4. Thêm GitHub Actions chạy `./mvnw test` và đặt làm status check bắt buộc cho `main`.
5. Kiểm thử tay 2 peer theo [TEST_REPORT.md](TEST_REPORT.md) mục 3, đính kèm ảnh chụp vào PR.
6. Chốt phân công (mục 3). Sau khi các PR merge, điền số PR thật vào bảng đóng góp.
7. Cân nhắc dùng email sinh viên cho Git (thêm email đó vào tài khoản GitHub trước).
