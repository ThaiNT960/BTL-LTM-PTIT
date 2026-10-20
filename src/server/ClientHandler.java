package server;

import common.Message;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientHandler implements Runnable {
    final Socket socket;
    final Server server;
    final AtomicBoolean closed = new AtomicBoolean();
    private volatile ObjectOutputStream out;
    DatabaseManager.Account user;
    GameRoom room;
    long lastMatch;
    String lastOpponent = "";

    ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
    }

    void send(Message message) {
        ObjectOutputStream stream = out;
        if (closed.get() || stream == null) return;
        try {
            synchronized (stream) {
                stream.writeObject(message);
                stream.flush();
            }
        } catch (IOException ex) {
            close();
        }
    }

    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = Message.input(socket.getInputStream());
            while (!closed.get()) {
                Object obj = in.readObject();
                if (!(obj instanceof Message m)) throw new IOException("Thông điệp không hợp lệ");
                if (m.type() == null
                        || m.data() == null
                        || m.rows() == null
                        || m.data().size() > 20
                        || !m.rows().isEmpty()) throw new IOException("Dữ liệu thông điệp không hợp lệ");
                for (var e : m.data().entrySet())
                    if (e.getKey() == null || e.getValue() == null || e.getValue().length() > 4096)
                        throw new IOException("Trường dữ liệu không hợp lệ");
                server.receive(this, m);
            }
        } catch (Exception ex) {
            /* Đóng luồng hoặc hết thời gian chờ đọc được xem là mất kết nối. */
        } finally {
            close();
        }
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            server.disconnected(this);
        }
    }
}
