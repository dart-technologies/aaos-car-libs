/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Proves every MVP case enters the identical bounded wire path for every provider. */
public class UseCaseCorpusGatewayParityTest {
    @Test
    public void allCasesReplayForEveryProvider() {
        int combinations = 0;
        for (AgentProvider provider : AgentProvider.values()) {
            for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
                AgentGatewayEnvelope envelope = new BoundedA2uiJsonDecoder().decode(
                        response(provider, useCase).getBytes(StandardCharsets.UTF_8));
                A2uiMessageProcessor processor = new A2uiMessageProcessor(
                        UseCaseCorpus.CATALOG_ID,
                        new HashSet<>(Collections.singletonList(UseCaseCorpus.VERSION)));
                processor.processAll(envelope.getMessages());
                assertThat(processor.getSurface(useCase.getId())).isNotNull();
                assertThat(processor.getSurface(useCase.getId()).project("root")).isNotNull();
                combinations++;
            }
        }
        assertThat(combinations)
                .isEqualTo(AgentProvider.values().length * UseCaseCorpus.all().size());
    }

    /** The Java enum, the gateway schema and the demo profiles must name the same providers. */
    @Test
    @SuppressWarnings("unchecked")
    public void providerSetMatchesGatewaySchemaAndProfiles() throws IOException {
        List<String> wire = new ArrayList<>();
        for (AgentProvider provider : AgentProvider.values()) {
            wire.add(provider.wireName());
        }
        Map<String, Object> schema = load("a2ui-automotive/agent-gateway-v1.schema.json");
        Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
        assertThat((List<Object>) ((Map<String, Object>) defs.get("provider")).get("enum"))
                .containsExactlyElementsIn(wire).inOrder();
        Map<String, Object> profiles = load("a2ui-automotive/demo/agent-profiles-v1.json");
        assertThat((List<Object>) profiles.get("selectors"))
                .containsExactlyElementsIn(wire).inOrder();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String relative) throws IOException {
        File dir = new File(".").getCanonicalFile();
        while (dir != null && !new File(dir, relative).isFile()) {
            dir = dir.getParentFile();
        }
        assertThat(dir).isNotNull();
        try (InputStream in = new FileInputStream(new File(dir, relative))) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    private static String response(AgentProvider provider, UseCaseCorpus.UseCase useCase) {
        StringBuilder out = new StringBuilder("{\"schema\":\"a2ui.gateway.v1\",\"provider\":");
        value(out, provider.wireName());
        out.append(",\"sessionId\":\"session\",\"turnId\":");
        value(out, useCase.getId());
        out.append(",\"messages\":[");
        for (int i = 0; i < useCase.getMessages().size(); i++) {
            if (i > 0) out.append(',');
            message(out, useCase.getMessages().get(i));
        }
        return out.append("]}").toString();
    }

    private static void message(StringBuilder out, A2uiMessage message) {
        out.append("{\"version\":");
        value(out, message.getVersion());
        switch (message.getKind()) {
            case CREATE_SURFACE:
                out.append(",\"createSurface\":{\"surfaceId\":");
                value(out, message.getSurfaceId());
                out.append(",\"catalogId\":");
                value(out, message.getCatalogId());
                out.append("}}");
                return;
            case UPDATE_COMPONENTS:
                out.append(",\"updateComponents\":{\"surfaceId\":");
                value(out, message.getSurfaceId());
                out.append(",\"components\":");
                value(out, message.getComponents());
                out.append("}}");
                return;
            case UPDATE_DATA_MODEL:
                out.append(",\"updateDataModel\":{\"surfaceId\":");
                value(out, message.getSurfaceId());
                out.append(",\"path\":");
                value(out, message.getPath());
                out.append(",\"value\":");
                value(out, message.getValue());
                out.append("}}");
                return;
            case DELETE_SURFACE:
            default:
                out.append(",\"deleteSurface\":{\"surfaceId\":");
                value(out, message.getSurfaceId());
                out.append("}}");
        }
    }

    @SuppressWarnings("unchecked")
    private static void value(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append('"');
            String string = (String) value;
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                if (c == '"' || c == '\\') out.append('\\');
                out.append(c);
            }
            out.append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof List) {
            out.append('[');
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                value(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                value(out, entry.getKey());
                out.append(':');
                value(out, entry.getValue());
            }
            out.append('}');
        } else {
            throw new AssertionError("unsupported fixture value " + value.getClass());
        }
    }
}
