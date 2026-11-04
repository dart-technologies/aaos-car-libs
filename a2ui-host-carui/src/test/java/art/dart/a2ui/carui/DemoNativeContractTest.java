/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

public class DemoNativeContractTest {

    @Test
    public void exactRecordedControlsResolveToTypedNativeSpecs() {
        assertThat(resolve("playback_settings", "quality", ComponentType.CHOICE_PICKER,
                "select").mKind).isEqualTo(DemoNativeContract.Kind.CHOICE);
        assertThat(resolve("playback_settings", "crossfade", ComponentType.SLIDER,
                "select").mMaximum).isEqualTo(12);
        assertThat(resolve("playback_settings", "sleep", ComponentType.DATE_TIME_INPUT,
                "edit").mKind).isEqualTo(DemoNativeContract.Kind.TIME);
        assertThat(resolve("poi_discovery_order", "route", ComponentType.CARD,
                "navigate").mKind).isEqualTo(DemoNativeContract.Kind.ROUTE);
        assertThat(resolve("browse_library", "list", ComponentType.LIST,
                "open").mKind).isEqualTo(DemoNativeContract.Kind.BROWSE);
        assertThat(resolve("big_buck_bunny", "watch", ComponentType.BUTTON,
                "open").mKind).isEqualTo(DemoNativeContract.Kind.VIDEO);
    }

    @Test
    public void labelsCannotSelectPrivilegedNativeBehaviour() {
        A2uiComponent attacker = new A2uiComponent.Builder(ComponentType.CARD)
                .setId("route").setTitle("Preview route").setAction("navigate").build();

        assertThat(DemoNativeContract.resolve("agent_surface", attacker)).isNull();
        assertThat(DemoNativeContract.resolve("big_buck_bunny", attacker)).isNull();
    }

    /** The pickup card and the weather glyph are exact too: same id elsewhere selects nothing. */
    @Test
    public void pickupOrderAndWeatherResolveOnlyOnTheirRecordedSurface() {
        assertThat(resolve("poi_discovery_order", "order", ComponentType.BUTTON,
                "purchase").mKind).isEqualTo(DemoNativeContract.Kind.ORDER);
        assertThat(resolve("agent_surface", "order", ComponentType.BUTTON, "purchase")).isNull();
        assertThat(resolve("poi_discovery_order", "order", ComponentType.BUTTON, "play"))
                .isNull();
        A2uiComponent status = new A2uiComponent.Builder(ComponentType.TEXT)
                .setId("status").setTitle("Curated snapshot").build();
        assertThat(DemoNativeContract.resolve("poi_discovery_order", status).mKind)
                .isEqualTo(DemoNativeContract.Kind.WEATHER);
        assertThat(DemoNativeContract.resolve("big_buck_bunny", status)).isNull();
    }

    @Test
    public void typeAndActionMustBothMatchTheHostContract() {
        assertThat(resolve("playback_settings", "crossfade", ComponentType.TEXT,
                "select")).isNull();
        assertThat(resolve("playback_settings", "crossfade", ComponentType.SLIDER,
                "purchase")).isNull();
    }

    private static DemoNativeContract.Spec resolve(String surface, String id,
            ComponentType type, String action) {
        return DemoNativeContract.resolve(surface, new A2uiComponent.Builder(type)
                .setId(id).setTitle(id).setAction(action).build());
    }
}
