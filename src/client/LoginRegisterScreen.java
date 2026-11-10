package client;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;

// Biểu mẫu đăng nhập và đăng ký dùng cùng bố cục.
final class LoginRegisterScreen {
    final Label error;
    Button submit;

    LoginRegisterScreen(ClientApp client, ScreenLayout root, boolean registering, String notice, String accountName, Button soundButton) {
        root.art("background.png", 0, 0, 1100, 620);
        root.put(soundButton, 1032, 16, 48, 48);
        root.art("dau-so-logo.png", 390, 8, 320, 116);
        root.art("auth-panel.png", 340, 136, 420, 460);
        root.art(registering ? "auth-title-register.png" : "auth-title-login.png", 425, 140, 250, 63);
        TextField name = new TextField(registering ? "" : accountName);
        name.setPromptText("Tên tài khoản");
        name.setId("auth-name");
        PasswordField pass = new PasswordField();
        pass.setPromptText("Mật khẩu");
        pass.setId("auth-password");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Nhập lại mật khẩu");
        confirm.setId("auth-confirm");
        double first = registering ? 206 : 228, second = registering ? 286 : 316;
        root.put(client.label("Tên tài khoản", "screen-label"), 390, first, 320, 22);
        root.input(name, 385, first + 25, 330);
        root.put(client.label("Mật khẩu", "screen-label"), 390, second, 320, 22);
        root.password(pass, 385, second + 25, 330);
        if (registering) {
            root.put(client.label("Xác nhận mật khẩu", "screen-label"), 390, 366, 320, 22);
            root.password(confirm, 385, 391, 330);
        }
        error = client.label(notice, "screen-error");
        error.setWrapText(true);
        root.put(error, 390, registering ? 439 : 405, 320, 34);
        submit = client.button(registering ? "ĐĂNG KÝ" : "ĐĂNG NHẬP", () -> {
            if (name.getText().isBlank() || pass.getText().isBlank()) {
                error.setText("Vui lòng nhập tên tài khoản và mật khẩu.");
                return;
            }
            if (registering && !pass.getText().equals(confirm.getText())) {
                error.setText("Mật khẩu xác nhận chưa khớp.");
                return;
            }
            error.setText("");
            submit.setDisable(true);
            client.sendAuth(registering, name.getText().trim(), pass.getText());
        });
        submit.setId("auth-submit");
        submit.setDefaultButton(true);
        root.imageButton(submit, registering ? "submit-register.png" : "submit-login.png", 440, registering ? 468 : 456, 220, 56);
        Hyperlink link = new Hyperlink(registering ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký");
        link.setId("auth-switch");
        link.setAlignment(Pos.CENTER);
        link.setOnAction(e -> client.auth(!registering, ""));
        root.put(link, 395, registering ? 525 : 515, 310, 28);
        root.imageButton(client.button("Đóng", client::dismissModal), "close-button.png", 676, 195, 32, 32);
        Platform.runLater(name::requestFocus);
    }
}
