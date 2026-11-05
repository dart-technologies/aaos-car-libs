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

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.NonNull;

import art.dart.a2ui.agent.AgentActionOutcome;
import art.dart.a2ui.agent.AgentExecutionProfile;
import art.dart.a2ui.agent.AgentGatewayProtocol;
import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.agent.AgentTransportException;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Only turns are replayed; an action result's acknowledgement is never made up. */
public class FallbackAgentTransportTest {
    private static final byte[] TURN = AgentGatewayProtocol.encodeTurn(AgentProvider.CLAUDE,
            AgentExecutionProfile.REALTIME_LOW_LATENCY, "s", "t", "find coffee", 0, 0, true);
    private static final byte[] ACTION = AgentGatewayProtocol.encodeActionResult(
            AgentProvider.CLAUDE, "s", "t", "coffee", "play", "play", AgentActionOutcome.ALLOWED,
            null);

    @Test
    public void aFailedTurnReplaysTheLastGoodResponse() {
        Delegate gateway = new Delegate();
        FallbackAgentTransport transport = new FallbackAgentTransport(gateway);
        Result first = send(transport, TURN);
        gateway.answer(0, "good");
        assertThat(first.response).isEqualTo("good");
        assertThat(transport.consumeFallback()).isFalse();

        Result second = send(transport, TURN);
        gateway.fail(1, AgentTransportException.Kind.NETWORK);
        assertThat(second.response).isEqualTo("good");
        assertThat(transport.consumeFallback()).isTrue();

        Result closed = send(transport, TURN);
        gateway.fail(2, AgentTransportException.Kind.CLOSED);
        assertThat(closed.failure).isNotNull();
    }

    @Test
    public void aFailedActionResultIsAFailureEvenAfterAnIdenticalOneWasAcknowledged() {
        assertThat(FallbackAgentTransport.isTurn(TURN)).isTrue();
        assertThat(FallbackAgentTransport.isTurn(ACTION)).isFalse();
        Delegate gateway = new Delegate();
        FallbackAgentTransport transport = new FallbackAgentTransport(gateway);
        Result first = send(transport, ACTION);
        gateway.answer(0, "ack");
        assertThat(first.response).isEqualTo("ack");

        Result second = send(transport, ACTION);
        gateway.fail(1, AgentTransportException.Kind.NETWORK);
        assertThat(second.response).isNull();
        assertThat(second.failure.getKind()).isEqualTo(AgentTransportException.Kind.NETWORK);
        assertThat(transport.consumeFallback()).isFalse();
    }

    private static Result send(FallbackAgentTransport transport, byte[] request) {
        Result result = new Result();
        transport.send(request, Runnable::run, new AgentTransport.Callback() {
            @Override
            public void onResponse(@NonNull byte[] response) {
                result.response = new String(response, StandardCharsets.UTF_8);
            }

            @Override
            public void onFailure(@NonNull AgentTransportException failure) {
                result.failure = failure;
            }
        });
        return result;
    }

    private static final class Result {
        String response;
        AgentTransportException failure;
    }

    private static final class Delegate implements AgentTransport {
        final List<Callback> mCallbacks = new ArrayList<>();

        @Override
        public void send(@NonNull byte[] request, @NonNull Executor executor,
                @NonNull Callback callback) {
            mCallbacks.add(callback);
        }

        @Override
        public void close() {
        }

        void answer(int index, String body) {
            mCallbacks.get(index).onResponse(body.getBytes(StandardCharsets.UTF_8));
        }

        void fail(int index, AgentTransportException.Kind kind) {
            mCallbacks.get(index).onFailure(new AgentTransportException(kind, "test"));
        }
    }
}
