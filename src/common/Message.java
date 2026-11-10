package common;

import java.io.*;
import java.util.*;

// Thông điệp dùng chung qua TCP; phản hồi không chứa mật khẩu băm hoặc số bí mật.
public record Message(String type, HashMap<String, String> data, ArrayList<HashMap<String, String>> rows) implements Serializable {
    private static final long serialVersionUID = 1L;

    public static Message of(String type, String... pairs) {
        HashMap<String, String> data = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) data.put(pairs[i], pairs[i + 1]);
        return new Message(type, data, new ArrayList<>());
    }

    public static Message rows(String type, List<HashMap<String, String>> rows) {
        return new Message(type, new HashMap<>(), new ArrayList<>(rows));
    }

    public String get(String key) {
        return data.getOrDefault(key, "");
    }

    public long number(String key) {
        return Long.parseLong(get(key));
    }

    public static ObjectInputStream input(InputStream stream) throws IOException {
        ObjectInputStream input = new ObjectInputStream(stream);
        input.setObjectInputFilter(info -> {
            if (info.depth() > 12 || info.arrayLength() > 20000) return ObjectInputFilter.Status.REJECTED;
            Class<?> c = info.serialClass();
            if (c == null) return ObjectInputFilter.Status.UNDECIDED;
            if (c.isArray()) c = c.getComponentType();
            return c.isPrimitive() || c == String.class || c == Message.class
                    || c == HashMap.class || c == ArrayList.class
                    || c == Object.class || c == Map.Entry.class
                    ? ObjectInputFilter.Status.ALLOWED
                    : ObjectInputFilter.Status.REJECTED;
        });
        return input;
    }
}
