# NumberGuessingClient1

Game Đoán Số đối kháng online viết bằng JavaFX, TCP Socket và MySQL.

## 1. Cấu hình MySQL

Chạy toàn bộ file `number_guessing.sql` trong MySQL Workbench. File sẽ xóa
database `number_guessing` cũ và tạo lại database mới.

Mở file `server.properties` và sửa tài khoản MySQL cho đúng với máy:

```properties
db.url=jdbc:mysql://localhost:3306/number_guessing
db.user=root
db.password=MAT_KHAU_MYSQL_CUA_BAN
```

Tài khoản game có sẵn để kiểm thử:

| Tài khoản | Mật khẩu | Quyền |
|---|---|---|
| `user1` | `123456` | Người chơi |
| `user2` | `123456` | Người chơi |
| `admin` | `123456` | Quản trị viên |

## 2. Cấu hình JDK và JavaFX

Các đường dẫn nằm trong `nbproject/project.properties`:

```properties
jdk.home=C:/Program Files/Apache NetBeans/jdk
javafx.home=${user.home}/Downloads/openjfx-25.0.1_windows-x64_bin-sdk/javafx-sdk-25.0.1
```

Project đang dùng JDK 24 và OpenJFX 25.0.1. Nếu đặt JDK hoặc JavaFX ở thư
mục khác, sửa hai dòng trên cho đúng đường dẫn trên máy.

MySQL Connector đã có tại `lib/mysql-connector-j-9.0.0.jar`.

## 3. Chạy chương trình

1. Run File `server.Server`.
2. Run File `client.ClientApp` hai lần.
3. Đăng nhập bằng `user1 / 123456` và `user2 / 123456` để thi đấu.
4. Chạy `client.AdminConsole` và đăng nhập `admin / 123456` để quản trị.

Server sử dụng cổng TCP `12345`. Khi chạy nhiều máy, sửa `server.host` trong
`nbproject/project.properties` thành địa chỉ IP của máy chạy server.
