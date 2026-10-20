package server;

import common.Message;

import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

// Máy chủ TCP: quản lý người online, lời mời và điều phối phòng đấu.
public final class Server implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Server.class.getName());
    private final DatabaseManager db;
    private final ServerSocket listener;
    private final Map<String, ClientHandler> online = new HashMap<>();
    private final Set<ClientHandler> connections = new HashSet<>();
    private final Map<String, Invitation> invitations = new HashMap<>();
    private final Map<Long, GameRoom> rooms = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom random = new SecureRandom();
    private final long turnMillis;
    private volatile boolean closing;

    private record Invitation(
            String id, ClientHandler from, ClientHandler to, int range, long expires) {}

    public Server(DatabaseManager db, int port, long turnMillis) throws Exception {
        this.db = db;
        this.turnMillis = turnMillis;
        listener = new ServerSocket(port);
        scheduler.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    public int port() {
        return listener.getLocalPort();
    }

    // Nhận kết nối và tạo luồng xử lý riêng cho từng client.
    public void listen() throws Exception {
        while (!closing) {
            Socket socket = listener.accept();
            socket.setTcpNoDelay(true);
            ClientHandler client = new ClientHandler(socket, this);
            synchronized (this) {
                if (connections.size() >= 200) {
                    socket.close();
                    continue;
                }
                connections.add(client);
            }
            Thread t = new Thread(client, "client-" + socket.getPort());
            t.setDaemon(true);
            t.start();
        }
    }

    // Phân loại yêu cầu; chỉ cho phép thao tác trò chơi sau khi đăng nhập.
    synchronized void receive(ClientHandler c, Message m) {
        if (closing || c.closed.get()) return;
        try {
            switch (m.type()) {
                case "REGISTER" -> {
                    require(c.user == null, "Bạn đã đăng nhập.");
                    String name = m.get("username").trim();
                    validateName(name);
                    validatePassword(m.get("password"));
                    require(db.find(name) == null, "Tên tài khoản đã tồn tại.");
                    db.register(name, Passwords.hash(m.get("password")));
                    c.send(Message.of("REGISTERED", "text", "Đăng ký thành công."));
                }
                case "LOGIN" -> {
                    require(c.user == null, "Bạn đã đăng nhập.");
                    var u = db.find(m.get("username").trim());
                    require(
                            u != null && Passwords.verify(m.get("password"), u.hash()),
                            "Sai tài khoản hoặc mật khẩu.");

                    require(
                            !online.containsKey(u.username()),
                            "Tài khoản đang đăng nhập ở nơi khác.");
                    c.user = u;
                    online.put(u.username(), c);
                    c.send(
                            Message.of(
                                    "LOGIN_OK",
                                    "username",
                                    u.username(),
                                    "displayName",
                                    u.displayName()));
                    lobby(c);
                }
                default -> {
                    require(c.user != null, "Bạn cần đăng nhập.");
                    authenticated(c, m);
                }
            }
        } catch (IllegalArgumentException ex) {
            c.send(Message.of("ERROR", "request", m.type(), "text", ex.getMessage()));
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Không xử lý được yêu cầu: " + m.type(), ex);
            c.send(
                    Message.of(
                            "ERROR",
                            "request",
                            m.type(),
                            "text",
                            "Không thể xử lý yêu cầu hoặc lưu dữ liệu. Hãy thử lại."));
        }
    }

    private void authenticated(ClientHandler c, Message m) throws Exception {
        switch (m.type()) {
            case "LOBBY" -> {
                require(c.room == null, "Bạn vẫn đang trong trận.");
                cancelInvitations(c);
                c.lastMatch = 0;
                c.send(Message.of("LOBBY_OK"));
                lobby(c);
            }
            case "LOGOUT" -> {
                leave(c, "QUIT");
                require(c.room == null, "Đang lưu kết quả trận đấu, hãy thử lại.");
                cancelInvitations(c);
                online.remove(c.user.username());
                c.user = null;
                c.lastMatch = 0;
                c.send(Message.of("LOGOUT_OK"));
                broadcastUsers();
            }
            case "PROFILE" -> {
                var u = db.find(c.user.username());
                var stats =
                        db.ranking().stream()
                                .filter(r -> r.get("username").equals(u.username()))
                                .findFirst()
                                .orElse(new HashMap<>());
                Message result = Message.of("PROFILE", "displayName", u.displayName());
                result.data().putAll(stats);
                c.send(result);
            }
            case "PROFILE_UPDATE" -> {
                String name = m.get("displayName").trim();
                require(
                        !name.isEmpty()
                                && name.codePointCount(0, name.length()) <= 20
                                && name.codePoints().noneMatch(Character::isISOControl),
                        "Tên hiển thị dài từ 1 đến 20 ký tự.");
                db.profile(c.user.id(), name);
                c.user = db.find(c.user.username());
                c.send(Message.of("PROFILE_UPDATED", "displayName", name));
                broadcastUsers();
            }
            case "INVITE", "REMATCH" -> invite(c, m);
            case "INVITE_REPLY" -> reply(c, m);
            case "SECRET" -> {
                GameRoom r = room(c, m);
                int n = number(m, r.range);
                r.chooseSecret(c, n);
                c.send(Message.of("SECRET_OK"));
                if (r.ready()) {
                    r.current = random.nextBoolean() ? r.a : r.b;
                    startTurn(r);
                }
            }
            case "GUESS" -> {
                GameRoom r = room(c, m);
                require(r.current == c, "Chưa đến lượt bạn.");
                require(m.number("turn") == r.turn, "Lượt đoán đã hết hiệu lực.");
                if (System.nanoTime() >= r.deadlineNanos) {
                    timeout(r);
                    throw new IllegalArgumentException("Đã hết thời gian của lượt này.");
                }
                int n = number(m, r.range);
                String hint = r.checkGuess(c, m.number("turn"), n);
                db.guess(r.id, r.turn, c.user.id(), n, hint);
                // Khóa trận đoán đúng trước khi thông báo, tránh xử thua do mất kết nối.
                if (hint.equals("EXACT")) r.ended = true;
                both(
                        r,
                        Message.of(
                                "GUESS_RESULT",
                                "match",
                                "" + r.id,
                                "turn",
                                "" + r.turn,
                                "player",
                                c.user.username(),
                                "displayName",
                                c.user.displayName(),
                                "number",
                                "" + n,
                                "hint",
                                hint));
                if (hint.equals("EXACT")) complete(r, c, "EXACT");
                else {
                    r.current = r.other(c);
                    startTurn(r);
                }
            }
            case "CHAT" -> {
                GameRoom r = room(c, m);
                String text = m.get("text").trim();
                require(
                        !text.isEmpty() && text.length() <= 1000,
                        "Tin nhắn dài từ 1 đến 1000 ký tự.");
                db.chat(r.id, c.user.id(), text);
                both(
                        r,
                        Message.of(
                                "CHAT",
                                "match",
                                "" + r.id,
                                "player",
                                c.user.username(),
                                "displayName",
                                c.user.displayName(),
                                "text",
                                text));
            }
            case "LEAVE" -> {
                leave(c, "QUIT");
                require(c.room == null, "Đang lưu kết quả trận đấu, hãy thử lại.");
                cancelInvitations(c);
                c.lastMatch = 0;
                c.send(Message.of("LEFT"));
                lobby(c);
            }
            case "HISTORY" -> c.send(Message.rows("HISTORY", db.history(c.user.id())));
            case "DETAILS" ->
                    c.send(Message.rows("DETAILS", db.details(m.number("match"), c.user.id())));
            case "RANKING" -> c.send(Message.rows("RANKING", db.ranking()));
            case "INVITE_CANCEL" -> cancelInvitations(c);
            default -> throw new IllegalArgumentException("Lệnh không hợp lệ.");
        }
    }

    // Gửi lời mời kèm phạm vi do người mời chọn.
    private void invite(ClientHandler c, Message m) throws Exception {
        require(c.room == null, "Bạn đang chơi.");
        ClientHandler to = online.get(m.get("opponent"));
        require(
                to != null && to != c && !to.closed.get() && to.room == null,
                "Đối thủ không sẵn sàng.");
        long rawRange = m.number("range");
        require(rawRange == 100 || rawRange == 1000 || rawRange == 10000, "Phạm vi không hợp lệ.");
        int range = (int) rawRange;
        long match = 0;
        if (m.type().equals("REMATCH")) {
            match = m.number("match");
            require(
                    match > 0
                            && c.lastMatch == match
                            && to.lastMatch == match
                            && c.lastOpponent.equals(to.user.username()),
                    "Đối thủ đã rời phòng hoặc trận không hợp lệ.");
        }
        require(
                invitations.values().stream()
                        .noneMatch(i -> i.from == c || i.to == c || i.from == to || i.to == to),
                "Một người đang có lời mời chờ phản hồi.");
        String id = UUID.randomUUID().toString();
        invitations.put(
                id,
                new Invitation(
                        id, c, to, range, System.nanoTime() + TimeUnit.SECONDS.toNanos(60)));
        to.send(
                Message.of(
                        "INVITED",
                        "id",
                        id,
                        "opponent",
                        c.user.username(),
                        "opponentName",
                        c.user.displayName(),
                        "range",
                        "" + range,
                        "rematch",
                        "" + (match > 0)));
        c.send(
                Message.of(
                        "INVITE_SENT",
                        "id",
                        id,
                        "text",
                        "Đã gửi yêu cầu tới " + to.user.username()));
    }

    // Kiểm tra lời mời còn hiệu lực trước khi tạo phòng đấu.
    private void reply(ClientHandler c, Message m) throws Exception {
        Invitation i = invitations.get(m.get("id"));
        require(i != null && i.to == c, "Lời mời không còn hiệu lực.");
        require(System.nanoTime() < i.expires, "Lời mời đã hết hạn.");
        if (!m.get("accept").equals("true")) {
            invitations.remove(i.id);
            i.from.send(
                    Message.of("INVITE_DECLINED", "id", i.id, "text", "Đối thủ từ chối lời mời."));
            c.send(Message.of("INVITE_DECLINED", "id", i.id, "text", "Bạn đã từ chối lời mời."));
            return;
        }
        require(
                i.from.user != null
                        && !i.from.closed.get()
                        && i.from.room == null
                        && c.room == null,
                "Người chơi không còn sẵn sàng.");
        long id = db.createMatch(i.from.user.id(), c.user.id(), i.range);
        GameRoom r = new GameRoom(id, i.from, c, i.range);
        rooms.put(id, r);
        i.from.room = r;
        c.room = r;
        invitations.remove(i.id);
        cancelInvitations(c);
        cancelInvitations(i.from);
        for (ClientHandler p : List.of(r.a, r.b))
            p.send(
                    Message.of(
                            "MATCH_CREATED",
                            "match",
                            "" + id,
                            "range",
                            "" + r.range,
                            "opponent",
                            r.other(p).user.username(),
                            "opponentName",
                            r.other(p).user.displayName()));
        broadcastUsers();
    }

    private GameRoom room(ClientHandler c, Message m) {
        require(
                c.room != null && !c.room.ended && c.room.id == m.number("match"),
                "Trận đấu không còn hoạt động.");
        return c.room;
    }

    private int number(Message m, int range) {
        long n = m.number("number");
        require(n >= 1 && n <= range, "Nhập số nguyên từ 1 đến " + range);
        return (int) n;
    }

    // Bắt đầu lượt 30 giây; server giữ thời hạn chính thức.
    private void startTurn(GameRoom r) {
        r.beginTurn(turnMillis);
        both(
                r,
                Message.of(
                        "TURN",
                        "match",
                        "" + r.id,
                        "turn",
                        "" + r.turn,
                        "player",
                        r.current.user.username(),
                        "remainingMillis",
                        "" + turnMillis));
    }

    // Lưu lượt hết giờ và tự chuyển lượt cho đối thủ.
    private void timeout(GameRoom r) throws Exception {
        db.guess(r.id, r.turn, r.current.user.id(), null, "TIMEOUT");
        both(
                r,
                Message.of(
                        "GUESS_RESULT",
                        "match",
                        "" + r.id,
                        "turn",
                        "" + r.turn,
                        "player",
                        r.current.user.username(),
                        "number",
                        "—",
                        "hint",
                        "TIMEOUT"));
        r.current = r.other(r.current);
        startTurn(r);
    }

    // Lưu kết quả một lần, gửi cho hai người và giải phóng phòng.
    private void complete(GameRoom r, ClientHandler winner, String reason) throws Exception {
        r.ended = true;
        boolean saved = true;
        try {
            db.finish(r.id, winner.user.id(), reason);
        } catch (java.sql.SQLException ex) {
            saved = false;
            LOG.log(Level.SEVERE, "Không lưu được kết quả trận " + r.id, ex);
        }
        both(
                r,
                Message.of(
                        "MATCH_END",
                        "match",
                        "" + r.id,
                        "winner",
                        winner.user.username(),
                        "reason",
                        reason));
        if (!saved)
            both(r, Message.of("INFO", "text", "Không lưu được kết quả trận đấu vào MySQL."));
        for (ClientHandler p : List.of(r.a, r.b)) {
            p.lastMatch = r.id;
            p.lastOpponent = r.other(p).user.username();
            p.room = null;
        }
        rooms.remove(r.id);
        broadcastUsers();
        LOG.info("Trận " + r.id + " đã kết thúc, lý do: " + reason);
    }

    private void leave(ClientHandler c, String reason) throws Exception {
        if (c.room != null && !c.room.ended) complete(c.room, c.room.other(c), reason);
    }

    private void both(GameRoom r, Message m) {
        r.a.send(m);
        r.b.send(m);
    }

    private void lobby(ClientHandler c) throws Exception {
        c.send(Message.rows("RANKING", db.ranking()));
        c.send(Message.rows("HISTORY", db.history(c.user.id())));
        broadcastUsers();
    }

    private void broadcastUsers() throws Exception {
        Map<String, String> wins = new HashMap<>();
        for (var row : db.ranking()) wins.put(row.get("username"), row.get("wins"));
        List<HashMap<String, String>> rows = new ArrayList<>();
        for (var c : online.values())
            if (!c.closed.get())
                rows.add(
                        Message.of(
                                        "",
                                        "username",
                                        c.user.username(),
                                        "displayName",
                                        c.user.displayName(),
                                        "wins",
                                        wins.getOrDefault(c.user.username(), "0"),
                                        "state",
                                        c.room == null ? "Rảnh" : "Đang chơi")
                                .data());
        rows.sort(Comparator.comparing(r -> r.get("username")));
        Message m = Message.rows("USERS", rows);
        for (var c : new ArrayList<>(online.values())) c.send(m);
    }

    private void cancelInvitations(ClientHandler c) {
        var expired = invitations.values().stream().filter(i -> i.from == c || i.to == c).toList();
        for (var i : expired) {
            invitations.remove(i.id);
            i.from.send(Message.of("INVITE_CANCELLED", "id", i.id));
            i.to.send(Message.of("INVITE_CANCELLED", "id", i.id));
        }
    }

    // Người mất kết nối bị xử thua nếu trận chưa kết thúc.
    synchronized void disconnected(ClientHandler c) {
        connections.remove(c);
        cancelInvitations(c);
        if (c.user != null) {
            try {
                leave(c, "DISCONNECT");
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Không thể kết thúc trận sau khi người chơi mất kết nối", ex);
            }
            online.remove(c.user.username(), c);
            try {
                broadcastUsers();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Không cập nhật được danh sách người chơi", ex);
            }
        }
    }

    // Kiểm tra hết giờ và lời mời hết hạn trên máy chủ.
    private synchronized void tick() {
        if (closing) return;
        try {
            for (var r : new ArrayList<>(rooms.values()))
                if (!r.ended && r.current != null && System.nanoTime() >= r.deadlineNanos)
                    timeout(r);
            for (var i : new ArrayList<>(invitations.values()))
                if (System.nanoTime() >= i.expires) cancelInvitations(i.from);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Tác vụ định kỳ gặp lỗi; sẽ kiểm tra lại ở lần tiếp theo", ex);
        }
    }

    static void require(boolean condition, String text) {
        if (!condition) throw new IllegalArgumentException(text);
    }

    private static void validateName(String name) {
        require(
                name.matches("[A-Za-z0-9_]{3,32}"),
                "Tên tài khoản gồm 3–32 chữ, số hoặc dấu gạch dưới.");
    }

    private static void validatePassword(String password) {
        require(
                password.length() >= 6 && password.length() <= 128,
                "Mật khẩu dài từ 6 đến 128 ký tự.");
    }

    public synchronized void close() {
        closing = true;
        scheduler.shutdownNow();
        try {
            listener.close();
        } catch (Exception ignored) {
        }
        for (var c : new ArrayList<>(connections)) {
            c.closed.set(true);
            try {
                c.socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Server server =
                new Server(
                        new DatabaseManager(),
                        Integer.parseInt(ServerConfig.load().getProperty("server.port", "12345")),
                        30000);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("NumberGuessingClient2 — máy chủ TCP, cổng " + server.port());
        try {
            server.listen();
        } catch (java.net.SocketException ex) {
            if (!server.closing) throw ex;
        }
    }
}
