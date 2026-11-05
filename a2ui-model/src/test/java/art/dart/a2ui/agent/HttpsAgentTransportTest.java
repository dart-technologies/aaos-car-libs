/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

public class HttpsAgentTransportTest {
    private static final Executor DIRECT = Runnable::run;

    @Test
    public void successPostsAuthenticatedJsonAndDeliversOwnedResponse() throws Exception {
        FakeConnection connection = new FakeConnection(200, "{\"ok\":true}".getBytes(
                StandardCharsets.UTF_8));
        RecordingCallback callback = new RecordingCallback();
        HttpsAgentTransport transport = transport(connection, () -> "short-lived-token", 1024);

        byte[] request = "{\"request\":1}".getBytes(StandardCharsets.UTF_8);
        transport.send(request, DIRECT, callback);
        request[0] = 'X';

        assertThat(new String(connection.output.toByteArray(), StandardCharsets.UTF_8))
                .isEqualTo("{\"request\":1}");
        assertThat(connection.getRequestProperty("Authorization"))
                .isEqualTo("Bearer short-lived-token");
        assertThat(connection.getRequestProperty("Content-Type"))
                .isEqualTo("application/json; charset=utf-8");
        assertThat(connection.getRequestProperty("Accept")).isEqualTo("application/json");
        assertThat(connection.getInstanceFollowRedirects()).isFalse();
        assertThat(connection.disconnected).isTrue();
        assertThat(callback.responses).hasSize(1);
        assertThat(callback.failures).isEmpty();
    }

    @Test
    public void authenticationAndHttpFailuresNeverExposeResponseBody() throws Exception {
        for (int status : new int[]{401, 403, 302, 500}) {
            RecordingCallback callback = new RecordingCallback();
            transport(new FakeConnection(status, "secret body".getBytes(StandardCharsets.UTF_8)),
                    () -> "token", 1024).send(new byte[0], DIRECT, callback);
            assertThat(callback.failures).hasSize(1);
            AgentTransportException failure = callback.failures.get(0);
            assertThat(failure.getHttpStatus()).isEqualTo(status);
            assertThat(failure.getMessage()).doesNotContain("secret body");
            if (status == 401 || status == 403) {
                assertThat(failure.getKind())
                        .isEqualTo(AgentTransportException.Kind.AUTHENTICATION);
            } else {
                assertThat(failure.getKind()).isEqualTo(AgentTransportException.Kind.HTTP);
            }
        }
    }

    @Test
    public void rejectsMissingOrHeaderInjectingToken() throws Exception {
        for (String token : new String[]{null, "", " token", "token\nInjected: true"}) {
            RecordingCallback callback = new RecordingCallback();
            transport(new FakeConnection(200, new byte[0]), () -> token, 1024)
                    .send(new byte[0], DIRECT, callback);
            assertThat(callback.failures.get(0).getKind())
                    .isEqualTo(AgentTransportException.Kind.AUTHENTICATION);
        }
    }

    @Test
    public void propagatesRedactedTokenFailureAndNetworkFailure() throws Exception {
        RecordingCallback tokenCallback = new RecordingCallback();
        transport(new FakeConnection(200, new byte[0]), () -> {
            throw new AgentTransportException(AgentTransportException.Kind.AUTHENTICATION,
                    "token unavailable");
        }, 1024).send(new byte[0], DIRECT, tokenCallback);
        assertThat(tokenCallback.failures.get(0).getMessage()).isEqualTo("token unavailable");

        FakeConnection broken = new FakeConnection(200, new byte[0]);
        broken.outputFailure = new IOException("socket failed");
        RecordingCallback networkCallback = new RecordingCallback();
        transport(broken, () -> "token", 1024).send(new byte[0], DIRECT, networkCallback);
        assertThat(networkCallback.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.NETWORK);
        assertThat(networkCallback.failures.get(0).getMessage())
                .isEqualTo("gateway network request failed");
    }

    @Test
    public void boundsResponseAndFailsPendingDeliveryAfterClose() throws Exception {
        RecordingCallback largeCallback = new RecordingCallback();
        transport(new FakeConnection(200, new byte[5]), () -> "token", 4)
                .send(new byte[0], DIRECT, largeCallback);
        assertThat(largeCallback.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.RESPONSE_TOO_LARGE);

        final HttpsAgentTransport[] holder = new HttpsAgentTransport[1];
        FakeConnection closesOnRead = new FakeConnection(200, new byte[]{1});
        closesOnRead.readHook = () -> holder[0].close();
        holder[0] = transport(closesOnRead, () -> "token", 4);
        RecordingCallback closedDuring = new RecordingCallback();
        holder[0].send(new byte[0], DIRECT, closedDuring);
        assertThat(closedDuring.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.CLOSED);
    }

    @Test
    public void closedTransportRejectsWithoutOpeningConnection() throws Exception {
        FakeConnection connection = new FakeConnection(200, new byte[0]);
        HttpsAgentTransport transport = transport(connection, () -> "token", 4);
        transport.close();
        RecordingCallback callback = new RecordingCallback();
        transport.send(new byte[0], DIRECT, callback);
        assertThat(callback.failures.get(0).getKind())
                .isEqualTo(AgentTransportException.Kind.CLOSED);
        assertThat(connection.connectedForOutput).isFalse();
    }

    private static HttpsAgentTransport transport(FakeConnection connection,
            AccessTokenProvider tokens, int responseLimit) {
        AgentGatewayConfig config = new AgentGatewayConfig("https://gateway.example/v1",
                100, 200, responseLimit);
        return new HttpsAgentTransport(config, tokens, DIRECT, endpoint -> connection);
    }

    private static final class RecordingCallback implements AgentTransport.Callback {
        final List<byte[]> responses = new ArrayList<>();
        final List<AgentTransportException> failures = new ArrayList<>();
        @Override public void onResponse(byte[] response) { responses.add(response); }
        @Override public void onFailure(AgentTransportException failure) { failures.add(failure); }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        final int status;
        final byte[] response;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOException outputFailure;
        Runnable readHook;
        boolean disconnected;
        boolean connectedForOutput;

        FakeConnection(int status, byte[] response) throws Exception {
            super(new URL("https://gateway.example/v1"));
            this.status = status;
            this.response = response;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            connectedForOutput = true;
            if (outputFailure != null) throw outputFailure;
            return output;
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() {
            if (readHook != null) readHook.run();
            return new ByteArrayInputStream(response);
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "TLS_FAKE"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[0];
        }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
    }
}
