/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

/** Bounded client-to-agent action outcome vocabulary. */
@SuppressWarnings("Enum")
public enum AgentActionOutcome {
    ALLOWED,
    CONFIRMED,
    REFUSED,
    FAILED
}
