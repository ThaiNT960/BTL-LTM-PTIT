package server;

import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

// Đọc cấu hình server từ tệp bên ngoài JAR.
final class ServerConfig {
    static Properties load() throws Exception {
        Properties settings = new Properties();
        Path file = Path.of("config/server.properties");
        if (!Files.isRegularFile(file))
            throw new IllegalStateException(
                    "Thiếu "
                            + file
                            + ". Kiểm tra tệp cấu hình MySQL trong thư mục config.");
        try (InputStream input = Files.newInputStream(file)) {
            settings.load(input);
        }
        return settings;
    }
}
