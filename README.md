# Automotive Car Dashboard (Android Application)

Dự án này là một ứng dụng giao diện bảng điều khiển (Dashboard) dành cho ô tô chạy trên nền tảng Android (Android Automotive OS - AAOS hoặc thiết bị Android thông thường kết nối với mô phỏng/kit phần cứng). 

Ứng dụng giao tiếp với một **C++ Daemon** có tên đăng ký là `navis.can.CanService` thông qua cơ chế **Binder IPC (AIDL)** để đọc và ghi dữ liệu CAN bus (cổng `can0` với bitrate mặc định là `500k`).

---

## 📌 Tính năng chính

- **Hiển thị Tốc độ xe (Vehicle Speed):** Đọc từ CAN ID `0x101`, giải mã dữ liệu ADC 12-bit (0 - 4096) sang tốc độ hiển thị từ 0 - 300 mph.
- **Trạng thái Pin (Battery Level):** Hiển thị phần trăm dung lượng pin (%) từ CAN ID `0x4D4` lên thanh ProgressBar và Text.
- **Điều khiển Âm lượng (Volume Control):** Lắng nghe sự kiện tăng/giảm âm lượng từ CAN ID `0x2B2` và điều chỉnh trực tiếp âm lượng hệ thống của thiết bị Android.
- **Điều khiển Đèn & Xi-nhan (Light & Blink Control):**
  - Gửi lệnh bật/tắt đèn xe và chế độ nhấp nháy đèn hazard qua CAN ID `0x200`.
  - Hiệu ứng nhấp nháy Neon mượt mà (Breathing Effect) cho các đèn xi-nhan trái/phải trên UI.
  - Tự động duy trì luồng gửi gói tin nhấp nháy định kỳ mỗi 3 giây khi bật Hazard.

---

## 🏗️ Kiến trúc & Cơ chế hoạt động

```
+----------------------------------------+
|          Android Dashboard App         |  <-- Dự án Kotlin này
+----------------------------------------+
                    |
      (Binder IPC / AIDL: ICan.aidl)
                    v
+----------------------------------------+
|    C++ Daemon (navis.can.CanService)    |  <-- Chạy ngầm trong hệ thống
+----------------------------------------+
                    |
            (SocketCAN / Can0)
                    v
+----------------------------------------+
|        CAN Bus / Kit SK144 Hardware    |
+----------------------------------------+
```

### Chi tiết Bản đồ CAN ID (CAN ID Mapping)

| CAN ID (Hex) | Tên chức năng | Chi tiết dữ liệu (Payload) | Hướng truyền |
| :--- | :--- | :--- | :--- |
| **`0x101`** | `CAN_ID_VEHICLE_SPEED` | Byte 0 & 1: Sensor_Data (12-bit ADC, Little Endian)<br>Byte 2 & 3: Threshold value (LE) | Nhận (Rx) |
| **`0x4D4`** | `CAN_ID_BATTERY_LEVEL` | Byte 0: Phần trăm Pin (0 - 100%) | Nhận (Rx) |
| **`0x2B2`** | `CAN_ID_VOLUME` | Byte 0: `0x01` (Tăng âm lượng), `0x02` (Giảm âm lượng) | Nhận (Rx) |
| **`0x200`** | `CAN_ID_LIGHT_STATUS` | Byte 0: Trạng thái đèn (`1` = Bật, `0` = Tắt)<br>Byte 1: Trạng thái nhấp nháy (`1` = Bật, `0` = Tắt)<br>Byte 2 & 3: Giá trị mặc định (`0xFF 0xFF`) | Gửi (Tx) |

---

## 🛠️ Hướng dẫn Biên dịch và Chạy ứng dụng

### 1. Yêu cầu hệ thống (Prerequisites)
- **Hệ điều hành máy tính:** Windows / macOS / Linux.
- **JDK:** Java 11 trở lên.
- **Android SDK:** Hỗ trợ API Level tối thiểu 24 (minSdk 24), mục tiêu API Level 36 (targetSdk 36).
- **Phần mềm:** Android Studio (phiên bản Koala trở lên được khuyến nghị).
- **Yêu cầu runtime:** Để ứng dụng hoạt động đầy đủ chức năng, thiết bị Android hoặc máy ảo của bạn phải được tích hợp sẵn dịch vụ C++ `navis.can.CanService` đăng ký với ServiceManager của Android. Nếu dịch vụ này không tồn tại, ứng dụng sẽ liên tục thử kết nối lại và hiển thị trạng thái tìm kiếm Daemon trên UI.

### 2. Biên dịch bằng Android Studio
1. Mở Android Studio.
2. Chọn **Open** và tìm đến thư mục chứa dự án này (`AutomotiveCarDashBoard`).
3. Đợi Android Studio đồng bộ hóa Gradle (Gradle Sync) hoàn tất.
4. Kết nối thiết bị Android hoặc khởi động Máy ảo (Emulator).
5. Nhấn nút **Run** (biểu tượng Play màu xanh 🟢) trên thanh công cụ hoặc phím tắt `Shift + F10` để build và cài đặt ứng dụng trực tiếp lên thiết bị.

### 3. Biên dịch nhanh bằng Dòng lệnh (CLI)
Mở terminal tại thư mục gốc của dự án và chạy lệnh sau:

*   **Trên Windows (PowerShell/CMD):**
    ```powershell
    .\gradlew.bat assembleDebug
    ```
*   **Trên macOS/Linux:**
    ```bash
    chmod +x gradlew
    ./gradlew assembleDebug
    ```

File APK sau khi build thành công sẽ nằm ở đường dẫn:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🔄 Hướng dẫn Kiểm tra và Giao tiếp thử nghiệm

1. **Khởi chạy App:** Khi mở ứng dụng lần đầu, app sẽ tự động tìm kiếm Binder Service `"navis.can.CanService"`.
2. **Trạng thái Kết nối:**
   - Nếu kết nối thành công, nút kết nối chuyển thành **Đã Kết Nối** (đồng thời tự động mở cổng `can0` với bitrate 500k).
   - Nếu Daemon bị crash hoặc tắt, ứng dụng có cơ chế tự động kết nối lại (Auto-recovery) sau mỗi 3 giây.
3. **Mô phỏng Nhận dữ liệu CAN:**
   - Để kiểm tra hiển thị tốc độ, hãy gửi frame CAN với ID `0x101` kèm payload dạng ADC.
   - Để kiểm tra pin, hãy gửi frame CAN với ID `0x4D4` kèm 1 byte giá trị pin (ví dụ `0x32` đại diện cho 50%).
4. **Mô phỏng Gửi dữ liệu CAN:**
   - Nhấn **BẬT ĐÈN XE** hoặc **BẬT NHẤP NHÁY** để gửi frame CAN ID `0x200` xuống Daemon. Bạn có thể sử dụng các công cụ giám sát CAN (như `candump can0`) trên thiết bị/máy ảo để xác nhận dữ liệu đã được gửi đi chính xác.
