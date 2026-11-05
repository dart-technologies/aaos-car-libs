/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiProtocolException;

/**
 * Provider-swappable session that applies only the newest in-flight turn.
 *
 * <p>Switching provider, submitting a newer turn, or closing increments the generation. A late
 * callback is discarded before it can mutate the processor, preventing same-node stale futures and
 * cross-provider responses from overwriting newer state.
 */
public final class AgentSession implements AutoCloseable {
    @SuppressWarnings("GenericCallbacks")
    public interface Callback {
        void onApplied(@NonNull AgentGatewayEnvelope response);
        void onFailure(@NonNull AgentTransportException failure);
    }

    @SuppressWarnings("GenericCallbacks")
    public interface ActionResultCallback {
        void onAcknowledged(@NonNull AgentGatewayAcknowledgement acknowledgement);
        void onFailure(@NonNull AgentTransportException failure);
    }

    private final Object mLock = new Object();
    private final String mSessionId;
    private final AgentTransport mTransport;
    private final BoundedA2uiJsonDecoder mDecoder;
    private final A2uiMessageProcessor mProcessor;
    private AgentProvider mProvider;
    private AgentExecutionProfile mExecutionProfile =
            AgentExecutionProfile.REALTIME_LOW_LATENCY;
    private long mGeneration;
    private boolean mClosed;

    public AgentSession(@NonNull String sessionId, @NonNull AgentProvider provider,
            @NonNull AgentTransport transport, @NonNull BoundedA2uiJsonDecoder decoder,
            @NonNull A2uiMessageProcessor processor,
            @NonNull TrustedActionRegistry actionRegistry) {
        if (!AgentId.isCanonical(sessionId)) {
            throw new IllegalArgumentException("sessionId must match [A-Za-z0-9._:-]{1,256}");
        }
        mSessionId = sessionId;
        mProvider = provider;
        mTransport = transport;
        mDecoder = decoder;
        mProcessor = processor;
        mProcessor.setActionClassResolver(actionRegistry);
        mProcessor.setActionSink(actionRegistry);
    }

    public void selectProvider(@NonNull AgentProvider provider) {
        synchronized (mLock) {
            requireOpen();
            if (mProvider != provider) {
                mProvider = provider;
                mGeneration++;
                mProcessor.reset();
            }
        }
    }

    @NonNull
    public AgentProvider getProvider() {
        synchronized (mLock) {
            return mProvider;
        }
    }

    /** Changes model effort/cache intent without exposing provider-specific model ids to the APK. */
    public void selectExecutionProfile(@NonNull AgentExecutionProfile executionProfile) {
        synchronized (mLock) {
            requireOpen();
            if (mExecutionProfile != executionProfile) {
                mExecutionProfile = executionProfile;
                mGeneration++;
            }
        }
    }

    @NonNull
    public AgentExecutionProfile getExecutionProfile() {
        synchronized (mLock) {
            return mExecutionProfile;
        }
    }

    public void submitTurn(@NonNull String turnId, @NonNull String inputText, int displayId,
            int activeRestrictions, boolean trustworthy,
            @NonNull java.util.concurrent.Executor callbackExecutor, @NonNull Callback callback) {
        final long generation;
        final AgentProvider provider;
        final AgentExecutionProfile executionProfile;
        synchronized (mLock) {
            requireOpen();
            generation = ++mGeneration;
            provider = mProvider;
            executionProfile = mExecutionProfile;
        }
        byte[] request = AgentGatewayProtocol.encodeTurn(provider, executionProfile, mSessionId,
                turnId, inputText, displayId, activeRestrictions, trustworthy);
        mTransport.send(request, callbackExecutor, new AgentTransport.Callback() {
            @Override
            public void onResponse(@NonNull byte[] responseBytes) {
                final AgentGatewayEnvelope response;
                try {
                    response = mDecoder.decode(responseBytes);
                } catch (A2uiProtocolException failure) {
                    deliverFailureIfCurrent(generation, callback, new AgentTransportException(
                            AgentTransportException.Kind.MALFORMED,
                            "gateway response failed A2UI validation", 0, failure));
                    return;
                }
                AgentTransportException processingFailure = null;
                synchronized (mLock) {
                    if (mClosed || generation != mGeneration) return;
                    if (response.getProvider() != provider
                            || !response.getSessionId().equals(mSessionId)
                            || !response.getTurnId().equals(turnId)) {
                        processingFailure = new AgentTransportException(
                                AgentTransportException.Kind.MALFORMED,
                                "gateway response identity does not match request");
                    } else {
                        try {
                            mProcessor.processAll(response.getMessages());
                        } catch (A2uiProtocolException failure) {
                            processingFailure = new AgentTransportException(
                                    AgentTransportException.Kind.MALFORMED,
                                    "gateway messages failed A2UI processing", 0, failure);
                        }
                    }
                }
                if (processingFailure == null) callback.onApplied(response);
                else callback.onFailure(processingFailure);
            }

            @Override
            public void onFailure(@NonNull AgentTransportException failure) {
                deliverFailureIfCurrent(generation, callback, failure);
            }
        });
    }

    /** Sends one policy/action outcome back through the same authenticated provider binding. */
    public void sendActionResult(@NonNull String turnId, @NonNull String surfaceId,
            @NonNull String componentId, @NonNull String actionId,
            @NonNull AgentActionOutcome outcome, @androidx.annotation.Nullable String resultText,
            @NonNull java.util.concurrent.Executor callbackExecutor,
            @NonNull ActionResultCallback callback) {
        final AgentProvider provider;
        synchronized (mLock) {
            requireOpen();
            provider = mProvider;
        }
        byte[] request = AgentGatewayProtocol.encodeActionResult(provider, mSessionId, turnId,
                surfaceId, componentId, actionId, outcome, resultText);
        mTransport.send(request, callbackExecutor, new AgentTransport.Callback() {
            @Override
            public void onResponse(@NonNull byte[] responseBytes) {
                synchronized (mLock) {
                    if (mClosed || mProvider != provider) return;
                }
                final AgentGatewayAcknowledgement acknowledgement;
                try {
                    acknowledgement = mDecoder.decodeAcknowledgement(responseBytes);
                } catch (A2uiProtocolException failure) {
                    callback.onFailure(new AgentTransportException(
                            AgentTransportException.Kind.MALFORMED,
                            "gateway acknowledgement failed validation", 0, failure));
                    return;
                }
                synchronized (mLock) {
                    if (mClosed || mProvider != provider) return;
                }
                if (acknowledgement.getProvider() != provider
                        || !acknowledgement.getSessionId().equals(mSessionId)
                        || !acknowledgement.getTurnId().equals(turnId)) {
                    callback.onFailure(new AgentTransportException(
                            AgentTransportException.Kind.MALFORMED,
                            "gateway acknowledgement identity does not match action result"));
                    return;
                }
                callback.onAcknowledged(acknowledgement);
            }

            @Override
            public void onFailure(@NonNull AgentTransportException failure) {
                synchronized (mLock) {
                    if (mClosed || mProvider != provider) return;
                }
                callback.onFailure(failure);
            }
        });
    }

    private void deliverFailureIfCurrent(long generation, Callback callback,
            AgentTransportException failure) {
        synchronized (mLock) {
            if (mClosed || generation != mGeneration) return;
        }
        callback.onFailure(failure);
    }

    private void requireOpen() {
        if (mClosed) throw new IllegalStateException("agent session is closed");
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
            mGeneration++;
            mProcessor.reset();
        }
        mTransport.close();
    }
}
