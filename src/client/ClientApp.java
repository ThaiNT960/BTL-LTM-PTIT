package client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import common.Message;
import java.util.*;

/** Original UI, connected to the authoritative TCP server. */
public class ClientApp extends Application {
    private Stage window;
    private String username = "user1";
    private String opponent = "user2";
    private int range = 100;
    private int secret, seconds, turnCount;
    private long matchId;
    private long displayDeadline;
    private NetworkClient network;
    private Label loginError;
    private TextArea chatArea;
    private Label gameHint;
    private TableView<Player> onlineTable, rankingTable;
    private TableView<GuessRow> matchesTable;
    private final javafx.collections.ObservableList<Player> onlineData=FXCollections.observableArrayList();
    private final Map<GuessRow,String> matchIds=new IdentityHashMap<>();
    private Dialog<ButtonType> activeInvite, secretDialog;
    private String activeInviteId;
    private Alert resultDialog;
    private boolean returningToLobby;
    private boolean ended;
    private Timeline timer;
    private Label status, clock;
    private TextField guess;
    private Button guessButton;
    private TableView<GuessRow> history;

    public record Player(String name, String wins, String state) {}
    public record GuessRow(String turn, String player, String number, String hint) {}

    @Override public void start(Stage stage) {
        window = stage;
        window.setMinWidth(400); window.setMinHeight(300);
        window.setOnCloseRequest(e -> {stopTimer();if(network!=null)network.close();});
        login();
    }

