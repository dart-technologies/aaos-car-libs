/*
 * Copyright (C) 2026 Dart Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package art.dart.a2ui.carui;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import art.dart.a2ui.agent.AgentActionOutcome;
import art.dart.a2ui.agent.AgentExecutionProfile;
import art.dart.a2ui.agent.AgentGatewayAcknowledgement;
import art.dart.a2ui.agent.AgentGatewayConfig;
import art.dart.a2ui.agent.AgentGatewayEnvelope;
import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.AgentSession;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;
import art.dart.a2ui.agent.BoundedA2uiJsonDecoder;
import art.dart.a2ui.agent.HttpsAgentTransport;
import art.dart.a2ui.agent.TrustedActionRegistry;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiSurface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Host B's live-agent lane, created only on debuggable non-user builds.
 *
 * <p>It owns a SEPARATE processor, handed to its {@link AgentSession}: the session's
 * {@code reset()} on a provider switch, and its action resolver and sink, then reach only agent
 * surfaces, never the preloaded corpus or the host's own sink. Each ask also resets it, because
 * the stateless gateway re-creates its surfaces every turn and the lifecycle refuses a second
 * {@code createSurface} for a live id.
 *
 * <p>Vendor keys stay in the dev gateway (tools/a2ui-gateway). This lane holds a fixed local token
 * the gateway checks, and reaches it as {@code https://127.0.0.1:8787} through
 * {@code adb reverse tcp:8787 tcp:8787}; debug builds alone trust its dev CA.
 */
final class AgentLane implements AutoCloseable {
    static final String DEV_GATEWAY = "https://127.0.0.1:8787/v1";
    static final String DEV_TOKEN = "a2ui-dev-local";
    static final String SESSION_ID = "carui-debug";
    /** The gateway's action vocabulary (tools/a2ui-gateway contract.ACTIONS), classed by policy. */
    static final List<String> ACTIONS = Collections.unmodifiableList(Arrays.asList(
            "play", "pause", "stop", "next", "previous", "open", "select", "navigate", "search",
            "edit", "purchase"));

    private static final String TAG = "A2uiAgentLane";

    interface Listener {
        void onPending(@NonNull AgentProvider provider);

        /** {@code badge} is {@link #badge}: a latency only when this turn's vendor call answered. */
        void onSurface(@NonNull String surfaceId, @NonNull AgentProvider provider,
                @NonNull String badge);

        void onFailure(@NonNull AgentProvider provider, @NonNull AgentTransportException failure);

        /** Spoken reply for a surface just shown; never called without one. */
        default void onAssistantText(@NonNull String text) {}
    }

    private final A2uiMessageProcessor mProcessor = DemoTrace.newProcessor();
    private final FallbackAgentTransport mTransport;
    private final AgentSession mSession;
    private final Executor mMain;
    private final Listener mListener;
    @Nullable private final ExecutorService mNetwork;
    @Nullable private String mLastAsk;
    @Nullable private String mLastTurnId;

    /** The dev-gateway lane. */
    static AgentLane create(@NonNull Executor main, @NonNull Listener listener) {
        ExecutorService network = Executors.newSingleThreadExecutor();
        AgentTransport https = new HttpsAgentTransport(
                new AgentGatewayConfig(DEV_GATEWAY, 3_000, 15_000,
                        AgentGatewayConfig.DEFAULT_MAX_RESPONSE_BYTES),
                () -> DEV_TOKEN, network);
        return new AgentLane(https, network, main, listener);
    }

    @VisibleForTesting
    AgentLane(@NonNull AgentTransport delegate, @Nullable ExecutorService network,
            @NonNull Executor main, @NonNull Listener listener) {
        mTransport = new FallbackAgentTransport(delegate);
        mNetwork = network;
        mMain = main;
        mListener = listener;
        TrustedActionRegistry.Builder registry = new TrustedActionRegistry.Builder()
                .setRejectionListener(ignored -> Log.i(TAG, "agent action refused: unknown id"));
        for (String action : ACTIONS) {
            registry.addAction(action, A2uiActionPolicy.classify(action, null),
                    (surfaceId, componentId, payload) -> Log.i(TAG, "agent action ran: " + action));
        }
        mSession = new AgentSession(SESSION_ID, AgentProvider.CLAUDE, mTransport,
                new BoundedA2uiJsonDecoder(), mProcessor, registry.build());
    }

    @NonNull
    A2uiMessageProcessor processor() {
        return mProcessor;
    }

    @NonNull
    AgentProvider provider() {
        return mSession.getProvider();
    }

    @Nullable
    String lastAsk() {
        return mLastAsk;
    }

    /** Resets the lane's surfaces (AgentSession does) and drops any in-flight turn. */
    /** Parked asks may take the cached high-quality lane; moving ones the low-latency one. */
    void selectProfile(@NonNull AgentExecutionProfile profile) {
        mSession.selectExecutionProfile(profile);
    }

    @NonNull
    AgentExecutionProfile profile() {
        return mSession.getExecutionProfile();
    }

    void selectProvider(@NonNull AgentProvider provider) {
        mSession.selectProvider(provider);
    }

