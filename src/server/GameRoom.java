package server;

// Luật và trạng thái một trận; các phương thức được gọi dưới khóa của Server.
final class GameRoom {
    final long id;
    final ClientHandler a, b;
    final int range;
    Integer secretA, secretB;
    ClientHandler current;
    int turn;
    long deadlineNanos;
    boolean ended;

    GameRoom(long id, ClientHandler a, ClientHandler b, int range) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.range = range;
    }

    ClientHandler other(ClientHandler c) {
        if (c == a) return b;
        if (c == b) return a;
        throw new IllegalArgumentException("Không thuộc trận.");
    }

    // Lưu số bí mật ở server, mỗi người chỉ được chọn một lần trong một ván.
    void chooseSecret(ClientHandler player, int number) {
        Server.require(current == null && !ended, "Trận đã bắt đầu hoặc kết thúc.");
        validateNumber(number);
        if (player == a) {
            Server.require(secretA == null, "Đã chọn số bí mật.");
            secretA = number;
        } else if (player == b) {
            Server.require(secretB == null, "Đã chọn số bí mật.");
            secretB = number;
        } else throw new IllegalArgumentException("Không thuộc trận.");
    }

    boolean ready() {
        return secretA != null && secretB != null;
    }

    // Kiểm tra lượt trước khi so sánh số đoán với số bí mật của đối phương.
    String checkGuess(ClientHandler player, long submittedTurn, int number) {
        Server.require(!ended && current == player, "Chưa đến lượt bạn.");
        Server.require(submittedTurn == turn, "Lượt đoán đã hết hiệu lực.");
        validateNumber(number);
        int target = player == a ? secretB : secretA;
        if (number == target) return "EXACT";
        return number < target ? "HIGHER" : "LOWER";
    }

    void beginTurn(long durationMillis) {
        turn++;
        deadlineNanos =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(durationMillis);
    }

    private void validateNumber(int number) {
        Server.require(number >= 1 && number <= range, "Nhập số nguyên từ 1 đến " + range);
    }
}
