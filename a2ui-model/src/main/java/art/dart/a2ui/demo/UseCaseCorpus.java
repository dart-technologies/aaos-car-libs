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
package art.dart.a2ui.demo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.protocol.A2uiMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A cached corpus of agent-authored use cases covering the whole automotive catalog, replayed
 * through the real {@code A2uiMessageProcessor} exactly as gateway output would be.
 *
 * <p>Authored by Claude against {@code a2ui-automotive/conformance/automotive.yaml}, and trusted
 * because of the checks, not the author: {@code UseCaseCorpusTest} processes every trace and
 * asserts the corpus covers every {@code ComponentType}.
 */
public final class UseCaseCorpus {

    /** Protocol version and catalog every case in the corpus negotiates. */
    public static final String VERSION = DemoTrace.VERSION;
    public static final String CATALOG_ID = DemoTrace.CATALOG_ID;

    /** Host presentation capabilities a case is intended to exercise. */
    @SuppressWarnings("Enum")
    public enum TemplateCapability {
        LIST,
        GRID,
        SEARCH,
        MEDIA_PLAYBACK,
        SETTINGS,
        MODAL,
        VIDEO,
        HANDOFF,
        PLACE_LIST_MAP,
        MAP_WITH_CONTENT,
        ROUTE_PREVIEW,
        NAVIGATION
    }

    /** One recorded use case: an id, why it exists, and the messages an agent would have sent. */
    public static final class UseCase {
        private final String mId;
        private final String mIntent;
        private final String mInterestingAt;
        private final List<A2uiMessage> mMessages;
        private final Set<ComponentType> mTypes;
        private final Set<TemplateCapability> mTemplateCapabilities;

        UseCase(String id, String intent, String interestingAt, List<A2uiMessage> messages,
                Set<ComponentType> types, Set<TemplateCapability> templateCapabilities) {
            mId = id;
            mIntent = intent;
            mInterestingAt = interestingAt;
            mMessages = Collections.unmodifiableList(messages);
            mTypes = Collections.unmodifiableSet(types);
            mTemplateCapabilities = Collections.unmodifiableSet(templateCapabilities);
        }

        @NonNull public String getId() { return mId; }

        /** What an agent was trying to do. Shown in the demo picker. */
        @NonNull public String getIntent() { return mIntent; }

        /** The restriction level at which this case demonstrates something. */
        @NonNull public String getInterestingAt() { return mInterestingAt; }

        @NonNull public List<A2uiMessage> getMessages() { return mMessages; }

        /** The catalog types this case uses. Summed across the corpus to measure coverage. */
        @NonNull public Set<ComponentType> getComponentTypes() { return mTypes; }

        /** Host shells/widgets this case is expected to exercise, independently of the catalog. */
        @NonNull
        public Set<TemplateCapability> getTemplateCapabilities() {
            return mTemplateCapabilities;
        }

        @Override
        public String toString() {
            return mId + " (" + mTypes.size() + " types, interesting at " + mInterestingAt + ")";
        }
    }

    private UseCaseCorpus() {
    }

    /**
     * Every recorded use case, in demo order. Built once, so {@link #byId} returns the same object
     * each time.
     */
    private static final List<UseCase> CASES = Collections.unmodifiableList(Arrays.asList(
            nowPlaying(),
            browseLibrary(),
            videoTrailer(),
            searchAndFilter(),
            playbackSettings(),
            modalConfirmation(),
            passengerVideo(),
            handoffToDriver(),
            poiDiscoveryOrder(),
            bigBuckBunny()));

    @NonNull
    public static List<UseCase> all() {
        return CASES;
    }

    /** Looks a case up by id, or null. */
    @Nullable
    public static UseCase byId(@NonNull String id) {
        for (UseCase c : all()) {
            if (c.getId().equals(id)) {
                return c;
            }
        }
        return null;
    }

    // The cases, each interesting at a different restriction level.

