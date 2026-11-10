package client;

import common.Message;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import client.ClientApp.OnlinePlayer;
import client.ClientApp.RankingRow;
import client.ClientApp.MatchHistoryRow;

// Dựng sảnh và các bảng thông tin người chơi.
final class LobbyScreen {
    private final ClientApp client;
    private final String username;
    private final ObservableList<OnlinePlayer> playerData;
    private final ObservableList<RankingRow> rankingData;
    private final ObservableList<MatchHistoryRow> matchData;
    ScreenLayout root;
    Label statTotal;
    Label statRate;

    LobbyScreen(ClientApp client, String username, ObservableList<OnlinePlayer> playerData, ObservableList<RankingRow> rankingData,
        ObservableList<MatchHistoryRow> matchData) {
        this.client = client;
        this.username = username;
        this.playerData = playerData;
        this.rankingData = rankingData;
        this.matchData = matchData;
        build();
    }

    private void build() {
        TableView<OnlinePlayer> players = new TableView<>();
        players.getColumns().add(client.column("Tên", OnlinePlayer::name));
        players.getColumns().add(client.column("Trận thắng", OnlinePlayer::wins));
        players.getColumns().add(client.column("Trạng thái", OnlinePlayer::state));
        players.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        var data = new javafx.collections.transformation.FilteredList<>(playerData, player -> true);
        players.setItems(data);
        players.setPlaceholder(new Label("Chưa có người chơi khác online"));
        TextField search = new TextField();
        search.setPromptText("Tìm kiếm theo tên");
        CheckBox available = new CheckBox("Chỉ người đang rảnh");
        Runnable filter = () -> data.setPredicate(p ->
            p.name().toLowerCase(java.util.Locale.ROOT).contains(search.getText().trim().toLowerCase(java.util.Locale.ROOT))
                && (!available.isSelected() || p.state().equals("Rảnh")));
        available.setOnAction(e -> filter.run());
        search.setOnAction(e -> filter.run());
        search.setPrefWidth(220);
        search.setId("player-search");
        search.textProperty().addListener((o, a, b) -> filter.run());
        HBox tools = new HBox(12, search, available);
        tools.setAlignment(Pos.CENTER_LEFT);
        Runnable invite = () -> {
            OnlinePlayer p = players.getSelectionModel().getSelectedItem();
            if (p == null) {
                client.info("Chọn một người trong bảng để mời đấu.");
                return;
            }
            if (!p.state().equals("Rảnh")) {
                client.info("Người chơi này đang chơi.");
                return;
            }
            client.invitePlayer(p);
        };
        players.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                invite.run();
                e.consume();
            }
        });
        Button challenge = client.button("Mời đấu", invite, false);
        challenge.setId("invite-player");
        ScreenLayout.skin(challenge, "invite-button.png", 180, 48);
        challenge.setDisable(true);
        players.getSelectionModel().selectedItemProperty().addListener(
            (o, a, b) -> challenge.setDisable(b == null || !b.state().equals("Rảnh")));
        HBox inviteBar = client.row(challenge);
        VBox list = new VBox(8, tools, players, inviteBar);
        list.setId("player-list");
        VBox.setVgrow(players, Priority.ALWAYS);
        TableView<RankingRow> ranking = new TableView<>();
        ranking.setTranslateY(-10);
        ranking.getColumns().add(client.column("Tên", RankingRow::name));
        ranking.getColumns().add(client.column("Trận thắng", RankingRow::wins));
        ranking.getColumns().add(client.column("Tỷ lệ thắng", RankingRow::winRate));
        ranking.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        ranking.setItems(rankingData);
        ranking.setSelectionModel(null);
        ranking.setPlaceholder(new Region());
        VBox matches = new VBox(8);
        matches.setTranslateY(-10);
        TableView<MatchHistoryRow> matchTable = new TableView<>();
        matchTable.setId("match-history-table");
        matchTable.getColumns().add(client.column("Ngày", MatchHistoryRow::date));
        matchTable.getColumns().add(client.column("Đối thủ", MatchHistoryRow::opponent));
        matchTable.getColumns().add(client.column("Phạm vi", MatchHistoryRow::range));
        matchTable.getColumns().add(client.column("Kết quả", MatchHistoryRow::result));
        matchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        matchTable.setItems(matchData);
        matchTable.setSelectionModel(null);
        matchTable.setPlaceholder(new Region());
        matchTable.setRowFactory(table -> {
            TableRow<MatchHistoryRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    client.send(Message.of("DETAILS", "match", row.getItem().matchId()));
                }
            });
            return row;
        });
        matches.getChildren().add(matchTable);
        VBox.setVgrow(matchTable, Priority.ALWAYS);
        root = new ScreenLayout();
        root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (!(e.getTarget() instanceof javafx.scene.Node target)) return;
            javafx.scene.Node node = target;
            while (node != null && node != players && node != challenge) node = node.getParent();
            if (node == null) players.getSelectionModel().clearSelection();
        });
        root.art("dau-so-logo.png", 40, 20, 280, 100);
        root.profileBar(600, 30, 260, 72);
        Label name = client.label(username, "screen-label");
        name.setAlignment(Pos.CENTER);
        name.setTooltip(new Tooltip(username));
        root.put(name, 674, 52, 151, 28);
        Button profile = client.button("", client::editProfile);
        profile.setId("edit-profile");
        profile.setAccessibleText("Đổi tên hiển thị");
        profile.setTooltip(new Tooltip("Đổi tên hiển thị"));
        profile.getStyleClass().add("profile-hit");
        root.put(profile, 600, 30, 260, 72);
        Button sound = client.soundButton();
        root.put(sound, 980, 42, 48, 48);
        root.imageButton(
            client.button("Đăng xuất", () -> client.send(Message.of("LOGOUT"))), "logout-button-gold.png", 1040.5, 42.5, 47, 47);
        javafx.scene.Group[] panels = new javafx.scene.Group[4];
        String[] files = {"lobby-players.png", "lobby-history.png", "lobby-ranking.png", "lobby-achievement.png"};
        for (int i = 0; i < 4; i++) {
            // Phần vương miện của bảng xếp hạng nhô lên trên mép khung chung.
            panels[i] = root.pagePanel(files[i], 310, i == 2 ? 100 : 130, 750, i == 2 ? 470 : 440);
            panels[i].setVisible(false);
        }
        StackPane body = new StackPane();
        body.getStyleClass().add("screen-content");
        root.put(body, 366, 245, 638, 280);
        statTotal = client.label("Đang tải thành tích…", "screen-body");
        statRate = client.label("", "screen-label");
        VBox stats = new VBox(18, client.label("Người chơi: " + username, "screen-label"), statTotal, statRate);
        stats.setPadding(new Insets(18));
        javafx.scene.Node[] pages = {list, matches, ranking, stats};
        String[] titles = {"NGƯỜI CHƠI", "LỊCH SỬ ĐẤU", "XẾP HẠNG", "THÀNH TÍCH"};
        String[] images = {"nav-players.png", "nav-history.png", "nav-ranking.png", "nav-achievement.png"};
        Button[] navigation = new Button[4];
        java.util.function.IntConsumer selectPage = index -> {
            if (index != 0) players.getSelectionModel().clearSelection();
            body.getChildren().setAll(pages[index]);
            for (int j = 0; j < panels.length; j++) panels[j].setVisible(j == index);
            for (int j = 0; j < 4; j++)
                navigation[j].pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), j == index);
        };
        for (int i = 0; i < 4; i++) {
            final int index = i;
            navigation[i] = client.button(titles[i], () -> {
                selectPage.accept(index);
                client.send(Message.of(index == 1 ? "HISTORY" : index == 2 ? "RANKING" : "PROFILE"));
            });
            navigation[i].setId("nav-" + i);
            root.imageButton(navigation[i], images[i], 40, 155 + i * 80, 240, 72);
        }
        selectPage.accept(0);
    }
}
