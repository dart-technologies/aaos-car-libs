/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

import java.net.URI;
import java.net.URISyntaxException;

/** Immutable connection policy for the OEM-owned provider normalization gateway. */
public final class AgentGatewayConfig {
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024;

    private final URI mEndpoint;
    private final int mConnectTimeoutMillis;
    private final int mReadTimeoutMillis;
    private final int mMaxResponseBytes;

    public AgentGatewayConfig(@NonNull String endpoint) {
        this(endpoint, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    public AgentGatewayConfig(@NonNull String endpoint, int connectTimeoutMillis,
            int readTimeoutMillis, int maxResponseBytes) {
        final URI parsed;
        try {
            parsed = new URI(endpoint);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("gateway endpoint is not a valid URI", invalid);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getUserInfo() != null
                || parsed.getFragment() != null
                || parsed.getQuery() != null) {
            throw new IllegalArgumentException(
                    "gateway endpoint must be an HTTPS origin/path without credentials, query, or fragment");
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("timeouts and response limit must be positive");
        }
        mEndpoint = parsed;
        mConnectTimeoutMillis = connectTimeoutMillis;
        mReadTimeoutMillis = readTimeoutMillis;
        mMaxResponseBytes = maxResponseBytes;
    }

    @NonNull public String getEndpoint() { return mEndpoint.toASCIIString(); }
    @NonNull URI endpointUri() { return mEndpoint; }
    public int getConnectTimeoutMillis() { return mConnectTimeoutMillis; }
    public int getReadTimeoutMillis() { return mReadTimeoutMillis; }
    public int getMaxResponseBytes() { return mMaxResponseBytes; }
}
