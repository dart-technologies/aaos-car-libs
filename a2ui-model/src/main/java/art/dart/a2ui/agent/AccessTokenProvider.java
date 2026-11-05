/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

/** Supplies a short-lived OEM gateway token, never a model-provider API key. */
public interface AccessTokenProvider {
    @NonNull String getAccessToken() throws AgentTransportException;
}
