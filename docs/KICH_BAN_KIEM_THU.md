# Kịch bản kiểm thử P2PFileShare (1 máy, 2 cửa sổ)

Người thực hiện: Nguyễn Đức Anh Tài (2280602799). Ngày: ..../10/2026.
Môi trường: Windows 11, JDK 21. Chạy `mvn javafx:run` hai lần (2 cửa sổ PowerShell) để có 2 peer.

> Lưu ý: plugin JavaFX lỗi `InvalidPathException` khi đường dẫn thư mục dự án có dấu tiếng Việt. Hãy clone repo vào đường dẫn không dấu, ví dụ `D:\code\P2P`.

## Chuẩn bị

Tự tạo các thư mục mẫu sau (tên file chỉ là ví dụ; file lớn khoảng 1 GB để kịp bấm Tạm dừng khi chạy trên cùng 1 máy):

| Thư mục | Vai trò |
|---|---|
| `D:\code\demo\A-chia-se` | Máy A chia sẻ: `Bai giang mang may tinh.pdf`, `Video bai giang socket (1GB).bin`, thư mục con `Lap trinh mang\Chuong 1 - Socket TCP.txt` |
| `D:\code\demo\B-chia-se` | Máy B chia sẻ: `De cuong Co so du lieu.txt` |
| `D:\code\demo\B-tai-ve` | Máy B lưu file tải về |

Đặt 2 cửa sổ cạnh nhau: trái = **Máy A**, phải = **Máy B**. Lần đầu nếu Windows Firewall hỏi, chọn cho phép mạng **Private**.

## Các ca kiểm thử

Cột "Thực tế" để trống, điền Đạt / Không đạt khi chạy.

| Mã | Chức năng | Các bước | Kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| TC01 | Đặt tên | Mỗi cửa sổ nhập tên "Máy A" / "Máy B" → **Bắt đầu tham gia** | Tên hiển thị trên giao diện chính | |
| TC02 | Chia sẻ thư mục | A: tab **📂 Tài liệu chia sẻ** → **Chọn...** → `A-chia-se`. B: chọn `B-chia-se` | Bảng của A có 3 file, kể cả file trong thư mục con; có cột môn học / loại | |
| TC03 | Khám phá peer | B: tab **🌐 Mạng LAN** → **🔄 Quét LAN** | Thấy "Máy A" kèm IP và cổng; không thấy chính mình → **chụp Hình 3.7** | |
| TC04 | Từ chối kết nối | B chọn Máy A → **🔗 Kết nối...**; A hiện hộp thoại → **chụp Hình 3.8** → bấm **Từ chối** | B báo "Peer từ chối hoặc không phản hồi"; không mở tab Kết nối | |
| TC05 | Chấp nhận kết nối | Lặp TC04, A bấm **Chấp nhận** | B báo "Kết nối thành công!", mở tab "Kết nối: Máy A" với danh sách file; A: tab **📡 Kết nối đến** có Máy B kèm thời điểm | |
| TC06 | Tải file + SHA-256 | B: tab Kết nối: Máy A → chọn `Bai giang mang may tinh.pdf` → **⬇ Tải xuống** → chọn `B-tai-ve` | Tải xong 100%. Chạy `certutil -hashfile <file> SHA256` cho file gốc và file tải về → hai mã giống hệt | |
| TC07 | Tạm dừng / Tiếp tục | Tải `Video bai giang socket (1GB).bin` → khi đang chạy bấm **⏸ Tạm dừng** → **chụp Hình 3.9** → chờ 5 giây → **▶ Tiếp tục** | Khi tạm dừng: trạng thái "Đã tạm dừng", % đứng yên. Tiếp tục: chạy tiếp từ % cũ đến 100%, hiển thị tốc độ và thời gian còn lại | |
| TC08 | Không ghi đè | Tải lại `Bai giang mang may tinh.pdf` vào `B-tai-ve` | File mới tên `Bai giang mang may tinh (1).pdf`, file cũ giữ nguyên | |
| TC09 | Hủy tải | Xóa file 1GB trong `B-tai-ve`, tải lại → giữa chừng bấm **⏹ Hủy bỏ** | Trạng thái "Đã hủy tải"; trong `B-tai-ve` không còn `.part`, `.bitmap` | |
| TC10 | Tải tiếp khi mất kết nối | Tải file 1GB → khoảng 30–50% thì đóng cửa sổ A (**⏻ Thoát**) | B báo lỗi tải; `B-tai-ve` còn `.part` và `.bitmap`. Mở lại A (`mvn javafx:run`), chia sẻ lại `A-chia-se`; B quét, kết nối lại, tải lại cùng file vào `B-tai-ve` → tiến độ bắt đầu từ % cũ, không từ 0 | |
| TC11 | Tìm kiếm | B: tab **🔍 Tìm kiếm** → từ khóa `socket` → Tìm kiếm | Có kết quả từ Máy A (file .txt và .bin), cột chủ sở hữu = Máy A; thử **Lọc môn** / **Lọc Peer** | |
| TC12 | Tóm tắt tài liệu | B: tab Kết nối: Máy A → chuột phải `Chuong 1 - Socket TCP.txt` → Tóm tắt | Hộp thoại "Kết quả Tóm tắt AI" hiện các câu chính, tiếng Việt đúng dấu | |
| TC13 | Tự đồng bộ danh sách | Dùng Explorer chép 1 file bất kỳ vào `A-chia-se` | Sau vài giây, tab Kết nối: Máy A ở B tự hiện file mới (không cần bấm Reload) | |
| TC14 | Đổi tên | A: **Đổi tên hiển thị** → "Máy A mới" → Lưu thay đổi | B nhận tên mới (tiêu đề tab / danh sách kết nối) | |
| TC15 | Lịch sử tải | B: tab **🕒 Lịch sử tải** → **chụp Hình 3.10**; nhấp đúp 1 dòng; chuột phải → mở thư mục chứa | Có đủ các file đã tải; nhấp đúp mở file; xóa file trong Explorer thì cột trạng thái báo đã xóa | |
| TC16 | Chặn peer | A: tab **📡 Kết nối đến** → chọn Máy B → Ngắt kết nối | B nhận thông báo bị ngắt; B bấm Reload / tải file → bị từ chối | |
| TC17 | Kiểm thử tự động | `cd D:\code\P2P` → `mvn clean test` | `Failures: 0, Errors: 0` – BUILD SUCCESS (37 test trên `main`; 41 trên nhánh PR #17; 50 khi gộp tất cả PR) | |

## Ghi chú khi viết báo cáo

- Chỉ ghi "Đạt" cho ca đã thực sự chạy. Ca nào lỗi thì ghi lại hiện tượng, đó cũng là kết quả kiểm thử.
- Tất cả ca trên chạy 2 peer trên **cùng 1 máy**; ghi rõ điều này trong báo cáo (mục 3.6).
- Ảnh chụp: Hình 3.7 (TC03), Hình 3.8 (TC04), Hình 3.9 (TC07), Hình 3.10 (TC15).
