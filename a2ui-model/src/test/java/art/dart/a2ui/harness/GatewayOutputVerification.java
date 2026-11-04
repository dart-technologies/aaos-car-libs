/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.harness;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import androidx.annotation.Nullable;

import art.dart.a2ui.agent.AgentExecutionProfile;
import art.dart.a2ui.agent.AgentGatewayEnvelope;
import art.dart.a2ui.agent.AgentProvider;
import art.dart.a2ui.agent.BoundedA2uiJsonDecoder;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.policy.A2uiVisibleTree;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiProtocolException;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts every recorded provider response through the vehicle path (bounded decode, transactional
 * apply, projection) and Host B's prune and value limits under each restriction mask.
 *
 * <p>A run is VALID when it decodes, applies and has a projectable surface; otherwise it is
 * INVALID with a reason, counted per provider. Quality is measured on valid runs: rows surviving
 * and nodes pruned under each mask against baseline, and consequential actions offered at all.
 *
 * <p>Fails when no run is valid, when a file is not a gateway envelope at all, or on a host
 * pipeline invariant: a component the conformance table forbids left in a rendered tree, or a
 * consequential action offered while restricted. The forbidden sets are restated from
 * a2ui-automotive/conformance/automotive.yaml, not read from the policy under test.
 *
 * <p>Run only by {@code :a2ui-model:verifyGatewayOutputs} (scripts/compare-providers.py).
 */
public class GatewayOutputVerification {
    /** Host B's demo-console levels, plus the two bits the corpus names. */
    static final int[] MASKS = {
            A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
            A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED,
    };
    static final String UNTRUSTED = "untrusted";
    static final String BASELINE = "0x0";
    private static final int FULLY = 0x1ff;
    /** The vehicle limits Host B's DEMO injection uses: platform defaults, three items. */
    private static final int STRING_LENGTH = 120;
    private static final int CONTENT_ITEMS = 3;
    private static final int CONTENT_DEPTH = 3;
    private static final Set<ComponentType> GLANCEABLE = EnumSet.of(ComponentType.TEXT,
            ComponentType.ICON, ComponentType.ROW, ComponentType.COLUMN, ComponentType.DIVIDER,
            ComponentType.BUTTON);
    /** A gateway error envelope, exactly as tools/a2ui-gateway writes it. */
    private static final Pattern GATEWAY_ERROR = Pattern.compile(
            "\\{\"schema\":\"a2ui\\.gateway\\.v1\"(?:,\"(?:provider|sessionId|turnId)\":"
                    + "\"[A-Za-z0-9._:-]+\")*,\"error\":\\{\"code\":\"([a-z_]+)\""
                    + "(?:,\"detail\":\"([A-Za-z0-9_.:-]+)\")?}}");
    /** An error filed under another provider would count against the wrong one. */
    private static final Pattern ERROR_PROVIDER = Pattern.compile("^\\{[^{]*\"provider\":\"([a-z]+)\"");

    /** The pipeline under test. */
    interface Pipeline {
        @Nullable
        A2uiComponent render(A2uiComponent projected, int mask, boolean trustworthy);
    }

    /** Host B's renderFor: the provenance-gated allowed set, prune, then value limits. */
    static final Pipeline HOST = (projected, mask, trustworthy) -> {
        Set<ComponentType> allowed = trustworthy ? A2uiRestrictionPolicy.allowedComponents(mask)
                : A2uiRestrictionPolicy.failClosedComponents();
        A2uiValueLimits limits = trustworthy
                ? A2uiValueLimits.from(mask, STRING_LENGTH, CONTENT_ITEMS, CONTENT_DEPTH)
                : A2uiValueLimits.failClosed();
        A2uiComponent tree = A2uiPruner.prune(projected, allowed);
        return tree == null ? null : A2uiPruner.applyLimits(tree, limits);
    };

    static final class Run {
        /** Null for a valid run; otherwise why not. */
        @Nullable String invalid;
        /** Not a gateway envelope at all: the run directory is not what the gateway wrote. */
        boolean corrupt;
        /** The envelope's source (live, cache, fallback); a fallback is not the provider's answer. */
        @Nullable String source;
        int surfaces;
        int nodes;
        int consequential;
        int leaks;
        int policyViolations;
        final Map<String, Integer> rows = new LinkedHashMap<>();
        final Map<String, Integer> pruned = new LinkedHashMap<>();

        boolean valid() {
            return invalid == null;
        }

        /** Rows surviving under {@code label} as a share of baseline rows: glanceability. */
        double fitness(String label) {
            int base = rows.getOrDefault(BASELINE, 0);
            return base == 0 ? 0 : rows.getOrDefault(label, 0) / (double) base;
        }
    }

    static final class Report {
        int valid;
        int corrupt;
        int leaks;
        int policyViolations;
        final Map<String, Integer> invalidByProvider = new TreeMap<>();
        final List<String> runs = new ArrayList<>();

