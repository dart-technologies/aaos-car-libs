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

    enum Kind {
        CHOICE, SLIDER, TIME, BROWSE, ROUTE, IMAGE, VIDEO, AUDIO, MODAL, ORDER, WEATHER, GALLERY,
        TOGGLE, CHARGER
    }

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
        if ("met_visit".equals(surfaceId)) {
            Spec met = metSpec(id, component);
            if (met != null) return met;
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            Spec ev = evSpec(id, component);
            if (ev != null) return ev;
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

    /** Large imagery for the visual panel: a bundled drawable by name, with a bundled fallback. */
    static final class PanelSpec {
        @NonNull final String mDrawableName;
        final int mFallbackDrawable;
        @Nullable final String mCaption;

        PanelSpec(@NonNull String drawableName, int fallbackDrawable, @Nullable String caption) {
            mDrawableName = drawableName;
            mFallbackDrawable = fallbackDrawable;
            mCaption = caption;
        }
    }

    /**
     * Panel imagery for an exact corpus component, or null. Asked only of components that
     * survived the prune, and drawn only while the reading allows IMAGE.
     */
    @Nullable
    static PanelSpec panelFor(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        if ("met_visit".equals(surfaceId) && "map".equals(component.getId())
                && component.getType() == ComponentType.IMAGE && component.getAction() == null) {
            // The real route only: without route_met.png the panel stays hidden.
            return new PanelSpec("route_met", 0, null);
        }
        return null;
    }

    /** A bundled video by raw-resource name, and the caption shown with it. */
    static final class Media {
        @NonNull final String mRawName;
        @NonNull final String mCaption;
        /** The player's title overlay, or null for none (the film carries its own captions). */
        @Nullable final String mTitle;
        @Nullable final String mCredit;
        /**
         * Driver media in the full-bleed player: it fills the stage, and it opens and stays only
         * on a trusted parked reading, over and above the VIDEO gate every video has.
         */
        final boolean mPlayer;

        Media(@NonNull String rawName, @NonNull String caption) {
            this(rawName, caption, null, null, false);
        }

        private Media(@NonNull String rawName, @NonNull String caption, @Nullable String title,
                @Nullable String credit, boolean player) {
            mRawName = rawName;
            mCaption = caption;
            mTitle = title;
            mCredit = credit;
            mPlayer = player;
        }

        /** Parked-only driver media with a host-owned title and credit, never agent text. */
        @NonNull
        static Media player(@NonNull String rawName, @NonNull String title,
                @NonNull String credit) {
            return new Media(rawName, "", title, credit, true);
        }
    }

    /** The video an exact corpus VIDEO component plays, or null for the generated fixture. */
    @Nullable
    static Media videoFor(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        if (isMetArtSurface(surfaceId) && "film".equals(component.getId())
                && component.getType() == ComponentType.VIDEO) {
            // The film carries its own captions and credit.
            return new Media("met_artwork_film", "");
        }
        return null;
    }

    /** The bundled artwork for an exact corpus Image inside a gallery, by drawable name. */
    @Nullable
    static String artworkFor(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        String id = component.getId();
        if (isMetArtSurface(surfaceId) && id != null && MET_WORKS.contains(id)
                && component.getType() == ComponentType.IMAGE) {
            return id; // met_<objectID>.jpg, bundled
        }
        return null;
    }

    /** The credit line under every slide of a corpus gallery, or null. */
    @Nullable
    static String artworkCredit(@NonNull String surfaceId) {
        return isMetArtSurface(surfaceId) ? MET_CREDIT : null;
    }

    private static boolean isMetArtSurface(@NonNull String surfaceId) {
        return "met_visit".equals(surfaceId) || "ev_charge_coffee".equals(surfaceId);
    }

    /** The confirmation question for an exact corpus control, or null for the generic one. */
    @Nullable
    static String confirmPrompt(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        if ("poi_discovery_order".equals(surfaceId) && "order".equals(component.getId())) {
            return "Order a cold brew and a bagel for pickup?";
        }
        if ("modal_confirmation".equals(surfaceId) && "buy".equals(component.getId())) {
            return "Buy this album for £9.99?";
        }
        if ("met_visit".equals(surfaceId) && "reserve".equals(component.getId())) {
            return "Reserve 2 timed-entry tickets for 3:00 PM?";
        }
        if ("ev_charge_coffee".equals(surfaceId) && "coffee".equals(component.getId())) {
            // No price: none is shown unless the café fixture cites a menu (CoffeeFixture).
            return "Order your usual: oat milk latte?";
        }
        return null;
    }

    /** The exact corpus coffee reorder, whose words a bundled café fixture may supply. */
    static boolean isCoffeeOrder(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        return "ev_charge_coffee".equals(surfaceId) && "coffee".equals(component.getId())
                && component.getType() == ComponentType.BUTTON
                && "purchase".equals(component.getAction());
    }

    /** Surfaces whose agent answer joins the screen as its brief instead of replacing it. */
    static boolean embedsAgentBrief(@NonNull String surfaceId) {
        return "met_visit".equals(surfaceId);
    }

    /** A thumbnail (by drawable name) for an exact corpus row, drawn only while IMAGE is allowed. */
    @Nullable
    static String thumbnailFor(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        String id = component.getId();
        if ("met_visit".equals(surfaceId)) {
            if ("brief".equals(id)) return "met_436535";
            if ("gallery".equals(id)) return "met_437881";
        }
        if ("ev_charge_coffee".equals(surfaceId) && "tour".equals(id)) return "met_435868";
        return null;
    }

    /** A glyph for an exact corpus row, drawn while ICON is allowed; 0 for none. */
    static int glyphFor(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        String id = component.getId();
        if ("met_visit".equals(surfaceId)) {
            if ("route".equals(id)) return R.drawable.a2ui_ic_route;
            if ("reserve".equals(id)) return R.drawable.a2ui_ic_ticket;
            if ("audio".equals(id)) return R.drawable.a2ui_ic_guide;
            if ("artmode".equals(id)) return R.drawable.a2ui_ic_film;
            if ("brief".equals(id) || "gallery".equals(id)) return R.drawable.a2ui_ic_film;
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            if (id != null && id.length() == 2 && id.startsWith("c")) {
                return R.drawable.a2ui_ic_battery;
            }
            if ("coffee".equals(id)) return R.drawable.a2ui_ic_pickup;
            if ("tour".equals(id)) return R.drawable.a2ui_ic_film;
        }
        return 0;
    }

    /** The confirmation's supporting line for an exact corpus control, or null. */
    @Nullable
    static String confirmDetail(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        if ("met_visit".equals(surfaceId) && "reserve".equals(component.getId())) {
            return "The Met Fifth Avenue · Thu Nov 5 · 81st St entrance";
        }
        if ("ev_charge_coffee".equals(surfaceId) && "coffee".equals(component.getId())) {
            return "Pickup at the counter";
        }
        return null;
    }

    /** The id of an exact corpus card's citation list, drawn as lines under it, or null. */
    @Nullable
    static String citationContainer(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        return "met_visit".equals(surfaceId) && "brief".equals(component.getId())
                && component.getType() == ComponentType.CARD ? "sources" : null;
    }

    /** Credit for every Met image and the film made from them. */
    static final String MET_CREDIT = "The Met, Open Access · CC0";

    /** The six bundled works, in gallery order. */
    static final List<String> MET_WORKS = Collections.unmodifiableList(Arrays.asList(
            "met_436535", "met_437881", "met_437394", "met_435809", "met_436105", "met_435868"));

    @Nullable
    private static Spec evSpec(@NonNull String id, @NonNull A2uiComponent component) {
        ComponentType type = component.getType();
        String action = component.getAction();
        if (("c1".equals(id) || "c2".equals(id) || "c3".equals(id))
                && type == ComponentType.CARD && "navigate".equals(action)) {
            return Spec.simple(Kind.CHARGER, "Charger");
        }
        if ("coffee".equals(id) && type == ComponentType.BUTTON && "purchase".equals(action)) {
            return Spec.simple(Kind.ORDER, "DEMO coffee reorder");
        }
        if ("tour".equals(id) && type == ComponentType.MODAL && "open".equals(action)) {
            return Spec.simple(Kind.GALLERY, "Finish the Met tour");
        }
        return null;
    }

    @Nullable
    private static Spec metSpec(@NonNull String id, @NonNull A2uiComponent component) {
        ComponentType type = component.getType();
        String action = component.getAction();
        if ("reserve".equals(id) && type == ComponentType.BUTTON && "reserve".equals(action)) {
            return Spec.simple(Kind.ORDER, "DEMO timed entry");
        }
        if ("gallery".equals(id) && type == ComponentType.MODAL && "open".equals(action)) {
            return Spec.simple(Kind.GALLERY, "Artwork highlights");
        }
        if ("artmode".equals(id) && type == ComponentType.CHOICE_PICKER
                && "select".equals(action)) {
            return Spec.simple(Kind.TOGGLE, "Film · Slideshow");
        }
        if ("audio".equals(id) && type == ComponentType.AUDIO_PLAYER && "play".equals(action)) {
            return Spec.simple(Kind.AUDIO, "Audio guide");
        }
        if ("weather".equals(id) && type == ComponentType.TEXT && action == null) {
            return Spec.simple(Kind.WEATHER, "Weather");
        }
        if ("map".equals(id) && type == ComponentType.IMAGE && action == null) {
            return Spec.simple(Kind.ROUTE, component.getTitle());
        }
        return null;
    }

    /**
     * A host-owned noun for the "what changed" line, for exact corpus components only; null falls
     * back to the type noun. Never agent text.
     */
    @Nullable
    static String changeLabel(@NonNull String surfaceId, @NonNull A2uiComponent component) {
        String id = component.getId();
        if (id == null) return null;
        if ("poi_discovery_order".equals(surfaceId)) {
            if ("field".equals(id)) return "Search";
            if ("places".equals(id)) return "Places";
        }
        if ("big_buck_bunny".equals(surfaceId)) {
            if ("field".equals(id)) return "Search";
            if ("results".equals(id)) return "Results";
        }
        if ("ev_charge_coffee".equals(surfaceId)) {
            if ("tour".equals(id)) return "Artwork";
            if (id.startsWith("c") && id.length() == 2) return "Chargers";
            if ("coffee".equals(id)) return "Order";
        }
        if ("met_visit".equals(surfaceId)) {
            if ("map".equals(id) || "preview".equals(id)) return "Map";
            if (id.startsWith("src") || "sources".equals(id)) return "Sources";
            if ("gallery".equals(id) || "film".equals(id)) return "Artwork";
            if ("artmode".equals(id)) return "Artwork";
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
