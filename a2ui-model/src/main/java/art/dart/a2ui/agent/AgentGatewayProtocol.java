/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/** Deterministic client-to-gateway envelopes; provider-specific payloads stay server-side. */
public final class AgentGatewayProtocol {
    private AgentGatewayProtocol() {
    }

    @NonNull
    public static byte[] encodeTurn(@NonNull AgentProvider provider, @NonNull String sessionId,
            @NonNull String turnId, @NonNull String inputText, int displayId,
            int activeRestrictions, boolean trustworthy) {
        return encodeTurn(provider, AgentExecutionProfile.REALTIME_LOW_LATENCY, sessionId, turnId,
                inputText, displayId, activeRestrictions, trustworthy);
    }

    /** Encodes a turn with a provider-neutral execution profile selected by an operator/host. */
    @NonNull
    public static byte[] encodeTurn(@NonNull AgentProvider provider,
            @NonNull AgentExecutionProfile executionProfile, @NonNull String sessionId,
            @NonNull String turnId, @NonNull String inputText, int displayId,
            int activeRestrictions, boolean trustworthy) {
        requireId(sessionId, "sessionId");
        requireId(turnId, "turnId");
        if (displayId < 0 || activeRestrictions < 0) {
            throw new IllegalArgumentException("display id and restriction mask must be non-negative");
        }
        if (inputText.length() > BoundedA2uiJsonDecoder.DEFAULT_MAX_STRING_CHARS) {
            throw new IllegalArgumentException("inputText exceeds client limit");
        }
        String json = "{\"schema\":\"" + AgentGatewayEnvelope.SCHEMA
                + "\",\"type\":\"turn\",\"provider\":" + quote(provider.wireName())
                + ",\"executionProfile\":" + quote(executionProfile.wireName())
                + ",\"sessionId\":" + quote(sessionId)
                + ",\"turnId\":" + quote(turnId)
                + ",\"inputText\":" + quote(inputText)
                + ",\"capabilities\":{\"versions\":[\"v0.9\"],\"catalogs\":[\"automotive\"]}"
                + ",\"display\":{\"id\":" + displayId
                + ",\"activeRestrictions\":" + activeRestrictions
                + ",\"trustworthy\":" + trustworthy + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    public static byte[] encodeActionResult(@NonNull AgentProvider provider,
            @NonNull String sessionId, @NonNull String turnId, @NonNull String surfaceId,
            @NonNull String componentId, @NonNull String actionId,
            @NonNull AgentActionOutcome outcome,
            @Nullable String resultText) {
        requireId(sessionId, "sessionId");
        requireId(turnId, "turnId");
        requireId(surfaceId, "surfaceId");
        requireId(componentId, "componentId");
        requireId(actionId, "actionId");
        String json = "{\"schema\":\"" + AgentGatewayEnvelope.SCHEMA
                + "\",\"type\":\"actionResult\",\"provider\":" + quote(provider.wireName())
                + ",\"sessionId\":" + quote(sessionId)
                + ",\"turnId\":" + quote(turnId)
                + ",\"surfaceId\":" + quote(surfaceId)
                + ",\"componentId\":" + quote(componentId)
                + ",\"actionId\":" + quote(actionId)
                + ",\"outcome\":" + quote(outcome.name())
                + (resultText == null ? "" : ",\"resultText\":" + quote(resultText)) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireId(String value, String name) {
        if (!AgentId.isCanonical(value)) {
            throw new IllegalArgumentException(name + " must match [A-Za-z0-9._:-]{1,256}");
        }
    }

    private static String quote(String value) {
        if (value.length() > BoundedA2uiJsonDecoder.DEFAULT_MAX_STRING_CHARS) {
            throw new IllegalArgumentException("string exceeds client limit");
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("string contains unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)))) {
                throw new IllegalArgumentException("string contains unpaired surrogate");
            }
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