    /** Glanceable now-playing. The case that must survive FULLY_RESTRICTED. */
    private static UseCase nowPlaying() {
        Builder b = new Builder("now_playing",
                "Show what is playing, with transport controls",
                "FULLY_RESTRICTED — this is the one that must still render");
        b.container("root", "Column", "title", "art", "sep", "controls");
        b.put("root", "title", "Now playing");
        b.text("title", null, "Artist — Album");
        b.put("title", "title", ptr("/now/title"));
        b.component("art", "Image", "Album art", null);
        b.component("sep", "Divider", null, null);
        b.container("controls", "Row", "prev", "play", "next", "icon");
        b.action("prev", "Button", "Previous", null, "previous");
        b.action("play", "Button", "Play", null, "play");
        b.action("next", "Button", "Next", null, "next");
        b.component("icon", "Icon", "Cast", null);
        b.data("/now/title", "Currently playing");
        b.templates(TemplateCapability.MEDIA_PLAYBACK);
        return b.build();
    }

    /** Browsing. Exercises the containers that deep navigation lives in. */
    private static UseCase browseLibrary() {
        Builder b = new Builder("browse_library",
                "Browse the library by category",
                "LIMIT_CONTENT — Tabs and deep lists are what it removes");
        b.container("root", "Column", "tabs", "list", "card");
        b.put("root", "title", "Your library");
        b.action("tabs", "Tabs", "Albums · Artists · Playlists", null, "select");
        b.container("list", "List", "row1", "row2");
        b.put("list", "action", "open");
        b.action("row1", "Card", "Recently added", "24 albums", "open");
        b.action("row2", "Card", "Made for you", "6 mixes", "open");
        b.component("card", "Card", "Continue listening", "Resume where you left off");
        b.templates(TemplateCapability.GRID);
        return b.build();
    }

    /** Video. The single most restriction-sensitive component in the catalog. */
    private static UseCase videoTrailer() {
        Builder b = new Builder("video_trailer",
                "Preview an album trailer with its audio track",
                "NO_VIDEO — Video goes, AudioPlayer deliberately stays");
        b.container("root", "Column", "video", "audio", "caption");
        b.put("root", "title", "Album trailer");
        b.component("video", "Video", "Trailer", "2:14");
        b.component("audio", "AudioPlayer", "Soundtrack", "Playing");
        b.text("caption", "About this album", "Recorded live in 1974");
        b.templates(TemplateCapability.VIDEO, TemplateCapability.MEDIA_PLAYBACK);
        return b.build();
    }

    /** Search. Every component that needs the keyboard or a filter affordance. */
    private static UseCase searchAndFilter() {
        Builder b = new Builder("search_and_filter",
                "Search the library and narrow the results",
                "NO_KEYBOARD and NO_FILTERING — text entry and pickers go separately");
        b.container("root", "Column", "field", "picker", "hint");
        b.put("root", "title", "Find music");
        b.action("field", "TextField", "Search", "Artist, album or track", "search");
        b.action("picker", "ChoicePicker", "Sort by", "Recently played", "select");
        b.text("hint", null, "Try saying a song name instead");
        b.templates(TemplateCapability.SEARCH);
        return b.build();
    }

    /** Settings. The configuration cluster NO_SETUP exists to remove while driving. */
    private static UseCase playbackSettings() {
        Builder b = new Builder("playback_settings",
                "Adjust playback quality, crossfade and a sleep timer",
                "NO_SETUP — configuration is exactly what it drops");
        b.container("root", "Column", "quality", "crossfade", "sleep", "explicit");
        b.put("root", "title", "Playback settings");
        b.action("quality", "ChoicePicker", "Streaming quality", "High", "select");
        b.action("crossfade", "Slider", "Crossfade", "6 seconds", "select");
        b.action("sleep", "DateTimeInput", "Sleep timer", "Off", "edit");
        b.action("explicit", "CheckBox", "Allow explicit content", null, "select");
        b.templates(TemplateCapability.SETTINGS);
        return b.build();
    }

    /** A modal, and the consequential action behind it. */
    private static UseCase modalConfirmation() {
        Builder b = new Builder("modal_confirmation",
                "Confirm a purchase raised from a modal",
                "BASELINE confirms; anything restricted refuses outright");
        b.container("root", "Column", "modal", "buy", "note");
        b.put("root", "title", "Confirm purchase");
        b.component("modal", "Modal", "Buy this album?", "£9.99");
        b.action("buy", "Button", "Buy album", "Charged to the account on file", "purchase");
        b.text("note", null, "A confirmation at speed is two glances, not one");
        b.templates(TemplateCapability.MODAL);
        return b.build();
    }

