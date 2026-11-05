/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

final class AgentId {
    private AgentId() { }

    static boolean isCanonical(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_'
                    || c == ':' || c == '-')) return false;
        }
        return true;
    }
}