        /** The gate. */
        void check(String where) {
            assertWithMessage("%s file(s) under %s are not gateway envelopes", corrupt, where)
                    .that(corrupt).isEqualTo(0);
            assertWithMessage("no valid provider run under %s (invalid: %s)", where,
                    invalidByProvider).that(valid).isGreaterThan(0);
            assertWithMessage("host pipeline invariant: forbidden components rendered")
                    .that(leaks).isEqualTo(0);
            assertWithMessage("host pipeline invariant: consequential action offered while "
                    + "restricted").that(policyViolations).isEqualTo(0);
        }

        String json() {
            StringBuilder out = new StringBuilder("{\"masks\":[");
            for (int i = 0; i < MASKS.length; i++) {
                out.append(i == 0 ? "" : ",").append(HarnessJson.quote(label(MASKS[i])));
            }
            out.append("],\"valid\":").append(valid).append(",\"corrupt\":").append(corrupt)
                    .append(",\"invalidByProvider\":").append(counts(invalidByProvider))
                    .append(",\"hostPipelineInvariants\":{\"leaks\":").append(leaks)
                    .append(",\"policyViolations\":").append(policyViolations).append("}")
                    .append(",\"runs\":[").append(String.join(",\n", runs)).append("]}\n");
            return out.toString();
        }
    }

    @Test
    public void verify() throws IOException {
        String runs = System.getProperty("a2ui.providerRuns");
        assumeTrue("set by :a2ui-model:verifyGatewayOutputs", runs != null);
        Report report = verifyDirectory(new File(runs), HOST);
        String path = System.getProperty("a2ui.providerVerification",
                new File(runs, "verification.json").getPath());
        Files.write(new File(path).toPath(), report.json().getBytes(StandardCharsets.UTF_8));
        report.check(runs);
    }

    static Report verifyDirectory(File root, Pipeline pipeline) throws IOException {
        Report report = new Report();
        for (AgentProvider provider : AgentProvider.values()) {
            for (AgentExecutionProfile profile : AgentExecutionProfile.values()) {
                File[] files = new File(root, provider.wireName() + "/" + profile.wireName())
                        .listFiles((dir, name) -> name.endsWith(".json"));
                if (files == null) continue;
                Arrays.sort(files);
                for (File file : files) {
                    Run run = verifyOne(provider, Files.readAllBytes(file.toPath()), pipeline);
                    String caseId = file.getName().substring(0, file.getName().length() - 5);
                    report.runs.add(json(provider, profile, caseId, run));
                    if (run.valid()) {
                        report.valid++;
                    } else {
                        report.invalidByProvider.merge(provider.wireName(), 1, Integer::sum);
                    }
                    if (run.corrupt) report.corrupt++;
                    report.leaks += run.leaks;
                    report.policyViolations += run.policyViolations;
                }
            }
        }
        return report;
    }

    static Run verifyOne(AgentProvider provider, byte[] response) {
        return verifyOne(provider, response, HOST);
    }

    static Run verifyOne(AgentProvider provider, byte[] response, Pipeline pipeline) {
        Run run = new Run();
        AgentGatewayEnvelope envelope;
        try {
            envelope = new BoundedA2uiJsonDecoder().decode(response);
        } catch (A2uiProtocolException notASurface) {
            String text = new String(response, StandardCharsets.UTF_8);
            Matcher error = GATEWAY_ERROR.matcher(text);
            Matcher named = ERROR_PROVIDER.matcher(text);
            if (error.matches() && named.find() && !named.group(1).equals(provider.wireName())) {
                run.invalid = "provider_mismatch";
                run.corrupt = true;
            } else if (error.matches()) {
                run.invalid = "gateway_error:" + error.group(1)
                        + (error.group(2) == null ? "" : ":" + error.group(2));
            } else {
                run.invalid = "corrupt";
                run.corrupt = true;
            }
            return run;
        }
        if (envelope.getProvider() != provider) {
            run.invalid = "provider_mismatch";
            run.corrupt = true;
            return run;
        }
        run.source = envelope.getSource();
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        try {
            processor.processAll(envelope.getMessages());
        } catch (A2uiProtocolException refused) {
            run.invalid = "apply." + category(refused);
            return run;
        }
        String unprojectable = null;
        for (String surfaceId : processor.getSurfaces().keySet()) {
            A2uiComponent projected;
            try {
                projected = processor.getSurface(surfaceId).project(DemoTrace.ROOT_ID);
            } catch (A2uiProtocolException failed) {
                unprojectable = "project." + category(failed);
                continue;
            }
            run.surfaces++;
            run.nodes += nodes(projected);
            run.consequential += consequential(projected);
            render(run, UNTRUSTED, projected, FULLY, false, pipeline);
            for (int mask : MASKS) {
                render(run, label(mask), projected, mask, true, pipeline);
            }
        }
        if (run.surfaces == 0) {
            run.invalid = unprojectable == null ? "no_surface" : unprojectable;
        }
        return run;
    }

