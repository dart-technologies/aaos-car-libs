/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.harness;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal JSON writing for the harness; the test classpath has no JSON library. */
final class HarnessJson {
    private HarnessJson() {
    }

    @SuppressWarnings("unchecked")
    static String value(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof String) return quote((String) value);
        StringBuilder out = new StringBuilder();
        if (value instanceof List) {
            out.append('[');
            for (Object item : (List<Object>) value) {
                out.append(out.length() == 1 ? "" : ",").append(value(item));
            }
            return out.append(']').toString();
        }
        out.append('{');
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
            out.append(out.length() == 1 ? "" : ",").append(quote(entry.getKey())).append(':')
                    .append(value(entry.getValue()));
        }
        return out.append('}').toString();
    }

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
