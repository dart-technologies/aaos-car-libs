/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

/** Provider-neutral gateway execution profiles; concrete model ids remain server-owned. */
@SuppressWarnings("Enum")
public enum AgentExecutionProfile {
    /** Generate and validate a high-quality response ahead of the demo, then replay its cache. */
    QUALITY_CACHED("quality_cached"),
    /** Ask the gateway for its lowest-latency approved configuration during an interactive demo. */
    REALTIME_LOW_LATENCY("realtime_low_latency");

    private final String mWireName;

    AgentExecutionProfile(String wireName) {
        mWireName = wireName;
    }

    @NonNull
    public String wireName() {
        return mWireName;
    }
}
