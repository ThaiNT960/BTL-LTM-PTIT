package client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import common.Message;

import java.util.*;

// Gửi thao tác tới server và hiển thị phản hồi trên luồng JavaFX.
public class ClientApp extends Application {
    private Stage window;
    private String username = "";
    private String accountName = "";
    private String opponent = "";
    private String opponentAccount = "";
    private int range = 100;
    private int seconds;
    private long matchId, turnId, clockDeadline;
    private boolean yourTurn;
    private boolean ended;
    private Timeline timer;
    private Label status, clock;
    private TextField guess;
    private Button guessButton;
    private TableView<GuessRow> history;
    private final GameAudio audio = new GameAudio();
    private BattleScreen battleScreen;
    private NetworkClient network;
    private boolean loggedIn;
    private Label authError;
    private Label secretError;
    private Button authSubmit;
    private TextArea chat;
    private String pendingInvite = "";
    private String modalKind = "";
    private Runnable modalCancel;
    private final ObservableList<OnlinePlayer> playerData = FXCollections.observableArrayList();
    private final ObservableList<RankingRow> rankingData = FXCollections.observableArrayList();
    private final ObservableList<MatchHistoryRow> matchData = FXCollections.observableArrayList();
    private Label statTotal, statRate;

    private final javafx.beans.property.BooleanProperty muted = new javafx.beans.property.SimpleBooleanProperty(false);
    private StackPane screens;
    private ScreenLayout modal;
    private javafx.scene.Node modalFocus;

    public record OnlinePlayer(String account, String name, String wins, String state) {}

    public record RankingRow(String name, String wins, String winRate) {}

    public record MatchHistoryRow(String matchId, String date, String opponent, String range, String result) {}

    public record GuessRow(String turn, String player, String number, String hint) {}

    // Khởi động màn đầu; chỉ kết nối khi người chơi thực hiện đăng nhập/đăng ký.
    @Override
    public void start(Stage stage) {
        window = stage;
        window.setOnCloseRequest(event -> {
            stopTimer();
            if (network != null) network.close();
            audio.close();
        });
        login();
    }

    private void show(Pane root, String title, int width, int height) {
        if (battleScreen != null) battleScreen.stop();
        modalCancel = null;
        modalKind = "";
        screens = new StackPane(root);
        modal = null;
        Scene scene = new Scene(screens, width, height);
        scene.getStylesheets().add(getClass().getResource("/resources/style.css").toExternalForm());
        window.setTitle("Đấu Số - " + title);
        window.setMinWidth((root instanceof BattleScreen || root instanceof ScreenLayout) ? 976 : 400);
        window.setMinHeight((root instanceof BattleScreen || root instanceof ScreenLayout) ? 580 : 300);
        window.setScene(scene);
        window.sizeToScene();
        window.show();
        audio.resumeMusic();
    }

    Label label(String text, String style) {
        Label l = new Label(text);
        if (!style.isEmpty()) l.getStyleClass().add(style);
        return l;
    }

    Button button(String text, Runnable action) {
        return button(text, action, true);
    }

    Button button(String text, Runnable action, boolean playClick) {
        Button b = new Button(text);
        b.setOnAction(e -> {
            if (playClick) audio.play("click");
            action.run();
        });
        return b;
    }

    HBox row(javafx.scene.Node... nodes) {
        HBox r = new HBox(10, nodes);
        r.setAlignment(Pos.CENTER);
        return r;
    }

