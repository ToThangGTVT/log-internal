package com.utc.blog.log.sdk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bộ ghi JSON tối giản dùng nội bộ SDK, để không phải kéo theo Jackson/Gson.
 */
final class Json {

    private Json() {
    }

    // bỏ qua field null để body gọn, server đã có mặc định cho các field thiếu
    static String object(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            value(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    static String array(Collection<String> jsonItems) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : jsonItems) {
            if (!first) sb.append(',');
            first = false;
            sb.append(item);
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static void value(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            ((Map<Object, Object>) value).forEach((k, v) -> map.put(String.valueOf(k), v));
            sb.append(object(map));
        } else if (value instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Collection<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                value(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
