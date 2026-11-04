/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Redacted transport failure. Response bodies and credentials are never included. */
public final class AgentTransportException extends Exception {
    @SuppressWarnings("Enum")
    public enum Kind { CLOSED, AUTHENTICATION, HTTP, RESPONSE_TOO_LARGE, NETWORK, MALFORMED }

    private final Kind mKind;
    private final int mHttpStatus;

    public AgentTransportException(@NonNull Kind kind, @NonNull String message) {
        this(kind, message, 0, null);
    }

    public AgentTransportException(@NonNull Kind kind, @NonNull String message,
            int httpStatus, @Nullable Throwable cause) {
        super(message, cause);
        mKind = kind;
        mHttpStatus = httpStatus;
    }

    @NonNull public Kind getKind() { return mKind; }
    public int getHttpStatus() { return mHttpStatus; }
}
