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
| 7 | Pull Request | PR #6 mô tả ngắn, chưa có bằng chứng kiểm thử | Mẫu PR `.github/pull_request_template.md`. PR #8, #12, #13, #14, #15, #17, #19 có đủ: issue liên quan, đã làm gì, ảnh hưởng, bằng chứng test | Mô tả từng PR | Chưa có ảnh chụp màn hình chạy app trong PR | Thấp |
| 8 | Review chéo | Bảo vệ `main`: bắt buộc PR, 1 Approve, áp dụng cả admin, cấm force push. Chưa PR nào được review | PR #6, #8, #12, #13, #14, #15, #17, #19 được `GiaLong2803` Approve rồi mới merge (26/10/2026) | Tab Reviews của từng PR | Chưa có CI bắt buộc test PASS | Thấp |
| 9 | Bảng đóng góp README | Bảng 5 thành viên, cột "Merge Requests" toàn `…` | Dùng bảng phân công của PR #6 (khớp file Word phiếu quy trình). Điền PR đã merge thật; thành viên chưa có PR ghi "Chưa có" | README trên `main` | 4 thành viên chưa có PR | Trung bình |
| 10 | Big Bang Commit | `main` có 1 commit 9.323 dòng, 67 file | Ghi rõ đây là commit nhập mã nền, **không** viết lại lịch sử ([BASELINE_AND_GIT_HISTORY.md](BASELINE_AND_GIT_HISTORY.md)). Công việc mới chia thành nhiều issue/PR nhỏ | SHA `465355c` | Dấu hiệu Big Bang vẫn còn trong lịch sử và không thể xóa một cách trung thực | **Cao** |
| 11 | Mức độ xác thực đóng góp | Không thể xác định ai viết phần nào của bản 2.0 | Từ 26/10, mỗi thay đổi gắn với 1 issue, 1 người, 1 PR, có review | Issue/PR #2–#20 | 4 thành viên còn lại chưa có commit nào | **Cao** |
| 12 | Kết quả kiểm thử | README ghi 37 test. `mvnw` không chạy được | Sửa wrapper (PR #12). Thêm 13 test (PR #13, #14, #17). Trên `main` sau khi merge: 50/50 PASS bằng `.\mvnw.cmd clean test` | [TEST_REPORT.md](TEST_REPORT.md) | Chưa kiểm thử trên 2 máy LAN thật; kịch bản tay ([KICH_BAN_KIEM_THU.md](KICH_BAN_KIEM_THU.md)) chưa ghi kết quả | Thấp |

## 2. Issue và Pull Request của thành viên 2280602799 (26/10/2026)

| Issue | Nội dung | Nhánh | PR | Trạng thái |
|---|---|---|---|---|
| #7 | Mẫu PR, CONTRIBUTING.md, quy trình Git trong README, `.gitignore` | `feature/2280602799-issue-7-pr-template` | #8 | Đã merge |
| #9 | Sửa Maven Wrapper (only-script) | `feature/2280602799-issue-9-fix-maven-wrapper` | #12 | Đã merge |
| #10 | Discovery: kiểm tra gói phản hồi, responder không dừng khi gửi lỗi | `feature/2280602799-issue-10-discovery-validation` | #13 | Đã merge |
| #2 | Cấu hình số kết nối tải song song | `feature/2280602799-issue-2-parallel-connections` | #14 | Đã merge |
| #11 | Tài liệu baseline, kiến trúc, kiểm thử, báo cáo tuân thủ | `feature/2280602799-issue-11-docs-baseline-architecture` | #15 | Đã merge |
| #16 | Kiểm thử end-to-end 2 peer trên 1 máy | `feature/2280602799-issue-16-two-peer-e2e-tests` | #17 | Đã merge |
| #18 | Kịch bản kiểm thử thủ công 2 peer trên 1 máy | `feature/2280602799-issue-18-manual-test-script` | #19 | Đã merge |
| #20 | Cập nhật bảng đóng góp README và báo cáo sau khi merge | `feature/2280602799-issue-20-update-contribution-table` | PR của thay đổi này | Chờ review |

Thành viên chịu trách nhiệm phải đọc hiểu và tự giải thích được từng thay đổi khi vấn đáp (mục 7 của quy trình).

Toàn bộ công việc trên được làm trong cùng một ngày. Để tránh lặp lại dấu hiệu dồn cục, các việc tiếp theo nên được rải đều đến hạn nộp.

## 3. Phân công thành viên

README trên `main` ban đầu và PR #6 có hai bảng phân công khác nhau. PR #6 (khớp với file Word phiếu quy trình của nhóm) đã được review và merge, nên README hiện dùng bảng phân công của PR #6.

PR #6 dùng nhánh `docs/cap-nhat-phan-cong`, không đúng mẫu `feature/<MSSV>-issue-<số>-<tên>` và không liên kết issue. Đây là sai sót đã xảy ra, được ghi nhận lại tại đây, không sửa lịch sử.

## 4. Việc tiếp theo

1. Trịnh Danh Thi, Trần Anh Trung, Lê Tấn Tài, Trần Hồng Đức tự làm issue #1, #3, #4, #5 từ tài khoản của mình (hướng dẫn trong [CONTRIBUTING.md](../CONTRIBUTING.md)); gán Assignee khi các bạn đã có quyền vào repo.
2. Chạy kịch bản [KICH_BAN_KIEM_THU.md](KICH_BAN_KIEM_THU.md), ghi kết quả thực tế và ảnh chụp màn hình.
3. Thêm GitHub Actions chạy `./mvnw test` và đặt làm status check bắt buộc cho `main`.
4. Cân nhắc dùng email sinh viên cho Git (thêm email đó vào tài khoản GitHub trước).