    /**
     * The passenger case: the full catalog is legal on a display that is not the driver's.
     *
     * <p>Mirrors {@code test_automotive_passenger_display_unrestricted_while_moving}. Included so
     * an implementation cannot pass by pruning everything whenever the vehicle moves.
     */
    private static UseCase passengerVideo() {
        Builder b = new Builder("passenger_video",
                "Full-catalog surface composed on the front passenger display",
                "BASELINE on the passenger display while the vehicle is moving");
        b.container("root", "Column", "video", "list", "field", "slider");
        b.put("root", "title", "Passenger screen");
        b.component("video", "Video", "Feature film", "1h 52m");
        b.container("list", "List", "ep1", "ep2");
        b.put("list", "action", "open");
        b.text("ep1", "Episode 1", "48m");
        b.text("ep2", "Episode 2", "51m");
        b.action("field", "TextField", "Search titles", null, "search");
        b.action("slider", "Slider", "Brightness", null, "select");
        b.templates(TemplateCapability.VIDEO, TemplateCapability.SEARCH,
                TemplateCapability.SETTINGS);
        return b.build();
    }

    /**
     * The handoff case: a passenger-display surface sent to the driver display must be re-pruned
     * for its destination ({@code test_automotive_surface_repruned_for_destination_display}). One
     * trace, because it is one surface. The {@code buy} Button survives the prune (glanceable), so
     * only the action policy stops it at the driver's display.
     */
    private static UseCase handoffToDriver() {
        Builder b = new Builder("handoff_to_driver",
                "Passenger sends a rich surface to the driver's display",
                "composed at BASELINE, re-pruned at the driver's FULLY_RESTRICTED");
        b.container("root", "Column", "video", "list", "field", "buy", "send", "why");
        b.put("root", "title", "From the passenger");
        b.component("video", "Video", "Trailer", "legal where it was composed");
        b.container("list", "List", "t1", "t2");
        b.text("t1", "Track 1", null);
        b.text("t2", "Track 2", null);
        b.action("field", "TextField", "Add a note", null, "compose");
        b.action("buy", "Button", "Buy the soundtrack",
                "Survives the prune — only the ACTION policy stops it", "purchase");
        b.action("send", "Button", "Send to driver", "Re-pruned on arrival",
                "demo.handoff_to_driver");
        b.text("why", null, "The destination display's restrictions win");
        b.templates(TemplateCapability.HANDOFF);
        return b.build();
    }

    /** POI discovery, route preview, navigation, and a purchase that cannot cross into motion. */
    private static UseCase poiDiscoveryOrder() {
        Builder b = new Builder("poi_discovery_order",
                "From Hudson Yards to Brooklyn Museum: find a coffee stop, preview the route, "
                        + "order while parked, then drive",
                "BASELINE permits search/order; NO_KEYBOARD removes search and motion refuses "
                        + "purchase while navigation remains glanceable");
        b.container("root", "Column", "field", "places", "route", "order", "status");
        b.put("root", "title", "Coffee on the way");
        b.action("field", "TextField", "Find coffee",
                "Hudson Yards to Brooklyn Museum", "search");
        b.put("field", "voicePhrase", "find coffee along the route");
        b.container("places", "List", "first", "second");
        b.put("places", "action", "open");
        b.action("first", "Card", "Fifth House Coffee Atelier",
                "FICTIONAL DEMO venue · SoHo · 0.4 mi detour", "navigate");
        b.action("second", "Card", "Atlantic Avenue Coffee",
                "DEMO venue · Boerum Hill · 0.7 mi detour", "navigate");
        b.action("route", "Card", "Preview pickup route",
                "DEMO route · 6 min detour", "navigate");
        b.action("order", "Button", "Order cold brew + bagel",
                "DEMO order · confirm only while parked", "purchase");
        b.put("order", "voicePhrase", "order ahead");
        b.text("status", "Curated snapshot · Sep 7 · warm, showers possible Thursday",
                "Umbrella reminder · the route stays glanceable in motion");
        // What the host's pickup and arrival cards show once the order is confirmed.
        b.data("/order/status", "DEMO order confirmed · preparing");
        b.data("/order/eta", "Ready in about 6 min");
        b.data("/order/pickup", "Fifth House Coffee Atelier · SoHo");
        b.data("/arrival/title", "Welcome to Fifth House · SoHo");
        b.data("/arrival/detail", "Your DEMO order is ready · city note unlocked");
        b.templates(TemplateCapability.SEARCH, TemplateCapability.PLACE_LIST_MAP,
                TemplateCapability.MAP_WITH_CONTENT, TemplateCapability.ROUTE_PREVIEW,
                TemplateCapability.NAVIGATION);
        return b.build();
    }

