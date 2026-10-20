package client;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

// Dựng màn đấu hai người từ các ảnh giao diện và co giãn theo kích thước cửa sổ.
final class BattleScreen extends Pane {
    private static final double WIDTH = 1100;
    private static final double HEIGHT = 620;
    private final Group canvas = new Group();
    private final Group overlay = new Group();
    private final ImageView playerCard;
    private final ImageView opponentCard;
    private final Label clock;
    private final Label status;
    private ImageView turnArt;
    private Animation animation;

    BattleScreen(
            String username,
            String opponent,
            int range,
            Label status,
            Label clock,
            TableView<?> history,
            TextField guess,
            Button guessButton,
            Label hint,
            TextArea chat,
            TextField message,
            Button send) {
        getStyleClass().add("battle-screen");
        getStyleClass().add("game-theme");
        this.status = status;
        this.clock = clock;

        // Dùng chung hệ tọa độ cho ảnh, điều khiển và vùng bấm.
        ImageView background = image("background.png", 0, 0, WIDTH, HEIGHT);
        Image bg = background.getImage();
        double cropHeight = bg.getWidth() * HEIGHT / WIDTH;
        background.setViewport(
                new Rectangle2D(0, (bg.getHeight() - cropHeight) / 2, bg.getWidth(), cropHeight));
        {
            playerCard = image("player-self.png", 116, 24, 300, 112);
            opponentCard = image("player-opponent-mirrored.png", 684, 24, 300, 112);
            turnArt = image("clock-turn-self.png", 425, 0, 250, 164);
            label(username, "battle-card-name", 226, 81, 166, 29)
                    .setTooltip(new javafx.scene.control.Tooltip(username));
            label(opponent, "battle-card-name", 708, 81, 166, 29)
                    .setTooltip(new javafx.scene.control.Tooltip(opponent));
            style(clock, "battle-clock", 515, 37, 70, 51);
            style(status, "battle-turn", 456, 112, 188, 28);
            status.setVisible(false);
            panel("history-panel.png", 70, 164, 665, 326, 0, 0);
            // Ảnh khung chat đã có viền ô nhập và vị trí đặt nút gửi.
            ImageView chatArt = image("chat-panel.png", 745, 164, 285, 409);
            chatArt.setPreserveRatio(false);
            style(history, "battle-history", 106, 232, 593, 230);
            history.setFixedCellSize(28);
            double[] widths = {75, 145, 100};
            for (int i = 0; i < widths.length; i++) {
                var col = history.getColumns().get(i);
                col.setMinWidth(widths[i]);
                col.setMaxWidth(widths[i]);
                col.setPrefWidth(widths[i]);
            }
            style(chat, "battle-chat-area", 771, 225, 233, 272);
            chat.setWrapText(true);
            style(message, "battle-message", 770, 520, 193, 30);
            // Căn giữa biểu tượng gửi sau khi khung chat được co giãn theo hai chiều.
            double sendCenterX = 745 + (875 - 51) * 285.0 / 946;
            double sendCenterY = 164 + (1336 - 36) * 409.0 / 1433;
            button(send, "send-button.png", sendCenterX - 16.5, sendCenterY - 16.5, 33, 33);
            // Kéo giãn phần gỗ trống theo chiều ngang; ô nhập nằm trên ảnh này.
            ImageView inputArt = image("number-console.png", 70, 495, 665, 78);
            inputArt.setPreserveRatio(false);
            ImageView rangeArt = image("range-" + range + ".png", 287.5, 482, 230, 36);
            rangeArt.setPreserveRatio(false);
            rangeArt.setAccessibleText("Phạm vi: 1–" + range);
            style(guess, "battle-guess", 170, 518, 325, 30);
            guess.setPromptText("Nhập số...");
            button(guessButton, "guess-button.png", 510, 513, 175, 45);
            style(hint, "battle-hint", 106, 580, 593, 26);
        }

        canvas.getChildren().add(overlay);
        getChildren().add(canvas);
        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());
        setPrefSize(WIDTH, HEIGHT);
        active(true);
    }

    void soundButton(Button sound) {
        // Dùng chung vùng cắt vuông để hai biểu tượng âm thanh có cùng kích thước hiển thị.
        place(sound, 1032, 24, 48, 48);
    }

    private static Image asset(String file) {
        return GameAssets.image(file);
    }

    private static Rectangle2D viewport(String file) {
        return GameAssets.bounds(file);
    }

    private ImageView image(String file, double x, double y, double w, double h) {
        ImageView view = new ImageView(asset(file));
        Rectangle2D crop = viewport(file);
        if (crop != null) view.setViewport(crop);
        view.setFitWidth(w);
        view.setFitHeight(h);
        view.setPreserveRatio(true);
        view.setMouseTransparent(true);
        place(view, x, y, w, h);
        return view;
    }

    // Giữ đúng tỷ lệ phần trang trí trên/dưới, chỉ kéo giãn phần trống ở giữa.
    private void panel(
            String file,
            double x,
            double y,
            double w,
            double h,
            double topPixels,
            double bottomPixels) {
        Image source = asset(file);
        Rectangle2D crop = viewport(file);
        double scale = w / crop.getWidth();
        {
            topPixels = crop.getHeight() * .20;
            bottomPixels = crop.getHeight() * .15;
        }
        double top = topPixels * scale, bottom = bottomPixels * scale;
        double[] srcY = {crop.getMinY(), crop.getMinY() + topPixels, crop.getMaxY() - bottomPixels};
        double[] srcH = {topPixels, crop.getHeight() - topPixels - bottomPixels, bottomPixels};
        double[] dstY = {y, y + top, y + h - bottom};
        double[] dstH = {top, h - top - bottom, bottom};
        for (int i = 0; i < 3; i++) {
            ImageView strip = new ImageView(source);
            strip.setViewport(new Rectangle2D(crop.getMinX(), srcY[i], crop.getWidth(), srcH[i]));
            strip.setFitWidth(w);
            strip.setFitHeight(dstH[i]);
            strip.setMouseTransparent(true);
            place(strip, x, dstY[i], w, dstH[i]);
        }
    }

    private void place(Node node, double x, double y, double w, double h) {
        node.setLayoutX(x);
        node.setLayoutY(y);
        if (node instanceof javafx.scene.layout.Region region) {
            region.setMinSize(w, h);
            region.setPrefSize(w, h);
            region.setMaxSize(w, h);
        }
        canvas.getChildren().add(node);
    }

    private static Label text(String value, String css, double x, double y, double w, double h) {
        Label label = new Label(value);
        label.getStyleClass().add(css);
        label.setAlignment(Pos.CENTER);
        label.setMinSize(w, h);
        label.setPrefSize(w, h);
        label.setMaxSize(w, h);
        label.setLayoutX(x);
        label.setLayoutY(y);
        return label;
    }

    private Label label(String value, String css, double x, double y, double w, double h) {
        Label label = text(value, css, x, y, w, h);
        canvas.getChildren().add(label);
        return label;
    }

    private void style(
            javafx.scene.layout.Region node, String css, double x, double y, double w, double h) {
        node.getStyleClass().add(css);
        if (node instanceof Label label) label.setAlignment(Pos.CENTER);
        place(node, x, y, w, h);
    }

    private void button(Button node, String image, double x, double y, double w, double h) {
        if (image.equals("guess-button.png")) {
            node.setAccessibleText(node.getText());
            node.setText("");
        }
        ImageView art = image(image, x, y, w, h);
        style(node, "battle-image-button", x, y, w, h);
        art.opacityProperty()
                .bind(javafx.beans.binding.Bindings.when(node.disabledProperty()).then(0.5).otherwise(1.0));
        node.hoverProperty()
                .addListener(
                        (o, oldValue, hovered) -> {
                            art.setScaleX(hovered ? 1.025 : 1);
                            art.setScaleY(hovered ? 1.025 : 1);
                        });
    }

    private void resizeCanvas() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        double scale = Math.min(getWidth() / WIDTH, getHeight() / HEIGHT);
        canvas.getTransforms().setAll(new Scale(scale, scale, 0, 0));
        canvas.setLayoutX((getWidth() - WIDTH * scale) / 2);
        canvas.setLayoutY((getHeight() - HEIGHT * scale) / 2);
    }

    void active(boolean yours) {
        if (turnArt != null) {
            turnArt.setImage(asset(yours ? "clock-turn-self.png" : "clock-turn-opponent.png"));
            turnArt.setViewport(viewport("clock-turn-self.png"));
            turnArt.setAccessibleText(yours ? "Đến lượt bạn" : "Đến lượt đối thủ");
            status.setVisible(false);
        }
        DropShadow glow = new DropShadow(8, Color.rgb(255, 219, 115, 0.82));
        playerCard.setEffect(yours ? glow : null);
        opponentCard.setEffect(yours ? null : glow);
    }

    void remaining(int seconds) {
        clock.setText(seconds > 0 ? String.valueOf(seconds) : "0");
        clock.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("warning"), seconds > 0 && seconds <= 5);
    }

    void submit(int number, boolean yours, Runnable delivered) {
        stop();
        PauseTransition pause = new PauseTransition(Duration.millis(180));
        pause.setOnFinished(
                e -> {
                    animation = null;
                    delivered.run();
                });
        animation = pause;
        pause.play();
    }

    void showResult(boolean won, String message, Runnable again, Runnable lobby) {
        overlay.getChildren().clear();
        overlay.getChildren().add(new Rectangle(WIDTH, HEIGHT, Color.rgb(9, 26, 23, 0.78)));
        String file = won ? "result-victory.png" : "result-defeat.png";
        ImageView emblem = new ImageView(asset(file));
        Rectangle2D crop = viewport(file);
        emblem.setViewport(crop);
        double scale = Math.min(340 / crop.getWidth(), 230 / crop.getHeight());
        emblem.setFitWidth(crop.getWidth() * scale);
        emblem.setFitHeight(crop.getHeight() * scale);
        emblem.setPreserveRatio(true);
        emblem.setLayoutX((WIDTH - crop.getWidth() * scale) / 2);
        emblem.setLayoutY(115 + (230 - crop.getHeight() * scale) / 2);
        emblem.setAccessibleText(won ? "Chiến thắng" : "Thua cuộc");
        overlay.getChildren().add(emblem);
        overlay.getChildren().add(modalLabel(message, "battle-result-message", 280, 355, 540, 52));
        modalButton("CHƠI LẠI", 352, 430, again);
        modalButton("VỀ SẢNH", 568, 430, lobby);
    }

    private Label modalLabel(String value, String css, double x, double y, double w, double h) {
        Label label = text(value, css, x, y, w, h);
        label.setWrapText(true);
        label.setTextAlignment(TextAlignment.CENTER);
        return label;
    }

    private void modalButton(String title, double x, double y, Runnable action) {
        String file = (title.equals("CHƠI LẠI") ? "replay-button.png" : "return-lobby-button.png");
        double w = 180, h = 54;
        ImageView art = new ImageView(asset(file));
        art.setViewport(viewport(file));
        art.setFitWidth(w);
        art.setFitHeight(h);
        art.setLayoutX(x);
        art.setLayoutY(y);
        overlay.getChildren().add(art);
        Button button = new Button("");
        button.setAccessibleText(title);
        button.setId(title.equals("CHƠI LẠI") ? "result-replay" : "result-lobby");
        button.getStyleClass().add("battle-image-button");
        button.setLayoutX(x);
        button.setLayoutY(y);
        button.setMinSize(w, h);
        button.setPrefSize(w, h);
        button.setMaxSize(w, h);
        button.setOnAction(e -> action.run());
        overlay.getChildren().add(button);
    }

    void stop() {
        if (animation != null) {
            animation.stop();
            animation = null;
        }
    }
}