    /** Submits one turn; the listener hears the outcome on {@code main}. */
    void ask(@NonNull String text, int displayId,
            @NonNull A2uiMessageProcessor.RestrictionReading reading) {
        AgentProvider provider = mSession.getProvider();
        String turnId = turnId(provider, mSession.getExecutionProfile(), text);
        mProcessor.reset();
        mLastAsk = text;
        mLastTurnId = turnId;
        long started = System.nanoTime();
        mListener.onPending(provider);
        mSession.submitTurn(turnId, text, displayId, reading.getActiveRestrictions(),
                reading.isTrustworthy(), mMain, new AgentSession.Callback() {
                    @Override
                    public void onApplied(@NonNull AgentGatewayEnvelope response) {
                        boolean replayed = mTransport.consumeFallback();
                        long latencyMs = (System.nanoTime() - started) / 1_000_000;
                        String surfaceId = firstProjectable(response);
                        if (surfaceId == null) {
                            mListener.onFailure(provider, new AgentTransportException(
                                    AgentTransportException.Kind.MALFORMED,
                                    "no surface projects from root"));
                        } else {
                            mListener.onSurface(surfaceId, provider,
                                    badge(provider, latencyMs, response.getSource(), replayed));
                            String reply = response.getAssistantText();
                            if (reply != null && !reply.isEmpty()) {
                                mListener.onAssistantText(reply);
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull AgentTransportException failure) {
                        mTransport.consumeFallback();
                        mListener.onFailure(provider, failure);
                    }
                });
    }

    @Nullable
    private String firstProjectable(AgentGatewayEnvelope response) {
        for (A2uiMessage message : response.getMessages()) {
            if (message.getKind() != A2uiMessage.Kind.CREATE_SURFACE) continue;
            A2uiSurface surface = mProcessor.getSurface(message.getSurfaceId());
            try {
                if (surface != null && surface.project(DemoTrace.ROOT_ID) != null) {
                    return message.getSurfaceId();
                }
            } catch (RuntimeException unprojectable) {
                // Try the next surface; one that cannot project is never shown.
            }
        }
        return null;
    }

    /** {@code provider · NNN ms} for this turn's live answer; {@code cached}/{@code fallback} else. */
    static String badge(@NonNull AgentProvider provider, long latencyMs, @Nullable String source,
            boolean replayed) {
        if (replayed || AgentGatewayEnvelope.SOURCE_FALLBACK.equals(source)) {
            return provider.wireName() + " · fallback";
        }
        if (AgentGatewayEnvelope.SOURCE_CACHE.equals(source)) {
            return provider.wireName() + " · cached";
        }
        return provider.wireName() + " · " + latencyMs + " ms";
    }

    /** The host decided an agent control: ALLOW and REFUSE are final, CONFIRM waits. */
    void onDecided(@NonNull String surfaceId, @NonNull A2uiComponent component,
            @NonNull A2uiActionPolicy.Decision decision) {
        if (decision == A2uiActionPolicy.Decision.ALLOW) {
            report(surfaceId, component, AgentActionOutcome.ALLOWED);
        } else if (decision == A2uiActionPolicy.Decision.REFUSE) {
            report(surfaceId, component, AgentActionOutcome.REFUSED);
        }
    }

    /** The driver answered a confirmation; {@code again} is the re-decision at that moment. */
    void onConfirmed(@NonNull String surfaceId, @Nullable A2uiComponent component,
            @NonNull A2uiActionPolicy.Decision again) {
        if (component == null) return;
        report(surfaceId, component, again == A2uiActionPolicy.Decision.REFUSE
                ? AgentActionOutcome.REFUSED : AgentActionOutcome.CONFIRMED);
    }

    /** A spoken phrase reached an agent control. */
    void onVoice(@NonNull String surfaceId,
            @NonNull A2uiMessageProcessor.VoiceDispatchResult result) {
        A2uiComponent matched = result.getMatchedComponent();
        if (matched == null) return;
        if (result.getOutcome() == A2uiMessageProcessor.VoiceOutcome.DISPATCHED) {
            report(surfaceId, matched, AgentActionOutcome.ALLOWED);
        } else if (result.getOutcome() == A2uiMessageProcessor.VoiceOutcome.REFUSED_BY_POLICY) {
            report(surfaceId, matched, AgentActionOutcome.REFUSED);
        }
    }

    /** Reports one outcome back through the provider binding that produced the surface. */
    private void report(String surfaceId, A2uiComponent component, AgentActionOutcome outcome) {
        String turnId = mLastTurnId;
        String componentId = component.getId();
        String actionId = component.getAction();
        if (turnId == null || componentId == null || actionId == null
                || mProcessor.getSurface(surfaceId) == null) {
            return;
        }
        try {
            mSession.sendActionResult(turnId, surfaceId, componentId, actionId, outcome, null,
                    mMain,
                    new AgentSession.ActionResultCallback() {
                        @Override
                        public void onAcknowledged(@NonNull AgentGatewayAcknowledgement ack) {
                            Log.i(TAG, "action result acknowledged");
                        }

                        @Override
                        public void onFailure(@NonNull AgentTransportException failure) {
                            Log.i(TAG, "action result not delivered: " + failure.getKind());
                        }
                    });
        } catch (RuntimeException notReportable) {
            // A non-canonical agent id, or a closed session: the decision itself stands.
            Log.i(TAG, "action result not reportable");
        }
    }

    /** Same ask, provider and profile, same id: what lets the fallback transport find a replay. */
    @VisibleForTesting
    static String turnId(AgentProvider provider, AgentExecutionProfile profile, String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (provider.wireName() + "|" + profile.wireName() + "|" + text)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("ask-");
            for (int i = 0; i < 12; i++) {
                id.append(Character.forDigit((hash[i] >> 4) & 0xf, 16))
                        .append(Character.forDigit(hash[i] & 0xf, 16));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        mSession.close();
        if (mNetwork != null) mNetwork.shutdownNow();
    }
}
