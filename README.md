# Gia Đình SK ❤️

Định vị điện thoại theo yêu cầu: app chạy ngầm trên Android, **chỉ bật GPS khi website cần xem**.

| Thư mục | Nội dung |
|---|---|
| `android/` | App Android **Gia Đình SK** (Kotlin): giữ kết nối tới server, nhận yêu cầu thì lấy GPS và gửi lên |
| `server/` | Server Node.js + **website bản đồ** |

## Cách hoạt động

```
Website ──"định vị"──▶ Server ──"locate"──▶ App (chạy ngầm)
   ▲                                          │ bật GPS, lấy vị trí mới
   └──────────── vị trí ◀── Server ◀──────────┘
```

- App giữ một kết nối WebSocket nhẹ tới server. Khi không ai xem, GPS tắt nên gần như không tốn pin.
- Mở website hoặc bấm **📍 Định vị ngay**: điện thoại lấy GPS và gửi lên trong khoảng 3–5 giây, kể cả khi đang tắt màn hình hoặc ngủ sâu (Doze).
- Khi bật **Tự cập nhật 20s**, website hỏi vị trí 20 giây một lần, chỉ trong lúc tab đang mở.
- Nếu điện thoại offline, website hiện vị trí cuối cùng kèm thời gian.

## 1. Chạy server + website

```bash
cd server
npm install
npm start
```

Mở http://localhost:3000.

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | `3000` | Cổng server |
| `DATA_FILE` | `server/data.json` | Nơi lưu vị trí |

> ⚠️ Website không có mật khẩu: ai biết địa chỉ server đều xem được vị trí. Khi đưa lên Internet, đừng chia sẻ địa chỉ này.

### Cho điện thoại kết nối tới server

- **Điện thoại ảo (emulator):** `http://10.0.2.2:3000`
- **Điện thoại thật, cùng Wi-Fi:** IP LAN của máy tính, ví dụ `http://192.168.1.9:3000`. Nếu Windows Firewall hỏi thì cho phép Node.js truy cập mạng.
- **Điện thoại ở ngoài, dùng 4G:** cần đưa server lên Internet, ví dụ Render / Railway / VPS, hoặc chạy tạm `ngrok http 3000`. Hạ tầng phải hỗ trợ WebSocket; các dịch vụ trên đều hỗ trợ.

## 2. App Android

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Build lại:

```bash
cd android
./gradlew assembleDebug      # hoặc: gradlew.bat assembleDebug
```

### Cài lần đầu trên điện thoại

1. Cài APK (cho phép "Cài ứng dụng không rõ nguồn gốc").
2. Nhập **tên điện thoại** và **địa chỉ server**, rồi bấm **Bật định vị ngầm**.
3. Cấp quyền vị trí và chọn **"Luôn cho phép"**.
4. Cho phép **tắt tối ưu pin**. Bước này rất quan trọng trên Xiaomi, Oppo, Vivo, Samsung. Trên Xiaomi nên bật thêm "Tự khởi động" cho app.

Sau đó không cần mở app nữa. App tự chạy lại sau khi khởi động máy và tự kết nối lại khi mất mạng hoặc server khởi động lại.

Android bắt buộc app chạy ngầm phải có một thông báo. App đặt thông báo ở mức thấp nhất, nên nó chỉ nằm thu gọn trong ngăn thông báo và không hiện icon trên thanh trạng thái.

### Test bằng điện thoại ảo

Trong Android Emulator, bấm **⋯** (Extended controls), vào **Location**, chọn điểm trên bản đồ rồi bấm **Set location**. Sau đó bấm **Định vị ngay** trên website.

## API

- WebSocket `/ws/device?id=&name=&device=`: kết nối của app; server gửi `{type:"locate"}`, app trả `{type:"location", lat, lng, accuracy, speed, battery, time}`
- WebSocket `/ws/viewer`: kết nối của website; gửi `{type:"locate", id?}` để yêu cầu định vị
- `GET /api/devices`: danh sách điện thoại, vị trí cuối, trạng thái online
- `GET /api/history/:id?since=<ms>`: lịch sử vị trí (mặc định 24h)
- `DELETE /api/devices/:id`: xoá một điện thoại
