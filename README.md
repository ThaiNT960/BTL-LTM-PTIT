# Game Đấu Số

Game đoán số đối kháng online viết bằng JavaFX, TCP Socket và MySQL.

## 1. Cấu hình MySQL

Chạy toàn bộ file `number_guessing.sql` trong MySQL Workbench.

Mở file `config/server.properties` và điền tài khoản MySQL trên máy chạy server:

```properties
db.url=jdbc:mysql://localhost:3306/number_guessing
db.user=root
db.password=MAT_KHAU_MYSQL_CUA_BAN
server.port=12345
```

Tài khoản game có sẵn để kiểm thử:

| Tài khoản | Mật khẩu | Quyền |
|---|---|---|
| `user1` | `123456` | Người chơi |
| `user2` | `123456` | Người chơi |

## 2. Cấu hình JDK và JavaFX

Project dùng JDK 24 trở lên và JavaFX 25.0.1 cho Windows. Các thư viện JavaFX và MySQL Connector đã có trong `lib/`; `nbproject/project.properties` tham chiếu tới các file này. Cần có Apache Ant hoặc NetBeans kèm Ant để biên dịch.

File `config/client.properties` mặc định là:

```properties
server.host=127.0.0.1
server.port=12345
```

Nếu chạy client trên máy khác, thay `server.host` bằng IP của máy chạy server. Trước khi chạy, thay `db.password` bằng mật khẩu MySQL của máy bạn.
## 3. Chạy chương trình

Mở project bằng NetBeans, chọn cấu hình **Server** rồi chạy. Giữ server hoạt động, mở hai phiên client bằng cấu hình **Client** (hoặc Run File `client.ClientApp` hai lần). Đăng nhập bằng `user1 / 123456` và `user2 / 123456` để thi đấu. Server sử dụng cổng TCP `12345` theo `config/server.properties`.
## 4. Hình ảnh giao diện
### Màn hình chào mừng
<p align="center">
  <img width="700" alt="Màn hình chào mừng" src="https://github.com/user-attachments/assets/0b564465-8866-400d-8c96-2af3451dc11a" />
</p>

### Đăng nhập và đăng ký
<p align="center">
  <img width="700" alt="Giao diện đăng nhập và đăng ký" src="https://github.com/user-attachments/assets/642268f2-675e-4dc3-b041-2b3f7e1610e8"/>
</p>

### Sảnh chờ
<p align="center">
  <img width="700" alt="Giao diện sảnh chờ" src="https://github.com/user-attachments/assets/114c2280-c243-4f65-88a2-deb414895603" />
</p>

### Phòng đấu
<p align="center">
  <img width="700" alt="Giao diện phòng đấu" src="https://github.com/user-attachments/assets/4ee2552d-4c87-4ea5-95c6-754f41e348eb" />
</p>

### Kết quả trận đấu
<p align="center">
  <img width="700" alt="Kết quả trận đấu" src="https://github.com/user-attachments/assets/15e85005-93c6-4e88-aad9-a233ea935019" />
</p>

## Nguồn tài nguyên

Nhạc nền: [TAD](https://opengameart.org/content/once-upon-a-time-loop). Hiệu ứng `click`, `key`, `countdown`: [Kenney](https://opengameart.org/content/51-ui-sound-effects-buttons-switches-and-clicks); `guess`: [Brandon Morris](https://opengameart.org/content/completion-sound); `win`, `lose`: [MintoDog](https://opengameart.org/content/cozy-puzzle-jingle-result). Toàn bộ hình ảnh và các hiệu ứng âm thanh còn lại được tạo riêng cho project.

![Visitors](https://komarev.com/ghpvc/?username=ThaiNT9&repo=BTL-LTM-PTIT&color=blue&style=flat-square)
