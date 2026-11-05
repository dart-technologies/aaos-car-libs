/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.harness;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.protocol.A2uiMessage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The provider-run verifier itself, on the corpus replayed as if each provider had sent it. */
public class GatewayOutputVerificationTest {
    /** A gateway 429, byte for byte as tools/a2ui-gateway writes one. */
    private static final String RATE_LIMITED = "{\"schema\":\"a2ui.gateway.v1\","
            + "\"provider\":\"chatgpt\",\"sessionId\":\"compare\",\"turnId\":\"coffee\","
            + "\"error\":{\"code\":\"upstream\",\"detail\":\"http_429.insufficient_quota\"}}";
    /** Video and a keyboard field beside glanceable rows. */
    private static final String MIXED = "{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"grok\","
            + "\"sessionId\":\"s\",\"turnId\":\"t\",\"messages\":[{\"version\":\"v0.9\","
            + "\"createSurface\":{\"surfaceId\":\"x\",\"catalogId\":\"automotive\"}},"
            + "{\"version\":\"v0.9\",\"updateComponents\":{\"surfaceId\":\"x\",\"components\":["
            + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"name\",\"clip\","
            + "\"field\",\"order\",\"play\"]},"
            + "{\"id\":\"name\",\"component\":\"Text\",\"title\":\"Cafe\"},"
            + "{\"id\":\"clip\",\"component\":\"Video\",\"title\":\"Tour\"},"
            + "{\"id\":\"field\",\"component\":\"TextField\",\"title\":\"Search\","
            + "\"action\":\"search\"},"
            + "{\"id\":\"order\",\"component\":\"Button\",\"title\":\"Order\","
            + "\"action\":\"purchase\"},"
            + "{\"id\":\"play\",\"component\":\"Button\",\"title\":\"Play\",\"action\":\"play\"}"
            + "]}}]}";