    private void show(Pane root, String title, int width, int height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("/resources/style.css").toExternalForm());
        window.setTitle("Game Đoán Số - " + title);
        window.setScene(scene); window.sizeToScene(); window.show();
    }
    private Label label(String text, String style) {
        Label l = new Label(text); if (!style.isEmpty()) l.getStyleClass().add(style); return l;
    }
    private Button button(String text, Runnable action) {
        Button b = new Button(text); b.setOnAction(e -> action.run()); return b;
    }
    private HBox row(javafx.scene.Node... nodes) {
        HBox r = new HBox(10, nodes); r.setAlignment(Pos.CENTER); return r;
    }
    private VBox box(javafx.scene.Node... nodes) {
        VBox b = new VBox(10, nodes); b.setPadding(new Insets(10)); return b;
    }
    private void login() {
        stopTimer();
        TextField name = new TextField(username); name.setPromptText("Tên tài khoản");
        PasswordField password = new PasswordField(); password.setPromptText("Mật khẩu");
        Label error = label("", "");
        loginError=error;
        Button enter = button("Đăng nhập", () -> {
            if (name.getText().isBlank() || password.getText().isBlank()) {
                error.setText("Nhập tên tài khoản và mật khẩu."); return;
            }
            send(Message.of("LOGIN","username",name.getText().trim(),"password",password.getText()));
        }); enter.setPrefWidth(250); enter.setDefaultButton(true);
        Button register = button("Đăng ký", () -> {
            TextField newName = new TextField(); newName.setPromptText("Tên tài khoản");
            PasswordField pass = new PasswordField(); pass.setPromptText("Mật khẩu");
            Dialog<ButtonType> d = dialog("Đăng ký tài khoản", box(newName, pass));
            d.showAndWait().filter(ButtonType.OK::equals).ifPresent(v -> {
                if (!newName.getText().isBlank()) name.setText(newName.getText());
                send(Message.of("REGISTER","username",newName.getText().trim(),"password",pass.getText()));
            });
        });
        ContextMenu accountMenu=new ContextMenu();
        MenuItem registerItem=new MenuItem("Đăng ký tài khoản");registerItem.setOnAction(e->register.fire());
        accountMenu.getItems().add(registerItem);name.setContextMenu(accountMenu);
        name.setTooltip(new Tooltip("Nhấn chuột phải để đăng ký tài khoản mới"));
        VBox form = new VBox(20, label("Đăng Nhập", "heading"), name, password, enter, error);
        form.setPadding(new Insets(20)); form.setAlignment(Pos.CENTER);
        show(form, "Đăng nhập", 400, 300);
    }
    private Dialog<ButtonType> dialog(String title, Pane content) {
        Dialog<ButtonType> d = new Dialog<>(); d.initOwner(window); d.setTitle(title);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); return d;
    }
    private void info(String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK); a.initOwner(window);
        a.setHeaderText(null); a.setTitle("Game Đoán Số"); a.show();
    }
    private <T> TableColumn<T,String> column(String title, java.util.function.Function<T,String> value) {
        TableColumn<T,String> c = new TableColumn<>(title);
        c.setCellValueFactory(v -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(v.getValue())));
        return c;
    }
    private void lobby() {
        stopTimer();
        BorderPane root = new BorderPane();
        Menu menu = new Menu("Menu");
        MenuItem profile = new MenuItem("Thành tích cá nhân");
        profile.setOnAction(e -> send(Message.of("PROFILE")));
        MenuItem logout = new MenuItem("Đăng xuất"); logout.setOnAction(e -> send(Message.of("LOGOUT")));
        menu.getItems().addAll(profile, new SeparatorMenuItem(), logout); root.setTop(new MenuBar(menu));
        TableView<Player> players = new TableView<>();
        onlineTable=players;
        players.getColumns().add(column("Tên", Player::name));
        players.getColumns().add(column("Trận thắng", Player::wins));
        players.getColumns().add(column("Trạng thái", Player::state));
        players.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        var data = onlineData;
        players.setItems(data);
        TextField search = new TextField(); search.setPromptText("Tìm kiếm theo tên");
        Runnable filter = () -> players.setItems(data.filtered(p -> p.name().toLowerCase().contains(search.getText().trim().toLowerCase())));
        search.setPrefWidth(150);
        HBox tools = new HBox(10, search, button("Lọc Online", () -> players.setItems(data.filtered(p -> p.state().equals("Rảnh")))), button("Tìm", filter));
        Runnable invite = () -> {
            Player p = players.getSelectionModel().getSelectedItem();
            if (p == null) { info("Chọn một người trong bảng để mời đấu."); return; }
            if (!p.state().equals("Rảnh")) { info("Người chơi này đang chơi."); return; }
            opponent = p.name(); invite();
        };
        players.setRowFactory(table -> {
            TableRow<Player> playerRow = new TableRow<>();
            playerRow.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 1 && !playerRow.isEmpty()) {
                    players.getSelectionModel().select(playerRow.getItem());
                    invite.run();
                }
            });
            return playerRow;
        });
        players.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) { invite.run(); e.consume(); }
        });
        VBox list = box(tools, players);
        VBox.setVgrow(players, Priority.ALWAYS);
        TableView<Player> ranking = new TableView<>();
        rankingTable=ranking;
        ranking.getColumns().add(column("Tên", Player::name)); ranking.getColumns().add(column("Trận thắng", Player::wins));
        ranking.getColumns().add(column("Tỷ lệ thắng", Player::state));
        ranking.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        VBox matches = box(label("Lịch sử đấu", "heading"));
        TableView<GuessRow> matchTable = new TableView<>();
        matchesTable=matchTable;
        matchTable.getColumns().add(column("Ngày", GuessRow::turn)); matchTable.getColumns().add(column("Đối thủ", GuessRow::player));
        matchTable.getColumns().add(column("Phạm vi", GuessRow::number)); matchTable.getColumns().add(column("Kết quả", GuessRow::hint));
        matchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        matchTable.setOnMouseClicked(e->{if(e.getClickCount()==2&&matchTable.getSelectionModel().getSelectedItem()!=null){
            GuessRow selected=matchTable.getSelectionModel().getSelectedItem();String id=matchIds.get(selected);
            if(id!=null)send(Message.of("DETAILS","match",id));
        }});
        matches.getChildren().add(matchTable); VBox.setVgrow(matchTable, Priority.ALWAYS);
        TabPane tabs = new TabPane(new Tab("Danh sách người chơi", list), new Tab("Lịch sử đấu", matches), new Tab("Bảng xếp hạng", ranking));
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs,oldValue,newValue)->{
            if(newValue.intValue()==1)send(Message.of("HISTORY"));
            if(newValue.intValue()==2)send(Message.of("RANKING"));
        });
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); root.setCenter(tabs);
        show(root, "Sảnh", 520, 600);
        returningToLobby=false;
    }
    private ComboBox<Integer> ranges() {
        ComboBox<Integer> c = new ComboBox<>(FXCollections.observableArrayList(100, 1000, 10000));
        c.setConverter(new javafx.util.StringConverter<Integer>() {
            @Override public String toString(Integer value) { return value == null ? "" : "1–" + String.format(java.util.Locale.GERMANY, "%,d", value); }
            @Override public Integer fromString(String value) { throw new UnsupportedOperationException("Chọn phạm vi từ danh sách"); }
        });
        c.setValue(range); c.setPrefWidth(180); return c;
    }
    private void invite() {
        ComboBox<Integer> c = ranges();
        Dialog<ButtonType> d = dialog("Mời thi đấu", box(label("Người chơi: " + opponent, ""),
                label("Chọn phạm vi số", ""), c, label("Đối thủ cần chấp nhận lời mời để bắt đầu.", "")));
        ButtonType send = new ButtonType("Gửi lời mời", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(send, cancel);
        d.showAndWait().filter(send::equals).ifPresent(v -> {
            range=c.getValue();send(Message.of(ended&&matchId>0?"REMATCH":"INVITE","opponent",opponent,"range",""+range,"match",""+matchId));
        });
    }
    private void chooseSecret() {
        TextField input = new TextField(); input.setPromptText("Số từ 1 đến " + range);
        Label error = new Label();
        Dialog<ButtonType> d = dialog("Chọn số bí mật", box(new Label("Số này không hiển thị cho đối thủ."), input, error));
        secretDialog=d;
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try { int n=Integer.parseInt(input.getText().trim()); if(n<1 || n>range) throw new NumberFormatException(); secret=n; }
            catch(NumberFormatException ex) { error.setText("Nhập số nguyên từ 1 đến " + range); e.consume(); }
        });
        d.setOnHidden(e->{secretDialog=null;if(ended)return;if(d.getResult()==ButtonType.OK)send(Message.of("SECRET","match",""+matchId,"number",""+secret));else{ returningToLobby=true;send(Message.of("LEAVE"));}});
        d.show();
    }
    private void game() {
        stopTimer(); ended=false; turnCount=0;
        BorderPane root = new BorderPane();
        status=label("Chờ hai người chọn số bí mật", ""); clock=label("—", "timer");
        VBox bar = new VBox(5, row(label(username + "  VS  " + opponent + "    |    Phạm vi: 1–" + range, "")), row(status, clock));
        bar.setAlignment(Pos.CENTER); bar.getStyleClass().add("gamebar"); root.setTop(bar);
        Label hint=label("Đoán số bí mật của đối thủ", ""); hint.setWrapText(true);
        gameHint=hint;
        guess = new TextField(); guess.setPromptText("Nhập số từ 1 đến " + range); guess.setMaxWidth(260);
        history = new TableView<>(); history.getColumns().add(column("Lượt", GuessRow::turn));
        history.getColumns().add(column("Người chơi", GuessRow::player)); history.getColumns().add(column("Số đoán", GuessRow::number));
        history.getColumns().add(column("Gợi ý", GuessRow::hint)); history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        guessButton=button("Đoán số", () -> {
            if(ended || guess.isDisabled()) return;
            try { int n=Integer.parseInt(guess.getText().trim()); if(n<1 || n>range) throw new NumberFormatException();
                send(Message.of("GUESS","match",""+matchId,"turn",""+turnCount,"number",""+n));guess.clear();
            } catch(NumberFormatException ex) { hint.setText("Nhập số nguyên từ 1 đến " + range); }
        }); guess.setOnAction(e -> guessButton.fire());
        VBox arena=box(hint, row(guess, guessButton),
                label("Lịch sử dự đoán", ""), history);
        arena.setAlignment(Pos.TOP_CENTER); VBox.setVgrow(history,Priority.ALWAYS);
        TextArea chat=new TextArea(); chat.setEditable(false); chat.setWrapText(true); chat.getStyleClass().add("chat-area");chatArea=chat;
        TextField message=new TextField(); message.setPromptText("Nhập tin nhắn");
        Button send=button("Gửi", () -> { if(!message.getText().isBlank()) {send(Message.of("CHAT","match",""+matchId,"text",message.getText().trim()));message.clear();} });
        message.setOnAction(e -> send.fire());
        VBox chatBox=new VBox(5,chat,message,send); chatBox.setPrefWidth(200); chatBox.setMinWidth(170); VBox.setVgrow(chat,Priority.ALWAYS);
        HBox center=new HBox(arena,chatBox); HBox.setHgrow(arena,Priority.ALWAYS); root.setCenter(center);
        HBox bottom=row(button("Thoát trận", () -> {
            Alert a=new Alert(Alert.AlertType.CONFIRMATION,"Thoát giữa trận sẽ bị xử thua. Bạn muốn thoát?",ButtonType.OK,ButtonType.CANCEL); a.initOwner(window);
            a.showAndWait().filter(ButtonType.OK::equals).ifPresent(v -> {returningToLobby=true;send(Message.of("LEAVE"));});
        })); root.setBottom(bottom);
        show(root,"Phòng đấu",800,500);guess.setDisable(true);guessButton.setDisable(true);
    }
    private void finish(boolean won,String reason) {
        ended=true;stopTimer();guess.setDisable(true);guessButton.setDisable(true);status.setText(won?"Bạn thắng!":"Bạn thua!");clock.setText("Kết thúc");
        Platform.runLater(() -> {
            ButtonType again=new ButtonType("Yêu cầu chơi lại");ButtonType exit=new ButtonType("Về sảnh");
            Alert a=new Alert(Alert.AlertType.INFORMATION,reason.equals("EXACT")?"Trận đấu kết thúc do đoán chính xác.":"Trận đấu kết thúc do người chơi rời trận hoặc mất kết nối.",again,exit);a.initOwner(window);a.setHeaderText(won?"Chiến thắng!":"Trận đấu kết thúc");
            if(returningToLobby)return;
            resultDialog=a;
            a.setOnHidden(e->{if(a.getResult()==again)invite();else {matchId=0;ended=false;lobby();send(Message.of("LOBBY"));}});a.show();
        });
    }
    private void send(Message m) {
        if(network==null)network=new NetworkClient(message->Platform.runLater(()->receive(message)),text->Platform.runLater(()->{
            network=null;ended=true;stopTimer();if(secretDialog!=null)secretDialog.close();activeInviteId=null;if(activeInvite!=null)activeInvite.close();
            if(resultDialog!=null){resultDialog.setOnHidden(null);resultDialog.close();resultDialog=null;}
            matchId=0;login();loginError.setText(text);
        }));
        network.send(m);
    }
    private static String hint(String value) {
        return switch(value){case "HIGHER"->"Số bí mật lớn hơn ↑";case "LOWER"->"Số bí mật nhỏ hơn ↓";case "EXACT"->"Chính xác!";case "TIMEOUT"->"Hết giờ · mất lượt";default->value;};
    }
    private void receive(Message m) {
        switch(m.type()) {
            case "LOGIN_OK" -> {username=m.get("username");matchId=0;ended=false;lobby();}
            case "LOGOUT_OK" -> {matchId=0;ended=false;login();}
            case "REGISTERED" -> loginError.setText(m.get("text"));
            case "ERROR" -> {if(m.get("request").equals("LOGIN")||m.get("request").equals("REGISTER"))loginError.setText(m.get("text"));else info(m.get("text"));}
            case "INFO","INVITE_SENT" -> info(m.get("text"));
            case "USERS" -> onlineData.setAll(m.rows().stream().filter(r->!r.get("username").equals(username)).map(r->new Player(r.get("username"),r.get("wins"),r.get("state"))).toList());
            case "RANKING" -> {if(rankingTable!=null)rankingTable.getItems().setAll(m.rows().stream().map(r->new Player(r.get("username"),r.get("wins"),r.get("rate")+"%")).toList());}
            case "HISTORY" -> {
                matchIds.clear();if(matchesTable!=null){matchesTable.getItems().clear();for(var r:m.rows()){
                    GuessRow row=new GuessRow(r.get("started_at"),r.get("opponent"),"1–"+r.get("range_max"),r.get("result"));
                    matchIds.put(row,r.get("id"));matchesTable.getItems().add(row);
                }}
            }
            case "DETAILS" -> {StringBuilder b=new StringBuilder();for(var r:m.rows())b.append(r.get("turn_no")).append(" · ").append(r.get("username")).append(" · ").append(r.get("number")).append(" · ").append(hint(r.get("hint"))).append('\n');info(b.length()==0?"Chưa có lượt đoán.":b.toString());}
            case "PROFILE" -> showProfile(m);
            case "INVITED" -> {
                activeInviteId=m.get("id");activeInvite=dialog(m.get("rematch").equals("true")?"Yêu cầu chơi lại":"Lời mời thi đấu",box(new Label(m.get("opponent")+" mời bạn chơi trong phạm vi 1–"+m.get("range"))));
                Dialog<ButtonType> d=activeInvite;String id=activeInviteId;
                d.setOnHidden(e->{if(id.equals(activeInviteId)){activeInvite=null;activeInviteId=null;send(Message.of("INVITE_REPLY","id",id,"accept",""+(d.getResult()==ButtonType.OK)));}});d.show();
            }
            case "INVITE_CANCELLED" -> {if(m.get("id").equals(activeInviteId)){activeInviteId=null;if(activeInvite!=null)activeInvite.close();activeInvite=null;}info("Lời mời đã bị hủy hoặc hết hạn.");}
            case "MATCH_CREATED" -> {if(resultDialog!=null){resultDialog.setOnHidden(null);resultDialog.close();resultDialog=null;}matchId=m.number("match");range=(int)m.number("range");opponent=m.get("opponent");returningToLobby=false;game();chooseSecret();}
            case "SECRET_OK" -> status.setText("Đã gửi số bí mật · đang chờ đối thủ");
            case "TURN" -> {
                if(m.number("match")!=matchId||ended)return;turnCount=(int)m.number("turn");boolean mine=m.get("player").equals(username);
                status.setText(mine?"Đến lượt bạn":"Đang chờ đối thủ");guess.setDisable(!mine);guessButton.setDisable(!mine);
                stopTimer();displayDeadline=System.nanoTime()+m.number("remainingMillis")*1000000;
                Runnable update=()->{seconds=(int)Math.max(0,(displayDeadline-System.nanoTime()+999999999)/1000000000);clock.setText(seconds+" giây");};update.run();
                timer=new Timeline(new KeyFrame(Duration.millis(100),e->update.run()));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
            }
            case "GUESS_RESULT" -> {if(m.number("match")==matchId){String text=hint(m.get("hint"));history.getItems().add(new GuessRow(m.get("turn"),m.get("player"),m.get("number"),text));gameHint.setText(text);}}
            case "CHAT" -> {if(m.number("match")==matchId)chatArea.appendText(m.get("player")+": "+m.get("text")+"\n");}
            case "MATCH_END" -> {if(m.number("match")==matchId){ended=true;if(secretDialog!=null)secretDialog.close();finish(m.get("winner").equals(username),m.get("reason"));}}
            case "LEFT" -> {matchId=0;ended=false;lobby();}
            default -> { }
        }
    }
    private void showProfile(Message m) {
        TextField display=new TextField(m.get("displayName"));
        PasswordField oldPassword=new PasswordField();oldPassword.setPromptText("Mật khẩu hiện tại (nếu đổi mật khẩu)");
        PasswordField newPassword=new PasswordField();newPassword.setPromptText("Mật khẩu mới (có thể bỏ trống)");
        Dialog<ButtonType> d=dialog("Thành tích cá nhân",box(new Label("Tổng: "+m.get("total")+" · Thắng: "+m.get("wins")+" · Thua: "+m.get("losses")+" · Tỷ lệ: "+m.get("rate")+"%"),display,oldPassword,newPassword));
        d.showAndWait().filter(ButtonType.OK::equals).ifPresent(v->send(Message.of("PROFILE_UPDATE","displayName",display.getText(),"oldPassword",oldPassword.getText(),"password",newPassword.getText())));
    }
    private void stopTimer(){if(timer!=null){timer.stop();timer=null;}}
    public static void main(String[] args){launch(args);}
}

