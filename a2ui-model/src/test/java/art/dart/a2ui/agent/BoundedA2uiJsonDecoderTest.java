/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiProtocolException;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Map;

public class BoundedA2uiJsonDecoderTest {
    @Test
    public void decodesAllLifecycleVerbsForEveryProvider() {
        for (AgentProvider provider : AgentProvider.values()) {
            String json = envelope(provider.wireName(), "["
                    + "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"s\",\"catalogId\":\"automotive\"}},"
                    + "{\"version\":\"v0.9\",\"updateComponents\":{\"surfaceId\":\"s\",\"components\":[{\"id\":\"root\",\"component\":\"Text\",\"title\":\"Hi\"}]}},"
                    + "{\"version\":\"v0.9\",\"updateDataModel\":{\"surfaceId\":\"s\",\"path\":\"/a\",\"value\":null}},"
                    + "{\"version\":\"v0.9\",\"deleteSurface\":{\"surfaceId\":\"s\"}}]");

            AgentGatewayEnvelope decoded = decoder().decode(bytes(json));

            assertThat(decoded.getProvider()).isEqualTo(provider);
            assertThat(decoded.getMessages()).hasSize(4);
            assertThat(decoded.getMessages().get(0).getKind())
                    .isEqualTo(A2uiMessage.Kind.CREATE_SURFACE);
            assertThat(decoded.getMessages().get(3).getKind())
                    .isEqualTo(A2uiMessage.Kind.DELETE_SURFACE);
        }
    }

