/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.agent;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

public class TrustedActionRegistryTest {
    @Test
    public void hostClassificationOverridesSafeLookingActionName() {
        int[] calls = {0};
        TrustedActionRegistry registry = new TrustedActionRegistry.Builder()
                .addAction("play", A2uiActionClass.CONSEQUENTIAL,
                        (surface, component, payload) -> calls[0]++)
                .build();
        A2uiMessageProcessor processor = processorWithAction("play");
        processor.setActionClassResolver(registry);
        processor.setActionSink(registry);
        processor.setRestrictionReader(baseline());

        A2uiActionPolicy.Decision first = processor.dispatchAction("s", "button", "play", null);
        A2uiActionPolicy.Decision confirmed =
                processor.confirmAction("s", "button", "play", null, null);

        assertThat(first).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(confirmed).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    public void unknownActionNeverReachesHandlerEvenAfterConfirmation() {
        int[] rejected = {0};
        TrustedActionRegistry registry = new TrustedActionRegistry.Builder()
                .setRejectionListener(action -> rejected[0]++)
                .build();
        A2uiMessageProcessor processor = processorWithAction("play");
        processor.setActionClassResolver(registry);
        processor.setActionSink(registry);
        processor.setRestrictionReader(baseline());

        assertThat(processor.dispatchAction("s", "button", "play", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(processor.confirmAction("s", "button", "play", null, null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(rejected[0]).isEqualTo(1);
    }

    @Test
    public void duplicateAndNonCanonicalIdsAreRejected() {
        TrustedActionRegistry.Builder builder = new TrustedActionRegistry.Builder()
                .addAction("x", A2uiActionClass.SAFE, (a, b, c) -> { });
        assertThrows(IllegalArgumentException.class,
                () -> builder.addAction("x", A2uiActionClass.SAFE, (a, b, c) -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedActionRegistry.Builder().addAction(
                        " x", A2uiActionClass.SAFE, (a, b, c) -> { }));
    }

    @Test
    public void knownSafeActionDispatchesAndAgentMayOnlyRaiseItsClass() {
        int[] calls = {0};
        TrustedActionRegistry registry = new TrustedActionRegistry.Builder()
                .addAction("media.pause", A2uiActionClass.SAFE,
                        (surface, component, payload) -> calls[0]++)
                .build();
        assertThat(registry.hasAction("media.pause")).isTrue();
        assertThat(registry.hasAction("unknown")).isFalse();
        assertThat(registry.resolve("media.pause", null)).isEqualTo(A2uiActionClass.SAFE);
        assertThat(registry.resolve("media.pause", A2uiActionClass.CONSEQUENTIAL))
                .isEqualTo(A2uiActionClass.CONSEQUENTIAL);
        registry.onAction("s", "c", "media.pause", null);
        assertThat(calls[0]).isEqualTo(1);
    }

    private static A2uiMessageProcessor processorWithAction(String action) {
        A2uiMessageProcessor p = new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList("v0.9")));
        p.process(A2uiMessage.createSurface("v0.9", "s", "automotive"));
        Map<String, Object> component = A2uiMessage.component("button", "Button");
        component.put("action", action);
        p.process(A2uiMessage.updateComponents("v0.9", "s", Arrays.asList(component)));
        return p;
    }

    private static A2uiMessageProcessor.RestrictionReader baseline() {
        return new A2uiMessageProcessor.RestrictionReader() {
            @Override public int activeRestrictions() { return 0; }
            @Override public boolean isTrustworthy() { return true; }
        };
    }
}
