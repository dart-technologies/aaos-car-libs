/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Provider selected behind the host-owned A2UI gateway. */
@SuppressWarnings("Enum")
public enum AgentProvider {
    CLAUDE("claude"),
    CHATGPT("chatgpt"),
    GEMINI("gemini"),
    GROK("grok"),
    MUSE("muse");

    private final String mWireName;

    AgentProvider(String wireName) {
        mWireName = wireName;
    }

    /** Stable gateway identifier. Model names deliberately do not appear in the vehicle API. */
    @NonNull
    public String wireName() {
        return mWireName;
    }

    /** Resolves an exact gateway identifier, or null for an unsupported provider. */
    @Nullable
    public static AgentProvider fromWireName(@Nullable String value) {
        for (AgentProvider provider : values()) {
            if (provider.mWireName.equals(value)) {
                return provider;
            }
        }
        return null;
    }
}