    @Test
    public void rejectsDuplicateKeysEvenWhenFirstValueIsNull() {
        String json = "{\"schema\":null,\"schema\":\"a2ui.gateway.v1\","
                + "\"provider\":\"claude\",\"sessionId\":\"s\",\"turnId\":\"t\",\"messages\":[]}";
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(json)));
    }

    @Test
    public void rejectsInvalidUtf8AndUnpairedSurrogates() {
        assertThrows(A2uiProtocolException.class,
                () -> decoder().decode(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(
                envelope("claude", "[]").replace("[]", "[] ,\"assistantText\":\"\\ud800\""))));
    }

    @Test
    public void enforcesByteDepthNodeStringAndMessageLimits() {
        assertThrows(A2uiProtocolException.class,
                () -> new BoundedA2uiJsonDecoder(8, 64, 1000, 4096, 64)
                        .decode(bytes(envelope("claude", "[]"))));
        assertThrows(A2uiProtocolException.class,
                () -> new BoundedA2uiJsonDecoder(10000, 1, 1000, 4096, 64)
                        .decode(bytes(envelope("claude", "[]"))));
        assertThrows(A2uiProtocolException.class,
                () -> new BoundedA2uiJsonDecoder(10000, 64, 3, 4096, 64)
                        .decode(bytes(envelope("claude", "[]"))));
        assertThrows(A2uiProtocolException.class,
                () -> new BoundedA2uiJsonDecoder(10000, 64, 1000, 3, 64)
                        .decode(bytes(envelope("claude", "[]"))));
        assertThrows(A2uiProtocolException.class,
                () -> new BoundedA2uiJsonDecoder(10000, 64, 1000, 4096, 1)
                        .decode(bytes(envelope("claude", "[{},{}]"))));
    }

    @Test
    public void rejectsAmbiguousVerbAndUnknownProvider() {
        String both = "[{\"version\":\"v0.9\",\"createSurface\":{},\"deleteSurface\":{}}]";
        assertThrows(A2uiProtocolException.class,
                () -> decoder().decode(bytes(envelope("claude", both))));
        assertThrows(A2uiProtocolException.class,
                () -> decoder().decode(bytes(envelope("other", "[]"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void parsesJsonScalarsEscapesAndExponentWithoutLoss() {
        String value = "{\"quote\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\","
                + "\"unicode\":\"\\uD83D\\uDE97\",\"truth\":true,\"no\":false,"
                + "\"numbers\":[-2,0,1.25,3e2],\"empty\":{}}";
        String messages = "[{\"version\":\"v0.9\",\"updateDataModel\":{"
                + "\"surfaceId\":\"s\",\"path\":\"\",\"value\":" + value + "}}]";

        Object decodedValue = decoder().decode(bytes(envelope("claude", messages)))
                .getMessages().get(0).getValue();
        Map<String, Object> map = (Map<String, Object>) decodedValue;

        assertThat(map.get("unicode")).isEqualTo("🚗");
        assertThat(map.get("truth")).isEqualTo(true);
        assertThat(((java.util.List<Object>) map.get("numbers")).get(3))
                .isEqualTo(new BigDecimal("3e2"));
    }

    @Test
    public void rejectsMalformedJsonGrammarAndNumberForms() {
        String longNumber = "11111111111111111111111111111111111111111111111111111111111111111"
                + "11111111111111111111111111111111111111111111111111111111111111111";
        String[] invalid = {"", "x", "true false", "{x:1}", "{\"a\" 1}",
                "{\"a\":1 \"b\":2}", "[1 2]", "\"\\q\"", "\"\\u12\"",
                "\"\\uZZZZ\"", "\"\u0001\"", "01", "-", "1.", "1e", "truX",
                longNumber};
        for (String json : invalid) {
            assertThrows("accepted malformed JSON: " + json, A2uiProtocolException.class,
                    () -> decoder().decode(bytes(json)));
        }
    }

    @Test
    public void acknowledgementHasBoundedExactShape() {
        String ack = "{\"schema\":\"a2ui.gateway.v1\",\"ack\":\"actionResult\","
                + "\"provider\":\"gemini\",\"sessionId\":\"session\",\"turnId\":\"turn\"}";
        AgentGatewayAcknowledgement decoded = decoder().decodeAcknowledgement(bytes(ack));
        assertThat(decoded.getProvider()).isEqualTo(AgentProvider.GEMINI);
        assertThat(decoded.getSessionId()).isEqualTo("session");
        assertThat(decoded.getTurnId()).isEqualTo("turn");
        assertThrows(A2uiProtocolException.class, () -> decoder().decodeAcknowledgement(bytes(
                ack.replace("actionResult", "turn"))));
    }

    @Test
    public void rejectsUnknownEnvelopeAndLifecycleFields() {
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(
                envelope("claude", "[]").replace("\"messages\"", "\"extra\":1,\"messages\""))));
        String message = "[{\"version\":\"v0.9\",\"deleteSurface\":{"
                + "\"surfaceId\":\"s\",\"ignored\":true}}]";
        assertThrows(A2uiProtocolException.class,
                () -> decoder().decode(bytes(envelope("claude", message))));
    }

    @Test
    public void sourceIsOptionalAndClosed() {
        assertThat(decoder().decode(bytes(envelope("muse", "[]"))).getSource()).isNull();
        for (String source : new String[]{"live", "cache", "fallback"}) {
            assertThat(decoder().decode(bytes(envelope("muse", "[]").replace(
                    "\"messages\"", "\"source\":\"" + source + "\",\"messages\""))).getSource())
                    .isEqualTo(source);
        }
        for (String bad : new String[]{"\"guessed\"", "null", "1"}) {
            assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(
                    envelope("muse", "[]").replace("\"messages\"",
                            "\"source\":" + bad + ",\"messages\""))));
        }
    }

    @Test
    public void decodesV1CreateSurfaceWithExtendedPayload() {
        String messages = "[{\"version\":\"1.0\",\"createSurface\":{"
                + "\"surfaceId\":\"s\",\"catalogId\":\"automotive\","
                + "\"components\":[{\"id\":\"root\",\"component\":\"Column\"}],"
                + "\"dataModel\":{\"counter\":42},"
                + "\"metadata\":{\"extensions\":{\"a2ui_experimental\":{\"streaming\":true}}},"
                + "\"sendDataModel\":true}}]";
        String json = envelope("claude", messages);

        A2uiMessage message = decoder().decode(bytes(json)).getMessages().get(0);

        assertThat(message.getKind()).isEqualTo(A2uiMessage.Kind.CREATE_SURFACE);
        assertThat(message.getVersion()).isEqualTo("1.0");
        assertThat(message.getCatalogId()).isEqualTo("automotive");
        assertThat(message.getComponents()).hasSize(1);
        assertThat(message.getInitialDataModel()).containsEntry("counter",
                new BigDecimal("42"));
        assertThat(message.getMetadata().keySet()).containsExactly("extensions");
        assertThat(message.getSendDataModel()).isTrue();
    }

    @Test
    public void decodesV1CreateSurfaceWithoutCatalogId() {
        String messages = "[{\"version\":\"1.0\",\"createSurface\":{\"surfaceId\":\"s\"}}]";
        String json = envelope("claude", messages);

        A2uiMessage message = decoder().decode(bytes(json)).getMessages().get(0);

        assertThat(message.getKind()).isEqualTo(A2uiMessage.Kind.CREATE_SURFACE);
        assertThat(message.getCatalogId()).isNull();
        assertThat(message.getComponents()).isEmpty();
        assertThat(message.getInitialDataModel()).isEmpty();
        assertThat(message.getMetadata()).isEmpty();
        assertThat(message.getSendDataModel()).isFalse();
    }

    @Test
    public void rejectsThemeOnEveryVersion() {
        for (String version : new String[]{"v0.9", "1.0"}) {
            String messages = "[{\"version\":\"" + version + "\",\"createSurface\":{"
                    + "\"surfaceId\":\"s\",\"catalogId\":\"automotive\",\"theme\":{}}}]";
            String json = envelope("claude", messages);
            assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(json)));
        }
    }

    @Test
    public void rejectsV1MetadataThatIsNotExtensionsOnly() {
        String owner = "[{\"version\":\"1.0\",\"createSurface\":{\"surfaceId\":\"s\","
                + "\"metadata\":{\"owner\":\"me\"}}}]";
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(
                bytes(envelope("claude", owner))));
        String badKey = "[{\"version\":\"1.0\",\"createSurface\":{\"surfaceId\":\"s\","
                + "\"metadata\":{\"extensions\":{\"a2ui:experimental\":{}}}}}]";
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(
                bytes(envelope("claude", badKey))));
    }

    @Test
    public void rejectsV1KeysOnPreV1CreateSurface() {
        String messages = "[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"s\","
                + "\"catalogId\":\"automotive\",\"components\":[]}}]";
        String json = envelope("claude", messages);
        assertThrows(A2uiProtocolException.class, () -> decoder().decode(bytes(json)));
    }

    private static BoundedA2uiJsonDecoder decoder() {
        return new BoundedA2uiJsonDecoder();
    }

    private static String envelope(String provider, String messages) {
        return "{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"" + provider
                + "\",\"sessionId\":\"session\",\"turnId\":\"turn\",\"messages\":"
                + messages + "}";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
