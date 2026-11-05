/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.protocol.A2uiMessage;

import java.util.Collections;
import java.util.List;

/** One validated response from the provider-normalization gateway. */
public final class AgentGatewayEnvelope {
    public static final String SCHEMA = "a2ui.gateway.v1";
    /** This turn's vendor call produced the messages. */
    public static final String SOURCE_LIVE = "live";
    /** An exact replay of an earlier call for the same model and prompt. */
    public static final String SOURCE_CACHE = "cache";
    /** Served because the vendor failed or missed the deadline. */
    public static final String SOURCE_FALLBACK = "fallback";

    private final AgentProvider mProvider;
    private final String mSessionId;
    private final String mTurnId;
    private final List<A2uiMessage> mMessages;
    @Nullable private final String mAssistantText;
    @Nullable private final String mSource;

    AgentGatewayEnvelope(AgentProvider provider, String sessionId, String turnId,
            List<A2uiMessage> messages, @Nullable String assistantText, @Nullable String source) {
        mProvider = provider;
        mSessionId = sessionId;
        mTurnId = turnId;
        mMessages = Collections.unmodifiableList(messages);
        mAssistantText = assistantText;
        mSource = source;
    }

    @NonNull public AgentProvider getProvider() { return mProvider; }
    @NonNull public String getSessionId() { return mSessionId; }
    @NonNull public String getTurnId() { return mTurnId; }
    @NonNull public List<A2uiMessage> getMessages() { return mMessages; }
    @Nullable public String getAssistantText() { return mAssistantText; }
    /** One of the SOURCE_ values, or null when the gateway did not say. */
    @Nullable public String getSource() { return mSource; }
}
