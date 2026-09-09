# Speed Monitor App

App theo dõi tốc độ qua GPS, tự động mở 1 app khác khi tốc độ giảm xuống dưới ngưỡng đặt trước (sau khi đã di chuyển nhanh hơn 15 km/h).

## Cách build ra file APK

1. Cài **Android Studio** (bản mới nhất): https://developer.android.com/studio
2. Mở Android Studio → **Open** → chọn thư mục `SpeedMonitorApp` này (thư mục gốc chứa `settings.gradle.kts`).
3. Đợi Android Studio tự động **Sync Gradle** (lần đầu sẽ tải Gradle + các thư viện, cần internet, có thể mất vài phút).
4. Build APK:
   - Vào menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
   - Sau khi build xong, bấm vào thông báo "locate" hoặc tìm file tại:
     `app/build/outputs/apk/debug/app-debug.apk`
5. Copy file `.apk` này vào USB, cắm vào đầu Android ô tô, dùng trình quản lý file trên đầu xe để cài (cần bật "Cài đặt từ nguồn không xác định" trong Settings của đầu xe).

## Lưu ý khi test trên đầu xe

- **Cấp quyền vị trí "Luôn cho phép" (Allow all the time)**: khi bấm "Bắt đầu" lần đầu, hệ thống sẽ hỏi quyền vị trí 2 lần (foreground rồi tới background) — nhớ chọn "Cho phép mọi lúc" ở lần hỏi thứ 2, nếu không app sẽ ngừng nhận vị trí khi tắt màn hình.
- **Cấp quyền thông báo** (Android 13): nếu từ chối, service vẫn chạy nhưng không thấy noti trạng thái.
- **Test tốc độ**: GPS trong nhà/gần tòa nhà cao tầng không ổn định, nên test thực tế ngoài trời hoặc lái thử.
- **Ngưỡng "đang di chuyển"** cố định ở 15 km/h trong code (`SpeedMonitorService.kt`, biến `movingThreshold`).
- **Sàn tốc độ tối thiểu 1km/h** (`minTriggerSpeed`): đứng yên hẳn (đèn đỏ, kẹt xe) sẽ không trigger, dù ngưỡng bạn đặt là bao nhiêu.
- **Chống mở lặp dựa vào trạng thái hiển thị**: app kiểm tra cam đích có đang hiển thị hay không (qua quyền "Usage access") trước khi mở lại — không mở chồng khi đang hiện, tự mở lại ngay khi cam vừa tắt (kể cả do chính cam tự hết giờ 15-30s, do bạn tắt tay, hay do tắt xi nhan/số lùi) miễn tốc độ vẫn còn trong khoảng kích hoạt.
- **Nút Pause/Resume nổi**: chỉ hiện khi xe đang chạy (tốc độ >1km/h), tự ẩn khi đứng yên/đỗ xe. Kéo thả tự do, vị trí được nhớ cho lần sau. Bấm 1 lần để Pause (icon đổi màu cam) khi bạn muốn tự do dùng màn hình mà không bị tự mở cam liên tục; tự động Resume (icon xanh lá) khi tốc độ vượt lại 15km/h.
- **Đỗ xe >5 phút**: chuyển hẳn sang chế độ tiết kiệm pin (GPS 30s/lần, độ chính xác thấp), tắt hoàn toàn việc trigger và ẩn nút nổi. Khi xe chạy lại, tự động quay về bình thường. Chỉnh thời gian 5 phút này trong `SpeedMonitorService.kt`, biến `parkTimeoutMs`.
- **Cần bật 2 quyền đặc biệt thủ công 1 lần** (không có popup xin quyền thông thường):
  1. **"Hiển thị trên ứng dụng khác"** — cho nút nổi.
  2. **"Usage access"** — để check cam có đang hiển thị hay không.
  
  Khi bấm "Bắt đầu" lần đầu, app sẽ tự mở lần lượt 2 màn hình Settings tương ứng — bạn tìm "Speed Monitor" trong danh sách, bật lên, quay lại app, bấm "Bắt đầu" lại cho tới khi cả 2 quyền đã được cấp.
- **Khu vực Debug** ở cuối màn hình chính: hiển thị tốc độ GPS hiện tại, trạng thái xe (đứng yên/di chuyển/đã đỗ), và 4 điều kiện trigger (đạt/chưa đạt từng điều kiện + trạng thái tổng thể) — cập nhật theo thời gian thực khi app đang mở, giúp debug dễ hơn khi lái thử thực tế. Cơ chế: Service gửi broadcast nội bộ (`sendBroadcast` + `setPackage`) mỗi lần đọc GPS, MainActivity lắng nghe khi đang ở foreground (`onStart`/`onStop`) — không ảnh hưởng gì tới hoạt động nền khi app bị đóng.
- **Đã sửa lỗi kích thước nút nổi**: bản trước bị lỗi hiển thị to bất thường (gần hết màn hình) do cách đo kích thước `WRAP_CONTENT` khi thêm view vào `WindowManager` bị sai lệch. Bản này ép kích thước cứng 56dp bằng pixel cụ thể, đảm bảo đúng kích thước trên mọi thiết bị.

## Cấu trúc project

```
app/src/main/java/com/example/speedmonitor/
├── MainActivity.kt          # Màn hình chính: chọn app, nhập ngưỡng/interval, nút Start/Stop
├── SpeedMonitorService.kt   # Foreground service theo dõi GPS, logic if-else chính
├── BootReceiver.kt          # Tự chạy lại service sau khi khởi động máy
├── AppAdapter.kt            # RecyclerView adapter hiển thị danh sách app
└── AppInfo.kt                # Data class thông tin app
```

## Cách 2: Build bằng GitHub Actions (không cần cài gì trên máy)

File `.github/workflows/build-apk.yml` đã có sẵn trong project. Chỉ cần:

1. Tạo 1 repository mới trên https://github.com (bấm nút xanh **New**, đặt tên bất kỳ, để **Public** hoặc **Private** đều được, không cần tick gì thêm).
2. Trong repo vừa tạo, bấm **Add file → Upload files**.
3. Kéo thả **toàn bộ nội dung** bên trong thư mục `SpeedMonitorApp` (không phải kéo cả thư mục `SpeedMonitorApp`, mà là các file/thư mục con bên trong nó như `app`, `gradle`, `build.gradle.kts`...) vào khung upload.
4. Bấm **Commit changes**.
5. Vào tab **Actions** ở trên cùng repo → sẽ thấy workflow "Build APK" tự chạy (mất khoảng 3-5 phút).
6. Khi chạy xong (dấu tích xanh), bấm vào lần chạy đó → kéo xuống mục **Artifacts** → tải file `SpeedMonitor-debug-apk.zip` → giải nén ra sẽ có file `app-debug.apk`.
7. Copy file .apk này vào USB, cài lên đầu xe như bình thường.

Không cần cài Android Studio, không cần dòng lệnh, hoàn toàn thao tác trên trình duyệt.
