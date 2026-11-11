# Chặn Số Lạ (Android 10+)

## Build ra APK

**Cách 1 – GitHub (không cần cài gì):**
1. Tạo repo mới trên github.com, upload toàn bộ thư mục này (giữ cả thư mục `.github`).
2. Vào tab **Actions** → đợi job "Build APK" chạy xong (~3–5 phút).
3. Vào **Releases** → tải `ChanSoLa.apk`.

**Cách 2 – Android Studio:** File > Open thư mục này → Build > Build APK(s).
APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Cài đặt trên điện thoại
1. Mở file APK, cho phép "Cài ứng dụng không rõ nguồn gốc".
2. Mở app, bấm lần lượt 3 nút ở mục **Thiết lập** cho đến khi đủ ✅.
3. Nếu quyền Nhật ký cuộc gọi / Đọc thông báo bị khoá (xám):
   Cài đặt > Ứng dụng > Chặn Số Lạ > ⋮ (góc phải) > **Cho phép cài đặt bị hạn chế**, rồi cấp lại.
4. Thêm vào **Danh sách cho phép** tên ngân hàng / dịch vụ gửi OTP cho bạn.

## Cách hoạt động
- **Cuộc gọi:** Android hỏi app trước khi đổ chuông. Số không có trong danh bạ (kể cả số ẩn) → ngắt,
  không ghi lịch sử, không báo cuộc gọi nhỡ. Có thêm cơ chế xoá lịch sử dự phòng.
- **Tin nhắn:** app đọc thông báo của app SMS, người gửi lạ → gỡ thông báo, đánh dấu đã đọc,
  lưu nội dung vào Nhật ký trong app. Tin vẫn nằm trong app Tin nhắn (Android chỉ cho app SMS mặc định xoá tin).
