/*
 * Copyright (C) 2026 Dart Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package art.dart.a2ui.carui;

import androidx.annotation.NonNull;

import art.dart.a2ui.agent.AgentGatewayEnvelope;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Replays the last good response to a byte-identical TURN request when the gateway times out or
 * fails. Action results are never cached or replayed: an acknowledgement is not fabricated.
 *
 * <p>The replay goes back through {@code AgentSession} exactly like a fresh response: decoder,
 * identity binding, transactional apply. It is found only for an identical request, which is why
 * {@code AgentLane} derives turn ids from the ask rather than from a counter.
 */
final class FallbackAgentTransport implements AgentTransport {
    private static final int MAX_ENTRIES = 8;
    private static final byte[] TURN_PREFIX = ("{\"schema\":\"" + AgentGatewayEnvelope.SCHEMA
            + "\",\"type\":\"turn\"").getBytes(StandardCharsets.UTF_8);

    private final AgentTransport mDelegate;
    private final Map<String, byte[]> mLastGood =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };
    private boolean mLastWasFallback;

    FallbackAgentTransport(@NonNull AgentTransport delegate) {
        mDelegate = delegate;
    }

    @Override
    public void send(@NonNull byte[] request, @NonNull Executor callbackExecutor,
            @NonNull Callback callback) {
        if (!isTurn(request)) {
            mDelegate.send(request, callbackExecutor, callback);
            return;
        }
        String key = digest(request);
        mDelegate.send(request, callbackExecutor, new Callback() {
            @Override
            public void onResponse(@NonNull byte[] response) {
                synchronized (mLastGood) {
                    mLastGood.put(key, response.clone());
                    mLastWasFallback = false;
                }
                callback.onResponse(response);
            }

            @Override
            public void onFailure(@NonNull AgentTransportException failure) {
                byte[] cached;
                synchronized (mLastGood) {
                    cached = failure.getKind() == AgentTransportException.Kind.CLOSED
                            ? null : mLastGood.get(key);
                    mLastWasFallback = cached != null;
                }
                if (cached == null) {
                    callback.onFailure(failure);
                } else {
                    callback.onResponse(cached.clone());
                }
            }
        });
    }

    /** Whether the response just delivered was a replay. Read from inside the delivery. */
    boolean consumeFallback() {
        synchronized (mLastGood) {
            boolean was = mLastWasFallback;
            mLastWasFallback = false;
            return was;
        }
    }

    @Override
    public void close() {
        mDelegate.close();
    }

    /** AgentGatewayProtocol.encodeTurn always starts this way; an action result never does. */
    static boolean isTurn(byte[] request) {
        if (request.length < TURN_PREFIX.length) return false;
        for (int i = 0; i < TURN_PREFIX.length; i++) {
            if (request[i] != TURN_PREFIX[i]) return false;
        }
        return true;
    }

    private static String digest(byte[] request) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(request)) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