    Button soundButton() {
        Button sound = new Button();
        sound.setId("sound-toggle");
        sound.getStyleClass().add("image-button");
        sound.graphicProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() -> {
            String file = muted.get() ? "sound-icon-off.png" : "sound-icon-on.png";
            var icon = ScreenLayout.icon(file, 48);
            icon.setFitHeight(48);
            return icon;
        }, muted));
        sound.accessibleTextProperty().bind(javafx.beans.binding.Bindings.when(muted).then("Âm thanh: tắt").otherwise("Âm thanh: bật"));
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(javafx.beans.binding.Bindings.when(muted).then("Bật âm thanh").otherwise("Tắt âm thanh"));
        sound.setTooltip(tip);
        sound.setOnAction(e -> {
            muted.set(!muted.get());
            audio.mute(muted.get());
        });
        return sound;
    }

    private void login() {
        stopTimer();
        ScreenLayout root = new ScreenLayout();
        root.art("dau-so-logo.png", 330, 60, 440, 160);
        root.put(soundButton(), 1032, 16, 48, 48);
        root.imageButton(button("Đăng nhập", () -> auth(false, "")), "home-login.png", 375, 270, 350, 96).setId("home-login");
        root.imageButton(button("Đăng ký", () -> auth(true, "")), "home-register.png", 375, 382, 350, 96).setId("home-register");
        show(root, "Chào mừng", 1000, 564);
    }

    void auth(boolean registering, String notice) {
        stopTimer();
        if (modal != null) closeModal();
        ScreenLayout root = overlay();
        screens.getChildren().get(0).setVisible(false);
        LoginRegisterScreen form = new LoginRegisterScreen(this, root, registering, notice, accountName, soundButton());
        authError = form.error;
        authSubmit = form.submit;
    }

    void sendAuth(boolean registering, String name, String password) {
        accountName = name;
        send(Message.of(registering ? "REGISTER" : "LOGIN", "username", name, "password", password));
    }

    private ScreenLayout overlay() {
        if (modal != null) closeModal();
        modalFocus = window.getScene().getFocusOwner();
        for (javafx.scene.Node n : screens.getChildren()) n.setDisable(true);
        modal = new ScreenLayout(false);
        modal.setId("screen-modal");
        screens.getChildren().add(modal);
        modal.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dismissModal();
                e.consume();
            }
        });
        modal.setFocusTraversable(true);
        modal.requestFocus();
        return modal;
    }

    private void closeModal() {
        if (modal == null) return;
        screens.getChildren().remove(modal);
        modal = null;
        modalCancel = null;
        modalKind = "";
        for (javafx.scene.Node n : screens.getChildren()) {
            n.setDisable(false);
            n.setVisible(true);
        }
        if (modalFocus != null) modalFocus.requestFocus();
    }

    private ScreenLayout modal(String title) {
        ScreenLayout root = overlay();
        String image = switch (title) {
            case "THÔNG BÁO" -> "modal-notice.png";
            case "CHI TIẾT TRẬN ĐẤU" -> "modal-match-details.png";
            case "ĐANG CHỜ ĐỐI THỦ" -> "modal-wait-opponent.png";
            case "MỜI THI ĐẤU" -> "modal-send-invite.png";
            case "MỜI CHƠI LẠI" -> "modal-send-rematch.png";
            case "NHẬN LỜI MỜI" -> "modal-receive-invite.png";
            case "LỜI MỜI CHƠI LẠI" -> "modal-receive-rematch.png";
            case "CHỌN SỐ BÍ MẬT" -> "modal-choose-secret.png";
            case "ĐÃ CHỌN SỐ" -> "modal-secret-chosen.png";
            case "ĐỔI TÊN HIỂN THỊ" -> "modal-edit-profile.png";
            default -> throw new IllegalArgumentException("Chưa có ảnh hộp thoại: " + title);
        };
        if (title.equals("CHI TIẾT TRẬN ĐẤU")) {
            root.art(image, 170, 70, 760, 480);
            root.imageButton(button("Đóng", this::dismissModal), "close-button.png", 830, 150, 36, 36);
        } else {
            root.art(image, 280, 130, 540, 360);
            root.imageButton(button("Đóng", this::dismissModal), "close-button.png", 746, 202, 36, 36);
        }
        return root;
    }

    void info(String text) {
        ScreenLayout root = modal("THÔNG BÁO");
        Label message = label(text, "screen-body");
        message.setWrapText(true);
        root.put(message, 350, 220, 400, 150);
        root.imageButton(button("Xác nhận", this::closeModal), "confirm-button.png", 470, 390, 160, 48);
    }

    <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(v -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(v.getValue())));
        boolean left = title.equals("Tên") || title.equals("Người chơi") || title.equals("Đối thủ") || title.equals("Gợi ý");
        c.getStyleClass().add(left ? "text-column" : "value-column");
        c.setCellFactory(col -> new TableCell<T, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER);
                setTooltip(empty || item == null ? null : new Tooltip(item));
            }
        });
        return c;
    }

    void editProfile() {
        ScreenLayout root = modal("ĐỔI TÊN HIỂN THỊ");
        root.put(label("Tên hiển thị (1–20 ký tự)", "screen-label"), 385, 220, 330, 25);
        TextField field = new TextField(username);
        field.setId("profile-name");
        root.input(field, 385, 255, 330);
        Label error = label("", "screen-error");
        root.put(error, 385, 308, 330, 40);
        Button save = button("Xác nhận", () -> {
            String value = field.getText().strip();
            if (value.isEmpty() || value.codePointCount(0, value.length()) > 20 || value.codePoints().anyMatch(Character::isISOControl)) {
                error.setText("Nhập tên từ 1 đến 20 ký tự.");
                return;
            }
            send(Message.of("PROFILE_UPDATE", "displayName", value));
        });
        save.setId("profile-save");
        save.setDefaultButton(true);
        root.imageButton(save, "confirm-button.png", 352, 385, 180, 54);
        root.imageButton(button("Hủy", this::closeModal), "cancel-button.png", 568, 385, 180, 54);
        Platform.runLater(field::requestFocus);
    }

    private void lobby() {
        stopTimer();
        LobbyScreen lobby = new LobbyScreen(this, username, playerData, rankingData, matchData);
        statTotal = lobby.statTotal;
        statRate = lobby.statRate;
        show(lobby.root, "Sảnh", 1000, 564);
        send(Message.of("PROFILE"));
    }

    void invitePlayer(OnlinePlayer player) {
        opponent = player.name();
        opponentAccount = player.account();
        invite();
    }

    private ComboBox<Integer> ranges() {
        ComboBox<Integer> c = new ComboBox<>(FXCollections.observableArrayList(100, 1000, 10000));
        c.setConverter(new javafx.util.StringConverter<Integer>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : "1–" + String.format(java.util.Locale.GERMANY, "%,d", value);
            }

            @Override
            public Integer fromString(String value) {
                throw new UnsupportedOperationException("Chọn phạm vi từ danh sách");
            }
        });
        c.setValue(range);
        c.setPrefWidth(180);
        return c;
    }

    // Người gửi lời mời là người chọn phạm vi, kể cả khi chơi lại.
    private void invite() {
        audio.play("invite");
        ScreenLayout root = modal(ended ? "MỜI CHƠI LẠI" : "MỜI THI ĐẤU");
        root.put(label("Đối thủ: " + opponent, "screen-label"), 350, 222, 400, 25);
        root.put(label("Chọn phạm vi số", "screen-body"), 350, 258, 180, 25);
        ComboBox<Integer> choice = ranges();
        choice.setId("invite-range");
        root.put(choice, 540, 253, 190, 36);
        root.put(label("Đối thủ cần đồng ý để bắt đầu trận.", "screen-body"), 350, 309, 400, 30);
        Button submit = button("Gửi lời mời", () -> {
            range = choice.getValue();
            send(Message.of(ended ? "REMATCH" : "INVITE", "opponent", opponentAccount, "range", "" + range, "match", "" + matchId));
            closeModal();
        });
        submit.setId("send-invite");
        root.imageButton(submit, "send-invite.png", 352, 388, 180, 54);
        root.imageButton(button("Hủy", this::closeModal), "cancel-button.png", 568, 388, 180, 54);
    }

    // Gửi số bí mật lên server, sau đó chờ cả hai người sẵn sàng.
    private void chooseSecret() {
        ScreenLayout root = modal("CHỌN SỐ BÍ MẬT");
        modalKind = "secret";
        modalCancel = () -> send(Message.of("LEAVE"));
        root.put(label("Số này không hiển thị cho đối thủ.", "screen-body"), 350, 222, 400, 28);
        TextField input = new TextField();
        input.setPromptText("Số từ 1 đến " + range);
        input.setId("secret-input");
        root.input(input, 385, 263, 330);
        Label error = label("", "screen-error");
        root.put(error, 350, 320, 400, 35);
        secretError = error;
        Button confirm = button("Xác nhận", () -> {
            try {
                int number = Integer.parseInt(input.getText().trim());
                if (number < 1 || number > range) throw new NumberFormatException();
                send(Message.of("SECRET", "match", "" + matchId, "number", "" + number));
                input.setDisable(true);
                root.lookup("#secret-confirm").setDisable(true);
            } catch (NumberFormatException ex) {
                error.setText("Nhập số nguyên từ 1 đến " + range);
            }
        });
        confirm.setId("secret-confirm");
        root.imageButton(confirm, "confirm-button.png", 352, 388, 180, 54);
        root.imageButton(button("Hủy", this::dismissModal), "cancel-button.png", 568, 388, 180, 54);
        input.setOnAction(event -> confirm.fire());
        Platform.runLater(input::requestFocus);
    }

    private void game() {
        stopTimer();
        ended = false;
        turnId = 0;
        status = label("ĐẾN LƯỢT BẠN", "");
        clock = label("30", "");
        Label hint = label("", "input-error");
        hint.setWrapText(true);
        hint.setVisible(false);
        hint.setManaged(false);
        guess = new TextField();
        guess.setPromptText("Nhập số từ 1 đến " + range);
        guess.setMaxWidth(260);
        history = new TableView<>();
        history.getColumns().add(column("Lượt", GuessRow::turn));
        history.getColumns().add(column("Người chơi", GuessRow::player));
        history.getColumns().add(column("Số đoán", GuessRow::number));
        history.getColumns().add(column("Gợi ý", GuessRow::hint));
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        history.setSelectionModel(null);
        guess.setOnKeyTyped(e -> {
            if (!e.getCharacter().isBlank()) audio.play("key");
        });
        guessButton = button("ĐOÁN SỐ", () -> {
            if (ended || guess.isDisabled()) return;
            try {
                int number = Integer.parseInt(guess.getText().trim());
                if (number < 1 || number > range) throw new NumberFormatException();
                guess.setDisable(true);
                guessButton.setDisable(true);
                audio.play("guess");
                send(Message.of("GUESS", "match", "" + matchId, "turn", "" + turnId, "number", "" + number));
                guess.clear();
                hint.setVisible(false);
                hint.setManaged(false);
            } catch (NumberFormatException ex) {
                audio.play("warning");
                hint.setText("Nhập số nguyên từ 1 đến " + range);
                hint.setVisible(true);
                hint.setManaged(true);
            }
        }, false);
        guess.setOnAction(event -> guessButton.fire());
        guess.setDisable(true);
        guessButton.setDisable(true);
        history.setPlaceholder(new Label("Chưa có dự đoán"));
        history.setRowFactory(table -> new TableRow<GuessRow>() {
            @Override
            protected void updateItem(GuessRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("your-guess", "opponent-guess", "latest-guess");
                if (!empty && item != null) {
                    getStyleClass().add(item.player().equals(username) ? "your-guess" : "opponent-guess");
                    if (getIndex() == table.getItems().size() - 1) getStyleClass().add("latest-guess");
                }
            }
        });
        chat = new TextArea();
        chat.setEditable(false);
        chat.setWrapText(true);
        chat.getStyleClass().add("chat-area");
        TextField message = new TextField();
        message.setId("chat-input");
        message.setPromptText("Nhập tin nhắn");
        Button send = button("", () -> {
            if (!message.getText().isBlank()) {
                send(Message.of("CHAT", "match", "" + matchId, "text", message.getText().trim()));
                message.clear();
            }
        });
        send.setAccessibleText("Gửi tin nhắn");
        send.setId("chat-send");
        message.setOnAction(e -> send.fire());
        battleScreen = new BattleScreen(username, opponent, range, status, clock, history, guess, guessButton, hint, chat, message, send);
        battleScreen.soundButton(soundButton());
        show(battleScreen, "Phòng đấu", 1000, 564);
        battleScreen.remaining(30);
    }

    // Đồng hồ client chỉ để hiển thị. Server tự xử lý hết giờ và đổi lượt.
    private void showTurn(Message message) {
        if (message.number("match") != matchId || ended) return;
        if (modalKind.equals("secret") || modalKind.equals("waiting-secret")) closeModal();
        turnId = message.number("turn");
        boolean yours = message.get("player").equals(accountName);
        yourTurn = yours;
        stopTimer();
        battleScreen.active(yours);
        guess.setDisable(!yours);
        guessButton.setDisable(!yours);
        clockDeadline = System.nanoTime() + message.number("remainingMillis") * 1_000_000L;
        updateClock();
        timer = new Timeline(new KeyFrame(Duration.millis(100), event -> updateClock()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        audio.play("turn");
    }

    private void updateClock() {
        int remaining = (int) Math.max(0, (clockDeadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
        if (remaining != seconds && remaining <= 5 && remaining > 0) audio.play("countdown");
        seconds = remaining;
        battleScreen.remaining(seconds);
        if (seconds == 0) {
            guess.setDisable(true);
            guessButton.setDisable(true);
        }
    }

    // Hiện kết quả và phát âm cùng lúc, giữ nguyên lượt và đồng hồ phía sau.
    private void finish(Message message) {
        if (message.number("match") != matchId || ended) return;
        ended = true;
        stopTimer();
        closeModal();
        pendingInvite = "";
        battleScreen.stop();
        guess.setDisable(true);
        guessButton.setDisable(true);
        boolean won = message.get("winner").equals(accountName);
        String reason = message.get("reason");
        String text = reason.equals("EXACT") ? (won ? "Bạn đã đoán đúng số bí mật của đối thủ." : "Đối thủ đã đoán đúng số bí mật của bạn.")
                                             : (won ? "Đối thủ đã rời trận hoặc mất kết nối." : "Bạn đã rời trận đấu.");
        battleScreen.showResult(won, text, this::invite, () -> send(Message.of("LOBBY")));
        audio.play(won ? "win" : "lose");
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    // Tạo kết nối khi cần; mọi phản hồi được chuyển về luồng JavaFX.
    void send(Message message) {
        if (network == null)
            network = new NetworkClient(result -> Platform.runLater(() -> handleMessage(result)), error -> Platform.runLater(() -> {
                network = null;
                loggedIn = false;
                stopTimer();
                matchId = 0;
                ended = false;
                login();
                info(error);
            }));
        network.send(message);
    }

    // Xử lý phản hồi từ server và cập nhật giao diện tương ứng.
    private void handleMessage(Message message) {
        switch (message.type()) {
            case "REGISTERED" -> auth(false, "Đăng ký thành công. Bạn có thể đăng nhập.");
            case "LOGIN_OK" -> {
                loggedIn = true;
                accountName = message.get("username");
                username = message.get("displayName");
                matchId = 0;
                ended = false;
                lobby();
            }
            case "LOGOUT_OK" -> {
                loggedIn = false;
                matchId = 0;
                ended = false;
                playerData.clear();
                rankingData.clear();
                matchData.clear();
                login();
            }
            case "USERS" -> {
                playerData.setAll(message.rows().stream()
                    .filter(row -> !row.get("username").equals(accountName))
                    .map(row -> new OnlinePlayer(row.get("username"), row.get("displayName"), row.get("wins"), row.get("state")))
                    .toList());
            }
            case "RANKING" ->
                rankingData.setAll(message.rows().stream()
                    .map(row -> new RankingRow(row.get("display_name"), row.get("wins"), row.get("rate") + "%"))
                    .toList());
            case "HISTORY" -> {
                matchData.setAll(message.rows().stream()
                    .map(row -> new MatchHistoryRow(
                        row.get("id"), row.get("started_at"), row.get("opponent"), "1–" + row.get("range_max"), row.get("result")))
                    .toList());
            }
            case "PROFILE" -> {
                if (statTotal != null)
                    statTotal.setText("Tổng số trận: " + message.get("total") + "  ·  Thắng: " + message.get("wins")
                        + "  ·  Thua: " + message.get("losses"));
                if (statRate != null) statRate.setText("Tỷ lệ thắng: " + message.get("rate") + "%");
            }
            case "PROFILE_UPDATED" -> {
                username = message.get("displayName");
                closeModal();
                lobby();
            }
            case "DETAILS" -> showDetails(message);
            case "INVITED" -> incomingInvite(message);
            case "INVITE_SENT" -> {
                pendingInvite = message.get("id");
                waiting("ĐANG CHỜ ĐỐI THỦ", message.get("text"), () -> send(Message.of("INVITE_CANCEL")));
                modalKind = "invite";
            }
            case "INVITE_DECLINED", "INVITE_CANCELLED" -> {
                if (message.get("id").equals(pendingInvite)) {
                    pendingInvite = "";
                    closeModal();
                    info(message.type().equals("INVITE_DECLINED") ? message.get("text") : "Lời mời đã bị hủy hoặc hết hạn.");
                }
            }
            case "MATCH_CREATED" -> {
                pendingInvite = "";
                closeModal();
                matchId = message.number("match");
                range = (int) message.number("range");
                opponentAccount = message.get("opponent");
                opponent = message.get("opponentName");
                game();
                chooseSecret();
            }
            case "SECRET_OK" -> {
                waiting("ĐÃ CHỌN SỐ", "Đang chờ đối thủ chọn số bí mật…", () -> send(Message.of("LEAVE")));
                modalKind = "waiting-secret";
            }
            case "TURN" -> showTurn(message);
            case "GUESS_RESULT" -> {
                if (message.number("match") != matchId || ended) return;
                String player = message.get("player").equals(accountName) ? username : opponent;
                history.getItems().add(new GuessRow(message.get("turn"), player, message.get("number"), hintText(message.get("hint"))));
                history.scrollTo(history.getItems().size() - 1);
                if (!message.get("hint").equals("EXACT")) audio.play("hint");
            }
            case "CHAT" -> {
                if (message.number("match") == matchId && chat != null)
                    chat.appendText(message.get("displayName") + ": " + message.get("text") + "\n");
            }
            case "MATCH_END" -> finish(message);
            case "LEFT", "LOBBY_OK" -> {
                matchId = 0;
                ended = false;
                pendingInvite = "";
                lobby();
            }
            case "ERROR" -> {
                if (!loggedIn && authError != null && modal != null) {
                    authError.setText(message.get("text"));
                    if (authSubmit != null) authSubmit.setDisable(false);
                } else if (message.get("request").equals("SECRET") && !ended && turnId == 0) {
                    chooseSecret();
                    secretError.setText(message.get("text"));
                } else {
                    if (message.get("request").equals("GUESS") && !ended && yourTurn && seconds > 0) {
                        guess.setDisable(false);
                        guessButton.setDisable(false);
                    }
                    info(message.get("text"));
                }
            }
            case "INFO" -> info(message.get("text"));
            default -> System.err.println("Thông điệp chưa hỗ trợ: " + message.type());
        }
    }

    private static String hintText(String hint) {
        return switch (hint) {
            case "HIGHER" -> "Số bí mật lớn hơn ↑";
            case "LOWER" -> "Số bí mật nhỏ hơn ↓";
            case "EXACT" -> "Đoán chính xác!";
            case "TIMEOUT" -> "Hết giờ · mất lượt";
            default -> hint;
        };
    }

    void dismissModal() {
        Runnable cancel = modalCancel;
        closeModal();
        if (cancel != null) cancel.run();
    }

    // Hiển thị trạng thái chờ trên ảnh hộp thoại tương ứng.
    private void waiting(String title, String text, Runnable cancel) {
        ScreenLayout root = modal(title);
        modalCancel = cancel;
        Label content = label(text, "screen-body");
        content.setWrapText(true);
        root.put(content, 350, 230, 400, 100);
        root.imageButton(button("Hủy", this::dismissModal), "cancel-button.png", 460, 385, 180, 54);
    }

    private void incomingInvite(Message message) {
        pendingInvite = message.get("id");
        audio.play("invite");
        ScreenLayout root = modal(Boolean.parseBoolean(message.get("rematch")) ? "LỜI MỜI CHƠI LẠI" : "NHẬN LỜI MỜI");
        modalKind = "invite";
        Runnable decline = () -> send(Message.of("INVITE_REPLY", "id", message.get("id"), "accept", "false"));
        modalCancel = decline;
        Label text = label(message.get("opponentName") + " mời bạn thi đấu.\nPhạm vi: 1–" + message.get("range"), "screen-body");
        text.setWrapText(true);
        root.put(text, 350, 225, 400, 110);
        Button accept = button("Đồng ý", () -> send(Message.of("INVITE_REPLY", "id", message.get("id"), "accept", "true")));
        accept.setId("accept-invite");
        root.imageButton(accept, "invite-accept.png", 352, 385, 180, 54);
        Button refuse = button("Từ chối", decline);
        refuse.setId("decline-invite");
        root.imageButton(refuse, "invite-decline.png", 568, 385, 180, 54);
    }

    private void showDetails(Message message) {
        ScreenLayout root = modal("CHI TIẾT TRẬN ĐẤU");
        TableView<GuessRow> table = new TableView<>();
        table.setId("match-details-table");
        TableColumn<GuessRow, String> turn = column("Lượt", GuessRow::turn);
        TableColumn<GuessRow, String> player = column("Người chơi", GuessRow::player);
        TableColumn<GuessRow, String> number = column("Số đoán", GuessRow::number);
        TableColumn<GuessRow, String> hint = column("Gợi ý", GuessRow::hint);
        turn.setPrefWidth(60);
        player.setPrefWidth(135);
        number.setPrefWidth(90);
        hint.setPrefWidth(305);
        table.getColumns().addAll(turn, player, number, hint);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        table.setSelectionModel(null);
        table.setPlaceholder(new Label("Trận kết thúc trước khi có lượt đoán"));
        for (var row : message.rows())
            table.getItems().add(new GuessRow(row.get("turn_no"), row.get("player"), row.get("number"), hintText(row.get("hint"))));
        root.put(table, 230, 192, 640, 275);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
