# Hướng dẫn đóng góp (Nhóm 12)

Tài liệu này cụ thể hóa quy trình Git/GitHub của học phần cho repo P2PFileShare. Mỗi thành viên tự làm phần việc của mình trên máy và tài khoản GitHub của chính mình.

## 1. Chuẩn bị (làm 1 lần)

```bash
git config --global user.name "MSSV_HoVaTen"        # ví dụ: 2380602094_TrinhDanhThi
git config --global user.email "<email đã thêm vào tài khoản GitHub>"

git clone https://github.com/Terence-0310/P2P.git
cd P2P
mvn test                                            # hoặc .\mvnw.cmd test trên Windows
```

Yêu cầu JDK 17 trở lên. Nên đặt thư mục code ở đường dẫn không dấu (ví dụ `D:\code\P2P`).

Email commit phải được thêm vào tài khoản GitHub (Settings → Emails), nếu không GitHub sẽ không tính commit cho bạn.

## 2. Issue trước, code sau

- Mỗi đầu việc (tính năng, sửa lỗi, test, tài liệu) là 1 Issue, tạo **trước** khi viết code.
- Gán (Assign) cho **đúng 1 người** chịu trách nhiệm chính.
- Gắn Label: `bug`, `enhancement`, `frontend`, `backend`, `test`, `refactor`, `documentation`.
- Gắn Milestone (`Tuần 1`, `Tuần 2`, ...).
- Mô tả gồm: vấn đề, file/module liên quan, Acceptance Criteria, cách kiểm thử.

## 3. Nhánh

```bash
git checkout main && git pull
git checkout -b feature/<MSSV>-issue-<số>-<ten-ngan>
# ví dụ: feature/2280602799-issue-10-discovery-validation
```

Nhánh `main` được bảo vệ: không push trực tiếp, chỉ merge qua Pull Request.

## 4. Commit

Theo Conventional Commits, chỉ dùng: `feat`, `fix`, `refactor`, `docs`.

```
feat: cho phép cấu hình số kết nối tải song song
fix: lỗi crash khi mất kết nối mạng
```

- Mỗi commit là một thay đổi logic, build và test vẫn chạy được.
- Commit ngay khi xong từng phần, không dồn đến sát hạn nộp.
- Không commit: `target/`, `bin/`, `.idea/`, `.vscode/`, `*.jar`, `*.zip`, `.env`, file dữ liệu cá nhân (`download_history.json`, thư mục tải xuống).
- Trước khi commit, chạy `git status` và `git diff --staged` để kiểm tra.

## 5. Pull Request

1. `git push -u origin feature/...` rồi mở PR vào `main`.
2. Điền đủ mẫu PR: issue liên quan (`Closes #số`), đã làm gì, ảnh hưởng đến module khác, bằng chứng kiểm thử (ảnh chụp `mvn test` thành công; phần giao diện thì chụp ảnh app).
3. Nhờ ít nhất **1 thành viên khác** review: đọc code, để lại nhận xét, bấm **Approve**. Không tự Approve PR của mình.
4. Merge sau khi đã được Approve, rồi cập nhật cột Pull Request của mình trong bảng đóng góp ở `README.md` (cũng qua một PR).

## 6. Vấn đáp

Mỗi người phải giải thích được luồng logic của code do chính tài khoản mình commit. Hãy đọc kỹ phần mình sửa và các hàm nó gọi tới.
