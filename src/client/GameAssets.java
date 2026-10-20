
package client;

import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;

import java.util.*;

// Nạp ảnh giao diện và vùng cắt tương ứng.
final class GameAssets {
    private static final String FOLDER = "/assets/";
    private static final Properties bounds = readBounds();
    private static final Map<String, Image> images = new HashMap<>();

    private static Properties readBounds() {
        Properties result = new Properties();
        try (var input = GameAssets.class.getResourceAsStream(FOLDER + "bounds.properties")) {
            if (input == null) throw new IllegalStateException("Thiếu vùng cắt ảnh: " + FOLDER);
            result.load(input);
            return result;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Không đọc được vùng cắt ảnh", ex);
        }
    }

    static Image image(String name) {
        if (!bounds.containsKey(name)) throw new IllegalStateException("Thiếu vùng cắt: " + name);
        String file = FOLDER + name;
        return images.computeIfAbsent(
                file,
                path -> {
                    var url = GameAssets.class.getResource(path);
                    if (url == null) throw new IllegalStateException("Thiếu ảnh: " + path);
                    return new Image(url.toExternalForm());
                });
    }

    static Rectangle2D bounds(String name) {
        if (name.equals("background.png")) return null;
        String raw = bounds.getProperty(name);
        if (raw == null) throw new IllegalStateException("Thiếu vùng cắt: " + name);
        String[] values = raw.split(",");
        return new Rectangle2D(
                Double.parseDouble(values[0]),
                Double.parseDouble(values[1]),
                Double.parseDouble(values[2]),
                Double.parseDouble(values[3]));
    }
}