    private static void render(Run run, String label, A2uiComponent projected, int mask,
            boolean trustworthy, Pipeline pipeline) {
        A2uiComponent tree = pipeline.render(projected, mask, trustworthy);
        run.leaks += count(tree, forbiddenBy(mask, trustworthy));
        boolean restricting = !trustworthy
                || mask != A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
        for (A2uiComponent row : A2uiVisibleTree.renderableRows(tree)) {
            if (!row.isInteractive()) continue;
            A2uiActionClass actionClass =
                    A2uiActionPolicy.classify(row.getAction(), row.getActionClass());
            if (restricting && actionClass.isAtLeast(A2uiActionClass.CONSEQUENTIAL)
                    && A2uiActionPolicy.decide(actionClass, mask, trustworthy)
                            != A2uiActionPolicy.Decision.REFUSE) {
                run.policyViolations++;
            }
        }
        run.rows.merge(label, A2uiVisibleTree.renderableRows(tree).size(), Integer::sum);
        run.pruned.merge(label, nodes(projected) - nodes(tree), Integer::sum);
    }

    /** The conformance table, restated: what each level removes. Untrusted means 0x1ff. */
    static Set<ComponentType> forbiddenBy(int mask, boolean trustworthy) {
        if (!trustworthy || (mask & FULLY) == FULLY) {
            return EnumSet.complementOf(EnumSet.copyOf(GLANCEABLE));
        }
        Set<ComponentType> forbidden = EnumSet.noneOf(ComponentType.class);
        if ((mask & A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING) != 0) {
            forbidden.addAll(EnumSet.of(ComponentType.TEXT_FIELD, ComponentType.CHOICE_PICKER));
        }
        if ((mask & A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD) != 0) {
            forbidden.addAll(EnumSet.of(ComponentType.TEXT_FIELD, ComponentType.DATE_TIME_INPUT));
        }
        if ((mask & A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO) != 0) {
            forbidden.add(ComponentType.VIDEO);
        }
        if ((mask & A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT) != 0) {
            forbidden.addAll(EnumSet.of(ComponentType.VIDEO, ComponentType.TABS,
                    ComponentType.MODAL));
        }
        if ((mask & A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP) != 0) {
            forbidden.addAll(EnumSet.of(ComponentType.TEXT_FIELD, ComponentType.CHECK_BOX,
                    ComponentType.CHOICE_PICKER, ComponentType.SLIDER,
                    ComponentType.DATE_TIME_INPUT));
        }
        return forbidden;
    }

    private static int consequential(A2uiComponent node) {
        int count = node.isInteractive() && A2uiActionPolicy.classify(node.getAction(),
                node.getActionClass()).isAtLeast(A2uiActionClass.CONSEQUENTIAL) ? 1 : 0;
        for (A2uiComponent child : node.getChildren()) count += consequential(child);
        return count;
    }

    private static int count(@Nullable A2uiComponent node, Set<ComponentType> types) {
        if (node == null) return 0;
        int count = types.contains(node.getType()) ? 1 : 0;
        for (A2uiComponent child : node.getChildren()) count += count(child, types);
        return count;
    }

    private static int nodes(@Nullable A2uiComponent node) {
        if (node == null) return 0;
        int count = 1;
        for (A2uiComponent child : node.getChildren()) count += nodes(child);
        return count;
    }

    private static String category(A2uiProtocolException failure) {
        return failure.getCategory().name().toLowerCase(Locale.ROOT);
    }

    static String label(int mask) {
        return "0x" + Integer.toHexString(mask);
    }

    private static String json(AgentProvider provider, AgentExecutionProfile profile,
            String caseId, Run run) {
        Map<String, Object> fitness = new LinkedHashMap<>();
        for (String label : run.rows.keySet()) {
            fitness.put(label, Math.round(run.fitness(label) * 100) / 100.0);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("provider", provider.wireName());
        fields.put("profile", profile.wireName());
        fields.put("case", caseId);
        fields.put("source", run.source);
        fields.put("valid", run.valid());
        fields.put("invalid", run.invalid);
        fields.put("surfaces", run.surfaces);
        fields.put("nodes", run.nodes);
        fields.put("consequentialOffered", run.consequential);
        fields.put("rows", new LinkedHashMap<String, Object>(run.rows));
        fields.put("pruned", new LinkedHashMap<String, Object>(run.pruned));
        fields.put("fitness", fitness);
        fields.put("hostPipelineInvariants", invariants(run));
        return HarnessJson.value(fields);
    }

    private static Map<String, Object> invariants(Run run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("leaks", run.leaks);
        map.put("policyViolations", run.policyViolations);
        return map;
    }

    private static String counts(Map<String, Integer> values) {
        return HarnessJson.value(new LinkedHashMap<String, Object>(values));
    }
}
