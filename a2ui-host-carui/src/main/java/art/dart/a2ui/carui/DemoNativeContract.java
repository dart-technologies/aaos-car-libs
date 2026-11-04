/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.carui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host-owned native behaviour for the recorded OEM corpus.
 *
 * <p>This is deliberately an exact registry over surface id, component id, type, and action. An
 * agent cannot obtain a slider, route launch, or media player by choosing persuasive visible text.
 * It is a demo contract, not a claim that v0.9 defines the typed properties these widgets need.
 */
final class DemoNativeContract {

    enum Kind { CHOICE, SLIDER, TIME, BROWSE, ROUTE, IMAGE, VIDEO, AUDIO, MODAL, ORDER, WEATHER }

    static final class Spec {
        final Kind mKind;
        final String mTitle;
        final List<String> mChoices;
        final int mMinimum;
        final int mMaximum;
        final int mInitial;

        private Spec(Kind kind, String title, List<String> choices,
                int minimum, int maximum, int initial) {
            mKind = kind;
            mTitle = title;
            mChoices = Collections.unmodifiableList(choices);
            mMinimum = minimum;
            mMaximum = maximum;
            mInitial = initial;
        }

        static Spec simple(Kind kind, String title) {
            return new Spec(kind, title, Collections.emptyList(), 0, 0, 0);
        }

        static Spec choice(String title, String... choices) {
            return new Spec(Kind.CHOICE, title, Arrays.asList(choices), 0, 0, 0);
        }

        static Spec slider(String title, int minimum, int maximum, int initial) {
            return new Spec(Kind.SLIDER, title, Collections.emptyList(),
                    minimum, maximum, initial);
        }
    }

    private DemoNativeContract() {
    }

    /** Returns a native spec only for an exact, known corpus component. */
    @Nullable
    static Spec resolve(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        String id = component.getId();
        if (id == null) return null;

        if (matches(surfaceId, id, component, "search_and_filter", "picker",
                ComponentType.CHOICE_PICKER, "select")) {
            return Spec.choice("Sort by", "Recently played", "Artist", "Album", "Newest");
        }
        if (matches(surfaceId, id, component, "playback_settings", "quality",
                ComponentType.CHOICE_PICKER, "select")) {
            return Spec.choice("Streaming quality", "Data saver", "Normal", "High");
        }
        if (matches(surfaceId, id, component, "playback_settings", "crossfade",
                ComponentType.SLIDER, "select")) {
            return Spec.slider("Crossfade seconds", 0, 12, 6);
        }
        if (matches(surfaceId, id, component, "playback_settings", "sleep",
                ComponentType.DATE_TIME_INPUT, "edit")) {
            return Spec.simple(Kind.TIME, "Sleep timer");
        }
        if (matches(surfaceId, id, component, "passenger_video", "slider",
                ComponentType.SLIDER, "select")) {
            return Spec.slider("Passenger brightness", 0, 100, 60);
        }
        if (matches(surfaceId, id, component, "browse_library", "list",
                ComponentType.LIST, "open")
                || matches(surfaceId, id, component, "passenger_video", "list",
                ComponentType.LIST, "open")
                || matches(surfaceId, id, component, "poi_discovery_order", "places",
                ComponentType.LIST, "open")
                || matches(surfaceId, id, component, "big_buck_bunny", "results",
                ComponentType.LIST, "open")) {
            return Spec.simple(Kind.BROWSE,
                    component.getTitle() == null ? "Browse" : component.getTitle());
        }
        if ("poi_discovery_order".equals(surfaceId)
                && ("first".equals(id) || "second".equals(id) || "route".equals(id))
                && component.getType() == ComponentType.CARD
                && "navigate".equals(component.getAction())) {
            return Spec.simple(Kind.ROUTE, component.getTitle());
        }
        if (matches(surfaceId, id, component, "poi_discovery_order", "order",
                ComponentType.BUTTON, "purchase")) {
            return Spec.simple(Kind.ORDER, "DEMO pickup order");
        }
        if ("poi_discovery_order".equals(surfaceId) && "status".equals(id)
                && component.getType() == ComponentType.TEXT && component.getAction() == null) {
            return Spec.simple(Kind.WEATHER, "Curated weather");
        }
        if ("now_playing".equals(surfaceId) && "art".equals(id)
                && component.getType() == ComponentType.IMAGE && component.getAction() == null) {
            return Spec.simple(Kind.IMAGE, "DEMO album artwork");
        }
        if (isRecordedVideo(surfaceId, id, component)) {
            return Spec.simple(Kind.VIDEO, "DEMO local video");
        }
        if (isRecordedAudio(surfaceId, id, component)) {
            return Spec.simple(Kind.AUDIO, "DEMO local audio");
        }
        if ("big_buck_bunny".equals(surfaceId) && "movie".equals(id)
                && component.getType() == ComponentType.CARD
                && "open".equals(component.getAction())) {
            return Spec.simple(Kind.VIDEO, "DEMO local video");
        }
        if ("big_buck_bunny".equals(surfaceId) && "watch".equals(id)
                && component.getType() == ComponentType.BUTTON
                && "open".equals(component.getAction())) {
            return Spec.simple(Kind.VIDEO, "DEMO local video");
        }
        if ("big_buck_bunny".equals(surfaceId) && "play".equals(id)
                && component.getType() == ComponentType.BUTTON
                && "play".equals(component.getAction())) {
            return Spec.simple(Kind.AUDIO, "DEMO local audio");
        }
        if ("modal_confirmation".equals(surfaceId) && "modal".equals(id)
                && component.getType() == ComponentType.MODAL && component.getAction() == null) {
            return Spec.simple(Kind.MODAL, "Buy this album?");
        }
        return null;
    }

    private static boolean isRecordedVideo(String surfaceId, String id,
            A2uiComponent component) {
        return "video".equals(id) && component.getType() == ComponentType.VIDEO
                && component.getAction() == null
                && ("video_trailer".equals(surfaceId)
                        || "passenger_video".equals(surfaceId)
                        || "big_buck_bunny".equals(surfaceId));
    }

    private static boolean isRecordedAudio(String surfaceId, String id,
            A2uiComponent component) {
        return "audio".equals(id) && component.getType() == ComponentType.AUDIO_PLAYER
                && component.getAction() == null
                && ("video_trailer".equals(surfaceId) || "big_buck_bunny".equals(surfaceId));
    }

    private static boolean matches(String surfaceId, String id, A2uiComponent component,
            String expectedSurface, String expectedId, ComponentType expectedType,
            String expectedAction) {
        return expectedSurface.equals(surfaceId) && expectedId.equals(id)
                && component.getType() == expectedType
                && expectedAction.equals(component.getAction());
    }
}
