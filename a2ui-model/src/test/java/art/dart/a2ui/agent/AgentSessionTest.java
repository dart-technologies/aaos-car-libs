/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class AgentSessionTest {
    private static final java.util.concurrent.Executor DIRECT = Runnable::run;
    @Test
    public void newerTurnDiscardsLateOlderResponse() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        session.submitTurn("one", "first", 0, 0, true, DIRECT, first);
        session.submitTurn("two", "second", 0, 0, true, DIRECT, second);
        transport.respond(0, response("claude", "one", "old"));
        transport.respond(1, response("claude", "two", "new"));

        assertThat(first.applied).isEmpty();
        assertThat(second.applied).hasSize(1);
        assertThat(processor.getSurface("old")).isNull();
        assertThat(processor.getSurface("new")).isNotNull();
    }

    @Test
    public void resetSubmitClearsSynchronouslyAndStaleTurnStaysDropped() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        session.submitTurn("one", "first", 0, 0, true, DIRECT, first);
        transport.respond(0, response("claude", "one", "old"));
        assertThat(processor.getSurface("old")).isNotNull();

        session.submitTurnAfterReset("two", "second", 0, 0, true, DIRECT, second);
        // Synchronous: the old surfaces are gone before the new turn's response can arrive.
        assertThat(processor.getSurface("old")).isNull();

        transport.respond(1, response("claude", "two", "new"));
        assertThat(second.applied).hasSize(1);
        assertThat(processor.getSurface("new")).isNotNull();
    }

    @Test
    public void overlappingResetSubmitDropsTheLateOlderResponse() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        session.submitTurn("one", "first", 0, 0, true, DIRECT, first);
        session.submitTurnAfterReset("two", "second", 0, 0, true, DIRECT, second);
        // The older response arrives after the reset: generation-gated, never applied.
        transport.respond(0, response("claude", "one", "old"));
        transport.respond(1, response("claude", "two", "new"));

        assertThat(first.applied).isEmpty();
        assertThat(second.applied).hasSize(1);
        assertThat(processor.getSurface("old")).isNull();
        assertThat(processor.getSurface("new")).isNotNull();
    }

    @Test
    public void providerSwapDiscardsPriorProviderAndAllShareOneContract() {
        for (AgentProvider provider : AgentProvider.values()) {
            FakeTransport transport = new FakeTransport();
            A2uiMessageProcessor processor = processor();
            AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
            session.selectProvider(provider);
            RecordingCallback callback = new RecordingCallback();
            session.submitTurn("t", "input", 0, 0, true, DIRECT, callback);
            transport.respond(0, response(provider.wireName(), "t", provider.wireName()));
            assertThat(callback.applied).hasSize(1);
            assertThat(processor.getSurface(provider.wireName())).isNotNull();
        }
    }

    @Test
    public void providerSwapClearsPriorProviderSurfaceState() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        RecordingCallback callback = new RecordingCallback();
        session.submitTurn("first", "input", 0, 0, true, DIRECT, callback);
        transport.respond(0, response("claude", "first", "claude_surface"));
        assertThat(processor.getSurface("claude_surface")).isNotNull();

        session.selectProvider(AgentProvider.GEMINI);

        assertThat(processor.getSurfaces()).isEmpty();
    }

    @Test
    public void mismatchedProviderSessionOrTurnFailsWithoutMutation() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        RecordingCallback callback = new RecordingCallback();
        session.submitTurn("right", "input", 0, 0, true, DIRECT, callback);
        transport.respond(0, response("grok", "right", "bad"));

        assertThat(callback.failures).hasSize(1);
        assertThat(processor.getSurfaces()).isEmpty();
    }

    @Test
    public void closeDropsCallbacksAndRejectsNewTurns() {
        FakeTransport transport = new FakeTransport();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor());
        RecordingCallback callback = new RecordingCallback();
        session.submitTurn("t", "input", 0, 0, true, DIRECT, callback);
        session.close();
        transport.respond(0, response("claude", "t", "late"));
        assertThat(callback.applied).isEmpty();
        assertThrows(IllegalStateException.class,
                () -> session.submitTurn("x", "x", 0, 0, true, DIRECT, callback));
    }

    @Test
    public void configRejectsNonHttpsCredentialsQueryAndFragment() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentGatewayConfig("http://gateway.example/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentGatewayConfig("https://u:p@gateway.example/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentGatewayConfig("https://gateway.example/v1?q=x"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentGatewayConfig("https://gateway.example/v1#x"));
        assertThrows(IllegalArgumentException.class, () -> new AgentGatewayConfig("https://%"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentGatewayConfig("https://gateway.example/v1", 0, 1, 1));
        AgentGatewayConfig config = new AgentGatewayConfig("https://gateway.example/v1");
        assertThat(config.getEndpoint())
                .isEqualTo("https://gateway.example/v1");
        assertThat(config.getConnectTimeoutMillis()).isEqualTo(10_000);
        assertThat(config.getReadTimeoutMillis()).isEqualTo(30_000);
        assertThat(config.getMaxResponseBytes()).isEqualTo(256 * 1024);
    }

    @Test
    public void clientEnvelopesUseBoundedProviderNeutralVocabulary() {
        for (AgentProvider provider : AgentProvider.values()) {
            String request = new String(AgentGatewayProtocol.encodeTurn(provider,
                    "session", "turn", "hello", 4, 511, true), StandardCharsets.UTF_8);
            assertThat(request).contains("\"provider\":\"" + provider.wireName() + "\"");
            assertThat(request).contains("\"schema\":\"a2ui.gateway.v1\"");
            assertThat(request).contains("\"executionProfile\":\"realtime_low_latency\"");
        }
        String action = new String(AgentGatewayProtocol.encodeActionResult(
                AgentProvider.GROK, "session", "turn", "surface", "button", "media.play",
                AgentActionOutcome.REFUSED, "restricted"), StandardCharsets.UTF_8);
        assertThat(action).contains("\"outcome\":\"REFUSED\"");
        String escaped = new String(AgentGatewayProtocol.encodeActionResult(
                AgentProvider.CLAUDE, "session", "turn", "surface", "button", "media.play",
                AgentActionOutcome.ALLOWED, "\"\\\b\f\n\r\t\u0001🚗"), StandardCharsets.UTF_8);
        assertThat(escaped).contains("\\\"");
        assertThat(escaped).contains("\\u0001");
        String noText = new String(AgentGatewayProtocol.encodeActionResult(
                AgentProvider.CLAUDE, "session", "turn", "surface", "button", "media.play",
                AgentActionOutcome.ALLOWED, null), StandardCharsets.UTF_8);
        assertThat(noText).doesNotContain("resultText");
    }

    @Test
    public void clientEnvelopeRejectsControlIdsSurrogatesAndInvalidDisplayState() {
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "bad/id", "turn", "x", 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "session", "turn", "\ud800", 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "session", "turn", "\udc00", 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "session", "turn", "x", -1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "session", "turn", "x", 0, -1, true));
        assertThrows(IllegalArgumentException.class, () -> AgentGatewayProtocol.encodeTurn(
                AgentProvider.CLAUDE, "session", "turn", new String(new char[4097]), 0, 0, true));
    }

    @Test
    public void malformedAndProtocolInvalidResponsesFailWithoutPartialCommit() {
        FakeTransport malformedTransport = new FakeTransport();
        A2uiMessageProcessor malformedProcessor = processor();
        AgentSession malformedSession = session(
                AgentProvider.CLAUDE, malformedTransport, malformedProcessor);
        RecordingCallback malformed = new RecordingCallback();
        malformedSession.submitTurn("bad", "x", 0, 0, true, DIRECT, malformed);
        malformedTransport.respond(0, "not json".getBytes(StandardCharsets.UTF_8));
        assertThat(malformed.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.MALFORMED);

        FakeTransport invalidTransport = new FakeTransport();
        A2uiMessageProcessor invalidProcessor = processor();
        AgentSession invalidSession = session(AgentProvider.CLAUDE, invalidTransport,
                invalidProcessor);
        RecordingCallback invalid = new RecordingCallback();
        invalidSession.submitTurn("bad", "x", 0, 0, true, DIRECT, invalid);
        String twoCreates = "{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"claude\","
                + "\"sessionId\":\"session\",\"turnId\":\"bad\",\"messages\":["
                + "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"same\",\"catalogId\":\"automotive\"}},"
                + "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"same\",\"catalogId\":\"automotive\"}}]}";
        invalidTransport.respond(0, twoCreates.getBytes(StandardCharsets.UTF_8));
        assertThat(invalid.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.MALFORMED);
        assertThat(invalidProcessor.getSurfaces()).isEmpty();
    }

    @Test
    public void currentTransportFailureIsDeliveredButStaleFailureIsDropped() {
        FakeTransport transport = new FakeTransport();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor());
        RecordingCallback stale = new RecordingCallback();
        RecordingCallback current = new RecordingCallback();
        session.submitTurn("old", "x", 0, 0, true, DIRECT, stale);
        session.submitTurn("new", "x", 0, 0, true, DIRECT, current);
        AgentTransportException failure = new AgentTransportException(
                AgentTransportException.Kind.NETWORK, "offline");
        transport.fail(0, failure);
        transport.fail(1, failure);
        assertThat(stale.failures).isEmpty();
        assertThat(current.failures).containsExactly(failure);
    }

    @Test
    public void providerSelectionAndCloseAreIdempotentAndClearState() {
        FakeTransport transport = new FakeTransport();
        A2uiMessageProcessor processor = processor();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor);
        assertThat(session.getProvider()).isEqualTo(AgentProvider.CLAUDE);
        assertThat(session.getExecutionProfile())
                .isEqualTo(AgentExecutionProfile.REALTIME_LOW_LATENCY);
        session.selectProvider(AgentProvider.CLAUDE);
        session.close();
        session.close();
        assertThat(transport.closed).isTrue();
        assertThat(processor.getSurfaces()).isEmpty();
        assertThrows(IllegalArgumentException.class, () -> new AgentSession("bad/id",
                AgentProvider.CLAUDE, transport, new BoundedA2uiJsonDecoder(), processor,
                emptyRegistry()));
    }

    @Test
    public void executionProfileChangeDropsOldTurnAndUsesProviderNeutralWireValue() {
        FakeTransport transport = new FakeTransport();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor());
        RecordingCallback stale = new RecordingCallback();
        RecordingCallback current = new RecordingCallback();
        session.submitTurn("old", "x", 0, 0, true, DIRECT, stale);

        session.selectExecutionProfile(AgentExecutionProfile.QUALITY_CACHED);
        session.submitTurn("new", "x", 0, 0, true, DIRECT, current);
        transport.respond(0, response("claude", "old", "old"));
        transport.respond(1, response("claude", "new", "new"));

        assertThat(stale.applied).isEmpty();
        assertThat(current.applied).hasSize(1);
        assertThat(session.getExecutionProfile()).isEqualTo(AgentExecutionProfile.QUALITY_CACHED);
        assertThat(new String(transport.requests.get(1), StandardCharsets.UTF_8))
                .contains("\"executionProfile\":\"quality_cached\"");
    }

    @Test
    public void actionResultRequiresMatchingAcknowledgement() {
        FakeTransport transport = new FakeTransport();
        AgentSession session = session(AgentProvider.CHATGPT, transport, processor());
        RecordingActionCallback callback = new RecordingActionCallback();
        session.sendActionResult("turn", "surface", "button", "media.play",
                AgentActionOutcome.ALLOWED, null, DIRECT, callback);
        assertThat(new String(transport.requests.get(0), StandardCharsets.UTF_8))
                .contains("\"type\":\"actionResult\"");
        transport.respond(0, acknowledgement("chatgpt", "turn"));
        assertThat(callback.acknowledgements).hasSize(1);
        assertThat(callback.failures).isEmpty();

        RecordingActionCallback mismatch = new RecordingActionCallback();
        session.sendActionResult("next", "surface", "button", "media.play",
                AgentActionOutcome.REFUSED, "moving", DIRECT, mismatch);
        transport.respond(1, acknowledgement("grok", "next"));
        assertThat(mismatch.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.MALFORMED);
    }

    @Test
    public void actionResultCallbackIsDroppedAfterProviderSwap() {
        FakeTransport transport = new FakeTransport();
        AgentSession session = session(AgentProvider.CLAUDE, transport, processor());
        RecordingActionCallback callback = new RecordingActionCallback();
        session.sendActionResult("turn", "surface", "button", "media.play",
                AgentActionOutcome.ALLOWED, null, DIRECT, callback);
        session.selectProvider(AgentProvider.GROK);
        transport.respond(0, "bad".getBytes(StandardCharsets.UTF_8));
        assertThat(callback.acknowledgements).isEmpty();
        assertThat(callback.failures).isEmpty();
    }

    private static AgentSession session(AgentProvider provider, AgentTransport transport,
            A2uiMessageProcessor processor) {
        return new AgentSession("session", provider, transport,
                new BoundedA2uiJsonDecoder(), processor, emptyRegistry());
    }

    private static TrustedActionRegistry emptyRegistry() {
        return new TrustedActionRegistry.Builder().build();
    }

    private static A2uiMessageProcessor processor() {
        return new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList("v0.9")));
    }

    private static byte[] response(String provider, String turn, String surface) {
        String json = "{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"" + provider
                + "\",\"sessionId\":\"session\",\"turnId\":\"" + turn
                + "\",\"messages\":[{\"version\":\"v0.9\",\"createSurface\":{"
                + "\"surfaceId\":\"" + surface + "\",\"catalogId\":\"automotive\"}}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] acknowledgement(String provider, String turn) {
        return ("{\"schema\":\"a2ui.gateway.v1\",\"ack\":\"actionResult\","
                + "\"provider\":\"" + provider + "\",\"sessionId\":\"session\","
                + "\"turnId\":\"" + turn + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeTransport implements AgentTransport {
        final List<Callback> callbacks = new ArrayList<>();
        final List<byte[]> requests = new ArrayList<>();
        boolean closed;
        @Override public void send(byte[] request, java.util.concurrent.Executor executor,
                Callback callback) { requests.add(request.clone()); callbacks.add(callback); }
        @Override public void close() { closed = true; }
        void respond(int index, byte[] response) { callbacks.get(index).onResponse(response); }
        void fail(int index, AgentTransportException failure) {
            callbacks.get(index).onFailure(failure);
        }
    }

    private static final class RecordingCallback implements AgentSession.Callback {
        final List<AgentGatewayEnvelope> applied = new ArrayList<>();
        final List<AgentTransportException> failures = new ArrayList<>();
        @Override public void onApplied(AgentGatewayEnvelope response) { applied.add(response); }
        @Override public void onFailure(AgentTransportException failure) { failures.add(failure); }
    }

    private static final class RecordingActionCallback implements AgentSession.ActionResultCallback {
        final List<AgentGatewayAcknowledgement> acknowledgements = new ArrayList<>();
        final List<AgentTransportException> failures = new ArrayList<>();
        @Override public void onAcknowledged(AgentGatewayAcknowledgement acknowledgement) {
            acknowledgements.add(acknowledgement);
        }
        @Override public void onFailure(AgentTransportException failure) { failures.add(failure); }
    }
}
