/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiProtocolException;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, bounded decoder for the gateway's normalized A2UI response.
 *
 * <p>The limits are lower than any provider's own limits on purpose: the renderer, not a cloud
 * API, is the constrained party. Duplicate object keys, invalid UTF-8, malformed surrogate pairs,
 * non-finite numbers, unknown lifecycle shapes, and trailing bytes are rejected.
 */
public final class BoundedA2uiJsonDecoder {
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static final int DEFAULT_MAX_NODES = 1_000;
    public static final int DEFAULT_MAX_STRING_CHARS = 4_096;
    public static final int DEFAULT_MAX_MESSAGES = 64;

    private final int mMaxBytes;
    private final int mMaxDepth;
    private final int mMaxNodes;
    private final int mMaxStringChars;
    private final int mMaxMessages;

    public BoundedA2uiJsonDecoder() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES,
                DEFAULT_MAX_STRING_CHARS, DEFAULT_MAX_MESSAGES);
    }

    public BoundedA2uiJsonDecoder(int maxBytes, int maxDepth, int maxNodes,
            int maxStringChars, int maxMessages) {
        if (maxBytes <= 0 || maxDepth <= 0 || maxNodes <= 0
                || maxStringChars <= 0 || maxMessages <= 0) {
            throw new IllegalArgumentException("decoder limits must be positive");
        }
        mMaxBytes = maxBytes;
        mMaxDepth = maxDepth;
        mMaxNodes = maxNodes;
        mMaxStringChars = maxStringChars;
        mMaxMessages = maxMessages;
    }

    @NonNull
    public AgentGatewayEnvelope decode(@NonNull byte[] bytes) {
        Map<String, Object> root = parseRoot(bytes);
        requireOnly(root, "schema", "provider", "sessionId", "turnId", "source", "messages",
                "assistantText");
        requireExact(root, "schema", AgentGatewayEnvelope.SCHEMA);
        AgentProvider provider = provider(root);
        String sessionId = canonicalId(root, "sessionId");
        String turnId = canonicalId(root, "turnId");
        List<Object> wireMessages = array(root.get("messages"), "messages");
        if (wireMessages.size() > mMaxMessages) {
            throw malformed("response exceeds " + mMaxMessages + " messages");
        }
        List<A2uiMessage> messages = new ArrayList<>();
        for (int i = 0; i < wireMessages.size(); i++) {
            messages.add(message(object(wireMessages.get(i), "messages[" + i + "]")));
        }
        Object textValue = root.get("assistantText");
        String assistantText = textValue == null ? null : asString(textValue, "assistantText");
        String source = null;
        if (root.containsKey("source")) {
            source = string(root, "source");
            if (!AgentGatewayEnvelope.SOURCE_LIVE.equals(source)
                    && !AgentGatewayEnvelope.SOURCE_CACHE.equals(source)
                    && !AgentGatewayEnvelope.SOURCE_FALLBACK.equals(source)) {
                throw malformed("source must be live, cache or fallback");
            }
        }
        return new AgentGatewayEnvelope(provider, sessionId, turnId, messages, assistantText,
                source);
    }

    /** Decodes the exact acknowledgement shape returned for an action-result envelope. */
    @NonNull
    public AgentGatewayAcknowledgement decodeAcknowledgement(@NonNull byte[] bytes) {
        Map<String, Object> root = parseRoot(bytes);
        requireOnly(root, "schema", "ack", "provider", "sessionId", "turnId");
        requireExact(root, "schema", AgentGatewayEnvelope.SCHEMA);
        requireExact(root, "ack", "actionResult");
        return new AgentGatewayAcknowledgement(provider(root),
                canonicalId(root, "sessionId"), canonicalId(root, "turnId"));
    }

    private Map<String, Object> parseRoot(byte[] bytes) {
        if (bytes.length > mMaxBytes) {
            throw malformed("gateway response exceeds " + mMaxBytes + " bytes");
        }
        final String json;
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            json = chars.toString();
        } catch (CharacterCodingException badUtf8) {
            throw malformed("gateway response is not valid UTF-8");
        }
        Object rootValue = new Parser(json, mMaxDepth, mMaxNodes, mMaxStringChars).parse();
        return object(rootValue, "response");
    }

    private static AgentProvider provider(Map<String, Object> root) {
        AgentProvider provider = AgentProvider.fromWireName(string(root, "provider"));
        if (provider == null) {
            throw malformed("unsupported provider");
        }
        return provider;
    }

    private static A2uiMessage message(Map<String, Object> wire) {
        requireOnly(wire, "version", "createSurface", "updateComponents", "updateDataModel",
                "deleteSurface");
        String version = string(wire, "version");
        int kinds = present(wire, "createSurface") + present(wire, "updateComponents")
                + present(wire, "updateDataModel") + present(wire, "deleteSurface");
        if (kinds != 1) {
            throw malformed("message must contain exactly one lifecycle verb");
        }
        if (wire.containsKey("createSurface")) {
            Map<String, Object> body = object(wire.get("createSurface"), "createSurface");
            requireOnly(body, "surfaceId", "catalogId");
            return A2uiMessage.createSurface(version, canonicalId(body, "surfaceId"),
                    canonicalId(body, "catalogId"));
        }
        if (wire.containsKey("updateComponents")) {
            Map<String, Object> body = object(wire.get("updateComponents"), "updateComponents");
            requireOnly(body, "surfaceId", "components");
            List<Object> source = array(body.get("components"), "components");
            List<Map<String, Object>> components = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                components.add(object(source.get(i), "components[" + i + "]"));
            }
            return A2uiMessage.updateComponents(version, canonicalId(body, "surfaceId"), components);
        }
        if (wire.containsKey("updateDataModel")) {
            Map<String, Object> body = object(wire.get("updateDataModel"), "updateDataModel");
            requireOnly(body, "surfaceId", "path", "value");
            if (!body.containsKey("value")) {
                throw malformed("updateDataModel.value is required (null is allowed)");
            }
            return A2uiMessage.updateDataModel(version, canonicalId(body, "surfaceId"),
                    string(body, "path"), body.get("value"));
        }
        Map<String, Object> body = object(wire.get("deleteSurface"), "deleteSurface");
        requireOnly(body, "surfaceId");
        return A2uiMessage.deleteSurface(version, canonicalId(body, "surfaceId"));
    }

    private static int present(Map<String, Object> map, String key) {
        return map.containsKey(key) ? 1 : 0;
    }

    private static void requireOnly(Map<String, Object> map, String... allowed) {
        for (String key : map.keySet()) {
            boolean known = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) throw malformed("unknown field " + key);
        }
    }

    private static void requireExact(Map<String, Object> map, String key, String expected) {
        if (!expected.equals(string(map, key))) {
            throw malformed(key + " is not " + expected);
        }
    }

    private static String canonicalId(Map<String, Object> map, String key) {
        String value = string(map, key);
        if (!AgentId.isCanonical(value)) {
            throw malformed(key + " must match [A-Za-z0-9._:-]{1,256}");
        }
        return value;
    }

    private static String string(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            throw malformed(key + " is required");
        }
        return asString(map.get(key), key);
    }

    private static String asString(Object value, String label) {
        if (!(value instanceof String)) {
            throw malformed(label + " must be a string");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) {
            throw malformed(label + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List)) {
            throw malformed(label + " must be an array");
        }
        return (List<Object>) value;
    }

    private static A2uiProtocolException malformed(String message) {
        return new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED, message);
    }

    private static final class Parser {
        private final String input;
        private final int maxDepth;
        private final int maxNodes;
        private final int maxStringChars;
        private int offset;
        private int nodes;

        Parser(String input, int maxDepth, int maxNodes, int maxStringChars) {
            this.input = input;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxStringChars = maxStringChars;
        }

        Object parse() {
            Object value = value(1);
            whitespace();
            if (offset != input.length()) {
                throw malformed("trailing content at character " + offset);
            }
            return value;
        }

        private Object value(int depth) {
            whitespace();
            if (depth > maxDepth) {
                throw malformed("JSON exceeds depth " + maxDepth);
            }
            if (++nodes > maxNodes) {
                throw malformed("JSON exceeds " + maxNodes + " values");
            }
            if (offset >= input.length()) {
                throw malformed("unexpected end of JSON");
            }
            char c = input.charAt(offset);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw malformed("unexpected character at " + offset);
        }

        private Map<String, Object> object(int depth) {
            offset++;
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (offset >= input.length() || input.charAt(offset) != '"') {
                    throw malformed("object key must be a string at " + offset);
                }
                String key = string();
                whitespace();
                expect(':');
                Object value = value(depth + 1);
                boolean duplicate = result.containsKey(key);
                result.put(key, value);
                if (duplicate) {
                    throw malformed("duplicate object key " + key);
                }
                whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            offset++;
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth + 1));
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (offset < input.length()) {
                char c = input.charAt(offset++);
                if (c == '"') {
                    validateSurrogates(out);
                    return out.toString();
                }
                if (c < 0x20) throw malformed("unescaped control character in string");
                if (c == '\\') {
                    if (offset >= input.length()) throw malformed("unterminated escape");
                    char escape = input.charAt(offset++);
                    switch (escape) {
                        case '"': case '\\': case '/': out.append(escape); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u': out.append(unicode()); break;
                        default: throw malformed("invalid string escape");
                    }
                } else {
                    out.append(c);
                }
                if (out.length() > maxStringChars) {
                    throw malformed("string exceeds " + maxStringChars + " characters");
                }
            }
            throw malformed("unterminated string");
        }

        private char unicode() {
            if (offset + 4 > input.length()) throw malformed("short unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(offset++), 16);
                if (digit < 0) throw malformed("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private static void validateSurrogates(CharSequence value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                        throw malformed("unpaired high surrogate");
                    }
                } else if (Character.isLowSurrogate(c)) {
                    throw malformed("unpaired low surrogate");
                }
            }
        }

        private Object number() {
            int start = offset;
            if (take('-') && offset >= input.length()) throw malformed("incomplete number");
            if (take('0')) {
                if (offset < input.length() && Character.isDigit(input.charAt(offset))) {
                    throw malformed("leading zero in number");
                }
            } else {
                digits();
            }
            if (take('.')) digits();
            if (offset < input.length() && (input.charAt(offset) == 'e'
                    || input.charAt(offset) == 'E')) {
                offset++;
                if (offset < input.length() && (input.charAt(offset) == '+'
                        || input.charAt(offset) == '-')) offset++;
                digits();
            }
            if (offset - start > 128) throw malformed("number token is too long");
            try {
                return new BigDecimal(input.substring(start, offset));
            } catch (NumberFormatException invalid) {
                throw malformed("invalid number");
            }
        }

        private void digits() {
            int start = offset;
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) offset++;
            if (offset == start) throw malformed("expected digit at " + offset);
        }

        @Nullable
        private Object literal(String literal, @Nullable Object value) {
            if (!input.regionMatches(offset, literal, 0, literal.length())) {
                throw malformed("invalid literal at " + offset);
            }
            offset += literal.length();
            return value;
        }

        private void whitespace() {
            while (offset < input.length()) {
                char c = input.charAt(offset);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                offset++;
            }
        }

        private boolean take(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw malformed("expected " + expected + " at " + offset);
            }
        }
    }
}