    @Rule public final TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void everyCorpusCaseIsAValidRunThatHoldsTheHostPipelineInvariants() {
        for (AgentProvider provider : AgentProvider.values()) {
            for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
                GatewayOutputVerification.Run run =
                        GatewayOutputVerification.verifyOne(provider, envelope(provider, useCase));
                assertWithMessage(useCase.getId()).that(run.invalid).isNull();
                assertThat(run.surfaces).isEqualTo(1);
                assertThat(run.leaks).isEqualTo(0);
                assertThat(run.policyViolations).isEqualTo(0);
                assertThat(run.rows.keySet()).containsAtLeast("untrusted", "0x0", "0x1ff");
            }
        }
    }

    @Test
    public void restrictionsReallyPrune() {
        GatewayOutputVerification.Run trailer = GatewayOutputVerification.verifyOne(
                AgentProvider.MUSE, envelope(AgentProvider.MUSE, case_("video_trailer")));
        assertThat(trailer.pruned.get("0x0")).isEqualTo(0);
        assertThat(trailer.pruned.get("0x10")).isGreaterThan(0);
        assertThat(trailer.rows.get("0x10")).isLessThan(trailer.rows.get("0x0"));
        GatewayOutputVerification.Run nowPlaying = GatewayOutputVerification.verifyOne(
                AgentProvider.CLAUDE, envelope(AgentProvider.CLAUDE, case_("now_playing")));
        assertThat(nowPlaying.rows.get("0x1ff")).isGreaterThan(0);
        assertThat(nowPlaying.rows.get("untrusted")).isGreaterThan(0);
    }

    @Test
    public void videoAndTextEntryAreMeasurablyPrunedUnderTheirMasks() {
        GatewayOutputVerification.Run run =
                GatewayOutputVerification.verifyOne(AgentProvider.GROK, bytes(MIXED));
        assertThat(run.valid()).isTrue();
        assertThat(run.pruned.get("0x0")).isEqualTo(0);
        assertThat(run.pruned.get("0x10")).isEqualTo(1);
        assertThat(run.pruned.get("0x8")).isEqualTo(1);
        assertThat(run.fitness("0x10")).isLessThan(1.0);
        assertThat(run.fitness("0x8")).isLessThan(1.0);
        assertThat(run.fitness("0x0")).isEqualTo(1.0);
        assertThat(run.leaks).isEqualTo(0);
        assertThat(run.consequential).isEqualTo(1);
        assertThat(run.policyViolations).isEqualTo(0);
    }

    @Test
    public void aWeakenedPrunerBreaksTheHostPipelineInvariant() throws IOException {
        // Baseline's allowed set for every mask: what a pruner that ignores the reading does.
        GatewayOutputVerification.Pipeline weakened = (projected, mask, trustworthy) -> {
            A2uiComponent tree = A2uiPruner.prune(projected, A2uiRestrictionPolicy
                    .allowedComponents(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
            return tree == null ? null
                    : A2uiPruner.applyLimits(tree, A2uiValueLimits.from(mask, 120, 3, 3));
        };
        assertThat(GatewayOutputVerification.verifyOne(AgentProvider.GROK, bytes(MIXED), weakened)
                .leaks).isGreaterThan(0);

        File runs = runs("grok/realtime_low_latency/mixed.json", MIXED);
        GatewayOutputVerification.verifyDirectory(runs, GatewayOutputVerification.HOST)
                .check("host");
        AssertionError failed = assertThrows(AssertionError.class,
                () -> GatewayOutputVerification.verifyDirectory(runs, weakened).check("weak"));
        assertThat(failed).hasMessageThat().contains("host pipeline invariant");
    }

    @Test
    public void aRateLimitedRunIsInvalidAndCountedNotPassed() throws IOException {
        GatewayOutputVerification.Run run = GatewayOutputVerification.verifyOne(
                AgentProvider.CHATGPT, bytes(RATE_LIMITED));
        assertThat(run.valid()).isFalse();
        assertThat(run.invalid).isEqualTo("gateway_error:upstream:http_429.insufficient_quota");
        assertThat(run.corrupt).isFalse();
        GatewayOutputVerification.Run misfiled = GatewayOutputVerification.verifyOne(
                AgentProvider.GROK, bytes(RATE_LIMITED));
        assertThat(misfiled.invalid).isEqualTo("provider_mismatch");
        assertThat(misfiled.corrupt).isTrue();

        File only = runs("chatgpt/realtime_low_latency/coffee.json", RATE_LIMITED);
        GatewayOutputVerification.Report alone =
                GatewayOutputVerification.verifyDirectory(only, GatewayOutputVerification.HOST);
        assertThat(alone.valid).isEqualTo(0);
        assertThat(alone.invalidByProvider).containsExactly("chatgpt", 1);
        assertThrows(AssertionError.class, () -> alone.check("only a 429"));

        // Beside a valid run the gate passes, and the 429 is still on the record.
        write(only, "grok/realtime_low_latency/mixed.json", MIXED);
        GatewayOutputVerification.Report mixed =
                GatewayOutputVerification.verifyDirectory(only, GatewayOutputVerification.HOST);
        mixed.check("a 429 and a valid run");
        assertThat(mixed.valid).isEqualTo(1);
        assertThat(mixed.invalidByProvider).containsExactly("chatgpt", 1);
        assertThat(mixed.json()).contains("\"invalidByProvider\":{\"chatgpt\":1}");
    }

    @Test
    public void aCorruptFileFailsTheGateEvenBesideValidRuns() throws IOException {
        File runs = runs("grok/realtime_low_latency/mixed.json", MIXED);
        write(runs, "claude/quality_cached/coffee.json", MIXED.substring(0, MIXED.length() / 2));
        GatewayOutputVerification.Report report =
                GatewayOutputVerification.verifyDirectory(runs, GatewayOutputVerification.HOST);
        assertThat(report.valid).isEqualTo(1);
        assertThat(report.corrupt).isEqualTo(1);
        assertThat(report.invalidByProvider).containsExactly("claude", 1);
        assertThrows(AssertionError.class, () -> report.check("corrupt"));
        // A response filed under the wrong provider is corrupt too.
        assertThat(GatewayOutputVerification.verifyOne(AgentProvider.GROK,
                envelope(AgentProvider.MUSE, case_("now_playing"))).invalid)
                .isEqualTo("provider_mismatch");
    }

    @Test
    public void aRootlessSurfaceIsInvalidNotThrown() {
        GatewayOutputVerification.Run rootless = GatewayOutputVerification.verifyOne(
                AgentProvider.GROK, bytes("{\"schema\":\"a2ui.gateway.v1\",\"provider\":\"grok\","
                        + "\"sessionId\":\"s\",\"turnId\":\"t\",\"source\":\"fallback\","
                        + "\"messages\":[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":"
                        + "\"x\",\"catalogId\":\"automotive\"}},{\"version\":\"v0.9\","
                        + "\"updateComponents\":{\"surfaceId\":\"x\",\"components\":["
                        + "{\"id\":\"main\",\"component\":\"Text\"}]}}]}"));
        assertThat(rootless.surfaces).isEqualTo(0);
        assertThat(rootless.invalid).isEqualTo("project.integrity");
        assertThat(rootless.corrupt).isFalse();
        assertThat(rootless.source).isEqualTo("fallback");
    }

    private File runs(String relative, String body) throws IOException {
        File root = mFolder.newFolder();
        write(root, relative, body);
        return root;
    }

    private static void write(File root, String relative, String body) throws IOException {
        File file = new File(root, relative);
        assertThat(file.getParentFile().isDirectory() || file.getParentFile().mkdirs()).isTrue();
        Files.write(file.toPath(), bytes(body));
    }

    private static UseCaseCorpus.UseCase case_(String id) {
        UseCaseCorpus.UseCase useCase = UseCaseCorpus.byId(id);
        assertThat(useCase).isNotNull();
        return useCase;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] envelope(AgentProvider provider, UseCaseCorpus.UseCase useCase) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("schema", "a2ui.gateway.v1");
        env.put("provider", provider.wireName());
        env.put("sessionId", "session");
        env.put("turnId", useCase.getId());
        List<Object> messages = new ArrayList<>();
        for (A2uiMessage message : useCase.getMessages()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("surfaceId", message.getSurfaceId());
            String verb;
            switch (message.getKind()) {
                case CREATE_SURFACE:
                    verb = "createSurface";
                    body.put("catalogId", message.getCatalogId());
                    break;
                case UPDATE_COMPONENTS:
                    verb = "updateComponents";
                    body.put("components", message.getComponents());
                    break;
                case UPDATE_DATA_MODEL:
                    verb = "updateDataModel";
                    body.put("path", message.getPath());
                    body.put("value", message.getValue());
                    break;
                case DELETE_SURFACE:
                default:
                    verb = "deleteSurface";
            }
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("version", message.getVersion());
            wire.put(verb, body);
            messages.add(wire);
        }
        env.put("messages", messages);
        return HarnessJson.value(env).getBytes(StandardCharsets.UTF_8);
    }
}
