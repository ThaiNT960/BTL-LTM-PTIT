package server;

import java.sql.*;
import java.util.*;

// Chỉ server được phép truy cập MySQL. Mỗi thao tác tự đóng tài nguyên JDBC.
public final class DatabaseManager {
    public record Account(long id, String username, String displayName, String hash) {}

    private final String url;
    private final String user;
    private final String password;

    public DatabaseManager() throws Exception {
        Properties config = ServerConfig.load();
        url = config.getProperty("db.url");
        user = config.getProperty("db.user");
        password = config.getProperty("db.password", "");
        // Trận đang chơi khi server dừng bất ngờ không được tính thắng/thua.
        update(
                "UPDATE matches SET end_reason='SERVER_RESTART',"
                        + " ended_at=CURRENT_TIMESTAMP WHERE end_reason IS NULL");
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    // Gắn tham số vào PreparedStatement, không nối dữ liệu người dùng vào SQL.
    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private void update(String sql, Object... values) throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private List<HashMap<String, String>> query(String sql, Object... values) throws SQLException {
        List<HashMap<String, String>> rows = new ArrayList<>();
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    HashMap<String, String> row = new HashMap<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        row.put(
                                result.getMetaData().getColumnLabel(i),
                                Objects.toString(result.getObject(i), ""));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    // Lấy tài khoản để kiểm tra mật khẩu; không gửi password_hash về client.
    public Account find(String name) throws SQLException {
        var rows =
                query(
                        "SELECT id,username,display_name,password_hash FROM users WHERE username=?",
                        name);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new Account(
                Long.parseLong(row.get("id")),
                row.get("username"),
                row.get("display_name"),
                row.get("password_hash"));
    }

    // Đăng ký tài khoản; mật khẩu đã được băm PBKDF2 ở server.
    public void register(String name, String hash) throws SQLException {
        update(
                "INSERT INTO users(username,display_name,password_hash) VALUES(?,?,?)",
                name,
                name,
                hash);
    }

    // Cập nhật tên hiển thị của người chơi.
    public void profile(long id, String name) throws SQLException {
        update("UPDATE users SET display_name=? WHERE id=?", name, id);
    }

    // Tạo trận đấu và lấy ID do MySQL sinh ra.
    public long createMatch(long player1, long player2, int range) throws SQLException {
        String sql = "INSERT INTO matches(player1_id,player2_id,range_max) VALUES(?,?,?)";
        try (Connection connection = open();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, player1);
            statement.setLong(2, player2);
            statement.setInt(3, range);
            statement.executeUpdate();
            try (ResultSet result = statement.getGeneratedKeys()) {
                if (!result.next()) throw new SQLException("Không lấy được ID trận đấu.");
                return result.getLong(1);
            }
        }
    }

    // Lưu chi tiết một lượt đoán; number=null biểu thị lượt hết giờ.
    public void guess(long matchId, int turn, long playerId, Integer number, String hint)
            throws SQLException {
        String sql =
                "INSERT INTO guesses(match_id,turn_no,player_id,number,hint) VALUES(?,?,?,?,?)";
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, matchId);
            statement.setInt(2, turn);
            statement.setLong(3, playerId);
            if (number == null) statement.setNull(4, Types.INTEGER);
            else statement.setInt(4, number);
            statement.setString(5, hint);
            statement.executeUpdate();
        }
    }

    // Chỉ kết thúc trận ACTIVE để tránh cộng thành tích lặp lại.
    public void finish(long matchId, long winnerId, String reason) throws SQLException {
        update(
                "UPDATE matches SET"
                    + " winner_id=?,end_reason=?,ended_at=CURRENT_TIMESTAMP WHERE"
                    + " id=? AND end_reason IS NULL",
                winnerId,
                reason,
                matchId);
    }

    public void chat(long matchId, long senderId, String text) throws SQLException {
        update(
                "INSERT INTO chat_messages(match_id,sender_id,content) VALUES(?,?,?)",
                matchId,
                senderId,
                text);
    }

    // Xếp hạng theo số trận thắng, sau đó đến tỷ lệ thắng.
    public List<HashMap<String, String>> ranking() throws SQLException {
        return query(
                "SELECT u.id,u.username,u.display_name,COUNT(m.id)"
                    + " total,COALESCE(SUM(m.winner_id=u.id),0)"
                    + " wins,COUNT(m.id)-COALESCE(SUM(m.winner_id=u.id),0)"
                    + " losses,COALESCE(ROUND(100.0*SUM(m.winner_id=u.id)/NULLIF(COUNT(m.id),0),1),0)"
                    + " rate FROM users u LEFT JOIN matches m ON (m.player1_id=u.id OR"
                    + " m.player2_id=u.id) AND m.winner_id IS NOT NULL GROUP BY u.id ORDER BY wins"
                    + " DESC,rate DESC,u.username");
    }

    // Lấy lịch sử trận đấu của người đang đăng nhập.
    public List<HashMap<String, String>> history(long playerId) throws SQLException {
        return query(
                "SELECT m.id,m.started_at,m.range_max,m.end_reason,u.display_name"
                    + " opponent,IF(m.winner_id=?,'Thắng','Thua') result FROM matches m JOIN users"
                    + " u ON u.id=IF(m.player1_id=?,m.player2_id,m.player1_id) WHERE"
                    + " (m.player1_id=? OR m.player2_id=?) AND m.winner_id IS NOT NULL ORDER BY m.id"
                    + " DESC LIMIT 500",
                playerId,
                playerId,
                playerId,
                playerId);
    }

    // Lấy các lượt đoán theo match ID, chỉ cho người thuộc trận xem.
    public List<HashMap<String, String>> details(long matchId, long playerId) throws SQLException {
        if (query(
                        "SELECT id FROM matches WHERE id=? AND (player1_id=? OR player2_id=?)",
                        matchId,
                        playerId,
                        playerId)
                .isEmpty()) {
            throw new IllegalArgumentException("Bạn không thuộc trận đấu này.");
        }
        return query(
                "SELECT g.turn_no,u.display_name player,g.number,g.hint,g.created_at FROM guesses g"
                        + " JOIN users u ON u.id=g.player_id WHERE g.match_id=? ORDER BY g.turn_no",
                matchId);
    }

}
