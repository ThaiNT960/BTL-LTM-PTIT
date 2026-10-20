package client;

import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

// Khung tọa độ dùng chung cho màn đăng nhập, đăng ký và sảnh.
final class ScreenLayout extends Pane {
    private final Group content = new Group();

    ScreenLayout() {
        this(true);
    }

    ScreenLayout(boolean background) {
        getStyleClass().add("app-screen");
        getStyleClass().add("game-theme");
        getChildren().add(content);
        setPrefSize(1100, 620);
        if (background) {
            ImageView bg = art("background.png", 0, 0, 1100, 620);
            double ch = bg.getImage().getWidth() * 620 / 1100;
            bg.setViewport(
                    new Rectangle2D(
                            0, (bg.getImage().getHeight() - ch) / 2, bg.getImage().getWidth(), ch));
        } else setStyle("-fx-background-color:rgba(16,30,20,.45);");
        widthProperty().addListener((o, a, b) -> resize());
        heightProperty().addListener((o, a, b) -> resize());
    }

    private void resize() {
        double s = Math.min(getWidth() / 1100, getHeight() / 620);
        content.getTransforms().setAll(new Scale(s, s));
        content.setLayoutX((getWidth() - 1100 * s) / 2);
        content.setLayoutY((getHeight() - 620 * s) / 2);
    }

    <T extends Node> T put(T node, double x, double y, double w, double h) {
        node.setLayoutX(x);
        node.setLayoutY(y);
        if (node instanceof Region r) {
            r.setMinSize(w, h);
            r.setPrefSize(w, h);
            r.setMaxSize(w, h);
        }
        content.getChildren().add(node);
        return node;
    }

    static ImageView icon(String file, double size) {
        ImageView v = new ImageView(GameAssets.image(file));
        Rectangle2D b = bounds(file);
        if (b != null) v.setViewport(b);
        v.setFitWidth(size);
        v.setFitHeight(size);
        v.setPreserveRatio(true);
        v.setMouseTransparent(true);
        return v;
    }

    ImageView art(String file, double x, double y, double w, double h) {
        ImageView v = icon(file, w);
        v.setFitHeight(h);
        Rectangle2D b = bounds(file);
        if (file.startsWith("modal-")) {
            double scale = w / b.getWidth(), cap = b.getHeight() * .24;
            double edge = cap * scale;
            double[] sy = {b.getMinY(), b.getMinY() + cap, b.getMaxY() - cap};
            double[] sh = {cap, b.getHeight() - 2 * cap, cap};
            double[] dy = {y, y + edge, y + h - edge}, dh = {edge, h - 2 * edge, edge};
            for (int i = 0; i < 3; i++) {
                ImageView part = new ImageView(v.getImage());
                part.setViewport(new Rectangle2D(b.getMinX(), sy[i], b.getWidth(), sh[i]));
                part.setFitWidth(w);
                part.setFitHeight(dh[i]);
                part.setMouseTransparent(true);
                put(part, x, dy[i], w, dh[i]);
            }
            return v;
        }
        double iw = b == null ? v.getImage().getWidth() : b.getWidth(),
                ih = b == null ? v.getImage().getHeight() : b.getHeight();
        double s = Math.min(w / iw, h / ih);
        return put(v, x + (w - iw * s) / 2, y + (h - ih * s) / 2, w, h);
    }

    Group pagePanel(String file, double x, double y, double w, double h) {
        Group group = new Group();
        Image source = GameAssets.image(file);
        Rectangle2D b = bounds(file);
        // Giữ nguyên vương miện và tiêu đề bảng; chỉ kéo giãn phần thân trống.
        double cap = b.getHeight() * (file.equals("lobby-ranking.png") ? .32 : .24),
                edge = cap * w / b.getWidth();
        double[] sy = {b.getMinY(), b.getMinY() + cap, b.getMaxY() - cap};
        double[] sh = {cap, b.getHeight() - 2 * cap, cap};
        double[] dy = {0, edge, h - edge}, dh = {edge, h - 2 * edge, edge};
        for (int i = 0; i < 3; i++) {
            ImageView v = new ImageView(source);
            v.setViewport(new Rectangle2D(b.getMinX(), sy[i], b.getWidth(), sh[i]));
            v.setFitWidth(w);
            v.setFitHeight(dh[i]);
            v.setLayoutY(dy[i]);
            v.setMouseTransparent(true);
            group.getChildren().add(v);
        }
        return put(group, x, y, w, h);
    }

