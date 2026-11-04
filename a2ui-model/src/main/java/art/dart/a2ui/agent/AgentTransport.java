/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

/** Asynchronous byte transport for the normalized gateway contract. */
public interface AgentTransport extends AutoCloseable {
    @SuppressWarnings("GenericCallbacks")
    interface Callback {
        void onResponse(@NonNull byte[] response);
        void onFailure(@NonNull AgentTransportException failure);
    }

    /** Sends one complete JSON envelope. Implementations must invoke exactly one callback method. */
    void send(@NonNull byte[] request, @NonNull java.util.concurrent.Executor callbackExecutor,
            @NonNull Callback callback);

    /** Cancels pending work and prevents future requests. Idempotent. */
    @Override void close();
}
