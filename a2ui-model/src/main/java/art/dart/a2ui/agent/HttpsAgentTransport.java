/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.agent;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS-only authenticated transport to an OEM gateway; never directly to a model provider. */
public final class HttpsAgentTransport implements AgentTransport {
    interface ConnectionFactory {
        HttpsURLConnection open(URI endpoint) throws IOException;
    }

    private final AgentGatewayConfig mConfig;
    private final AccessTokenProvider mTokenProvider;
    private final Executor mNetworkExecutor;
    private final ConnectionFactory mConnectionFactory;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public HttpsAgentTransport(@NonNull AgentGatewayConfig config,
            @NonNull AccessTokenProvider tokenProvider, @NonNull Executor networkExecutor) {
        this(config, tokenProvider, networkExecutor,
                endpoint -> (HttpsURLConnection) endpoint.toURL().openConnection());
    }

    HttpsAgentTransport(AgentGatewayConfig config, AccessTokenProvider tokenProvider,
            Executor networkExecutor, ConnectionFactory connectionFactory) {
        mConfig = config;
        mTokenProvider = tokenProvider;
        mNetworkExecutor = networkExecutor;
        mConnectionFactory = connectionFactory;
    }

    @Override
    public void send(@NonNull byte[] request, @NonNull Executor callbackExecutor,
            @NonNull Callback callback) {
        byte[] ownedRequest = request.clone();
        if (mClosed.get()) {
            fail(callbackExecutor, callback, new AgentTransportException(
                    AgentTransportException.Kind.CLOSED, "transport is closed"));
            return;
        }
        mNetworkExecutor.execute(() -> execute(ownedRequest, callbackExecutor, callback));
    }

    private void execute(byte[] request, Executor callbackExecutor, Callback callback) {
        if (mClosed.get()) {
            fail(callbackExecutor, callback, new AgentTransportException(
                    AgentTransportException.Kind.CLOSED, "transport is closed"));
            return;
        }
        HttpsURLConnection connection = null;
        try {
            String token = mTokenProvider.getAccessToken();
            if (token == null || token.trim().isEmpty() || !token.equals(token.trim())
                    || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new AgentTransportException(AgentTransportException.Kind.AUTHENTICATION,
                        "gateway token is absent or malformed");
            }
            connection = mConnectionFactory.open(mConfig.endpointUri());
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(mConfig.getConnectTimeoutMillis());
            connection.setReadTimeout(mConfig.getReadTimeoutMillis());
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new AgentTransportException(AgentTransportException.Kind.AUTHENTICATION,
                        "gateway authentication failed", status, null);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new AgentTransportException(AgentTransportException.Kind.HTTP,
                        "gateway returned HTTP " + status, status, null);
            }
            byte[] response;
            try (InputStream input = connection.getInputStream()) {
                response = readBounded(input, mConfig.getMaxResponseBytes());
            }
            if (mClosed.get()) {
                fail(callbackExecutor, callback, new AgentTransportException(
                        AgentTransportException.Kind.CLOSED, "transport closed during request"));
            } else {
                byte[] ownedResponse = response.clone();
                callbackExecutor.execute(() -> callback.onResponse(ownedResponse));
            }
        } catch (AgentTransportException failure) {
            fail(callbackExecutor, callback, failure);
        } catch (IOException failure) {
            fail(callbackExecutor, callback, new AgentTransportException(AgentTransportException.Kind.NETWORK,
                    "gateway network request failed", 0, failure));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int limit)
            throws IOException, AgentTransportException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new AgentTransportException(AgentTransportException.Kind.RESPONSE_TOO_LARGE,
                        "gateway response exceeds configured byte limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void fail(Executor callbackExecutor, Callback callback,
            AgentTransportException failure) {
        callbackExecutor.execute(() -> callback.onFailure(failure));
    }

    @Override
    public void close() {
        mClosed.set(true);
    }
}