    static void skin(Button button, String file, double w, double h) {
        String text = button.getText();
        if (!text.isEmpty()) button.setAccessibleText(text);
        button.setText("");
        ImageView v = icon(file, w);
        v.setFitHeight(h);
        button.setGraphic(v);
        if (!button.getStyleClass().contains("image-button"))
            button.getStyleClass().add("image-button");
        button.setMinSize(w, h);
        button.setPrefSize(w, h);
        button.setMaxSize(w, h);
    }

    Button imageButton(Button button, String file, double x, double y, double w, double h) {
        skin(button, file, w, h);
        return put(button, x, y, w, h);
    }

    // Chỉ kéo giãn phần giữa không có họa tiết của ảnh nằm ngang.
    void profileBar(double x, double y, double w, double h) {
        Image source = GameAssets.image("profile-bar.png");
        Rectangle2D b = bounds("profile-bar.png");
        double left = 750, right = 200, scale = h / b.getHeight();
        double[] sx = {b.getMinX(), b.getMinX() + left, b.getMaxX() - right};
        double[] sw = {left, b.getWidth() - left - right, right};
        double[] dx = {x, x + left * scale, x + w - right * scale};
        double[] dw = {left * scale, w - (left + right) * scale, right * scale};
        for (int i = 0; i < 3; i++) {
            ImageView v = new ImageView(source);
            v.setViewport(new Rectangle2D(sx[i], b.getMinY(), sw[i], b.getHeight()));
            v.setFitWidth(dw[i]);
            v.setFitHeight(h);
            v.setMouseTransparent(true);
            put(v, dx[i], y, dw[i], h);
        }
    }

    void strip(String file, double x, double y, double w, double h) {
        ImageView original = icon(file, w);
        Rectangle2D b = bounds(file);
        double cap = b.getHeight() * .45, edge = cap * h / b.getHeight();
        double[] sx = {b.getMinX(), b.getMinX() + cap, b.getMaxX() - cap};
        double[] sw = {cap, b.getWidth() - 2 * cap, cap};
        double[] dx = {x, x + edge, x + w - edge}, dw = {edge, w - 2 * edge, edge};
        for (int i = 0; i < 3; i++) {
            ImageView v = new ImageView(original.getImage());
            v.setViewport(new Rectangle2D(sx[i], b.getMinY(), sw[i], b.getHeight()));
            v.setFitWidth(dw[i]);
            v.setFitHeight(h);
            v.setMouseTransparent(true);
            put(v, dx[i], y, dw[i], h);
        }
    }

    void input(TextField field, double x, double y, double w) {
        strip("input-field.png", x, y, w, 46);
        field.getStyleClass().add("screen-input");
        put(field, x + 10, y + 5, w - 20, 36);
    }

    void password(PasswordField password, double x, double y, double w) {
        strip("input-field.png", x, y, w, 46);
        TextField visible = new TextField();
        visible.textProperty().bindBidirectional(password.textProperty());
        visible.setPromptText(password.getPromptText());
        visible.setVisible(false);
        visible.setManaged(false);
        password.getStyleClass().add("screen-input");
        visible.getStyleClass().add("screen-input");
        put(password, x + 10, y + 5, w - 57, 36);
        put(visible, x + 10, y + 5, w - 57, 36);
        SVGPath outline = new SVGPath();
        outline.setContent("M2 12 C7 5 17 5 22 12 C17 19 7 19 2 12 Z");
        outline.getStyleClass().add("eye-outline");
        Circle pupil = new Circle(12, 12, 3);
        pupil.getStyleClass().add("eye-pupil");
        Line slash = new Line(3, 21, 21, 3);
        slash.getStyleClass().add("eye-slash");
        slash.setVisible(false);
        Rectangle iconBounds = new Rectangle(0, 0, 24, 24);
        iconBounds.setOpacity(0);
        ToggleButton eye = new ToggleButton();
        eye.setGraphic(new Group(iconBounds, outline, pupil, slash));
        eye.setAccessibleText("Hiện mật khẩu");
        eye.getStyleClass().add("screen-eye");
        eye.setOnAction(
                e -> {
                    boolean show = eye.isSelected();
                    visible.setVisible(show);
                    visible.setManaged(show);
                    password.setVisible(!show);
                    password.setManaged(!show);
                    slash.setVisible(show);
                    eye.setAccessibleText(show ? "Ẩn mật khẩu" : "Hiện mật khẩu");
                });
        put(eye, x + w - 40, y + 7, 32, 32);
    }

    static Rectangle2D bounds(String file) {
        return GameAssets.bounds(file);
    }
}