    /** Parked video discovery with an audio-only continuation when video becomes unavailable. */
    private static UseCase bigBuckBunny() {
        Builder b = new Builder("big_buck_bunny",
                "While waiting in Long Island City, find and watch a curated short while parked, "
                        + "then continue audio in motion",
                "BASELINE permits search/video; NO_VIDEO removes pixels while AudioPlayer and "
                        + "safe transport remain");
        b.container("root", "Column", "field", "results", "video", "watch", "audio", "play",
                "note");
        b.put("root", "title", "A short while you wait");
        b.action("field", "TextField", "Find a curated short",
                "Parked in Long Island City", "search");
        b.put("field", "voicePhrase", "find curated short films");
        b.container("results", "List", "movie");
        b.put("results", "action", "open");
        b.action("movie", "Card", "Big Buck Bunny",
                "Fifth House DEMO collection · generated fixture", "open");
        b.component("video", "Video", "Fifth House city note · Big Buck Bunny",
                "Parked video · generated placeholder pixels");
        b.action("watch", "Button", "Watch video", "Parked only", "open");
        b.put("watch", "voicePhrase", "open Big Buck Bunny");
        b.component("audio", "AudioPlayer", "Fifth House city note · audio",
                "Generated placeholder audio · available in motion");
        b.action("play", "Button", "Play audio / pause", null, "play");
        b.put("play", "voicePhrase", "play audio");
        b.text("note", "Leave Long Island City",
                "Video is withheld in motion; audio controls remain available");
        b.templates(TemplateCapability.SEARCH, TemplateCapability.VIDEO,
                TemplateCapability.MEDIA_PLAYBACK);
        return b.build();
    }

    // Construction: the same message shapes DemoTrace uses, so they stay protocol messages.

    private static Map<String, Object> ptr(String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        return m;
    }

    private static final class Builder {
        private final String mId;
        private final String mIntent;
        private final String mInterestingAt;
        private final List<Map<String, Object>> mComponents = new ArrayList<>();
        private final Map<String, Map<String, Object>> mById = new LinkedHashMap<>();
        private final Set<ComponentType> mTypes = new LinkedHashSet<>();
        private final Set<TemplateCapability> mTemplateCapabilities =
                new LinkedHashSet<>(Collections.singleton(TemplateCapability.LIST));
        private final List<A2uiMessage> mExtra = new ArrayList<>();

        Builder(String id, String intent, String interestingAt) {
            mId = id;
            mIntent = intent;
            mInterestingAt = interestingAt;
        }

        private Map<String, Object> add(String id, String type) {
            Map<String, Object> m = A2uiMessage.component(id, type);
            mComponents.add(m);
            mById.put(id, m);
            ComponentType t = ComponentType.fromCatalogName(type);
            if (t != null) {
                mTypes.add(t);
            }
            return m;
        }

        void container(String id, String type, String... children) {
            Map<String, Object> m = add(id, type);
            m.put("children", Arrays.asList(children));
        }

        void component(String id, String type, String title, String subtitle) {
            Map<String, Object> m = add(id, type);
            if (title != null) {
                m.put("title", title);
            }
            if (subtitle != null) {
                m.put("subtitle", subtitle);
            }
        }

        void text(String id, String title, String subtitle) {
            component(id, "Text", title, subtitle);
        }

        void action(String id, String type, String title, String subtitle, String action) {
            component(id, type, title, subtitle);
            mById.get(id).put("action", action);
        }

        void put(String id, String key, Object value) {
            mById.get(id).put(key, value);
        }

        void data(String path, Object value) {
            mExtra.add(A2uiMessage.updateDataModel(VERSION, mId, path, value));
        }

        void templates(TemplateCapability... capabilities) {
            mTemplateCapabilities.addAll(Arrays.asList(capabilities));
        }

        UseCase build() {
            List<A2uiMessage> messages = new ArrayList<>();
            messages.add(A2uiMessage.createSurface(VERSION, mId, CATALOG_ID));
            messages.add(A2uiMessage.updateComponents(VERSION, mId, mComponents));
            messages.addAll(mExtra);
            return new UseCase(mId, mIntent, mInterestingAt, messages, mTypes,
                    mTemplateCapabilities);
        }
    }
}
