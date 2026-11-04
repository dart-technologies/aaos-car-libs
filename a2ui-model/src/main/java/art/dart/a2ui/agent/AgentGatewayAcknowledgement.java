/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

/** Validated acknowledgement of a client-to-agent action result. */
public final class AgentGatewayAcknowledgement {
    private final AgentProvider mProvider;
    private final String mSessionId;
    private final String mTurnId;

    AgentGatewayAcknowledgement(AgentProvider provider, String sessionId, String turnId) {
        mProvider = provider;
        mSessionId = sessionId;
        mTurnId = turnId;
    }

    @NonNull public AgentProvider getProvider() { return mProvider; }
    @NonNull public String getSessionId() { return mSessionId; }
    @NonNull public String getTurnId() { return mTurnId; }
}
