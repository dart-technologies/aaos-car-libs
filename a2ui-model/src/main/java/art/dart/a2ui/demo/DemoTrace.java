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

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiProtocolVersions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The showcase surface as a recorded A2UI message trace ({@code createSurface} →
 * {@code updateComponents} → {@code updateDataModel}), processed by {@link A2uiMessageProcessor}
 * and projected. The final {@code updateDataModel} changes content without resending components.
 */
public final class DemoTrace {

    public static final String SURFACE_ID = "showcase";
    public static final String CATALOG_ID = "automotive";
    public static final String VERSION = "v0.9";
    public static final String ROOT_ID = "root";

    private DemoTrace() {
    }

    /**
     * A processor negotiated for the automotive catalog and both protocol versions. Messages are
     * routed by their own {@code version}; each surface speaks the version it was created with.
     */
    @NonNull
    public static A2uiMessageProcessor newProcessor() {
        return new A2uiMessageProcessor(CATALOG_ID,
                new HashSet<>(Arrays.asList(VERSION, A2uiProtocolVersions.V1)));
    }

    /** The recorded trace, in order. */
    @NonNull
    public static List<A2uiMessage> messages() {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(container(ROOT_ID, "Column", Arrays.asList(
                "nowPlaying", "trailer", "art", "search", "quality", "volume", "browse", "play",
                "buy")));
        components.add(text("nowPlaying", null, "Text survives every restriction level"));
        components.add(text("trailer", "Trailer", "Dropped by NO_VIDEO (0x10) and LIMIT_CONTENT (0x20)"));
        components.add(text("art", "Album art", "Survives until FULLY_RESTRICTED (0x1ff)"));
        components.add(text("search", "Search", "Dropped by NO_KEYBOARD, NO_FILTERING and NO_SETUP"));
        components.add(text("quality", "Quality", "Dropped by NO_FILTERING (0x2) and NO_SETUP (0x40)"));
        components.add(text("volume", "Volume", "Dropped by NO_SETUP (0x40)"));
        components.add(text("browse", "Browse", "Dropped by LIMIT_CONTENT (0x20)"));
        components.add(text("play", "Play", "Glanceable — survives FULLY_RESTRICTED"));
        // Prunes like "play" (Button is glanceable) but must be refused while driving: the pair
        // shows that a component-type policy cannot decide interaction.
        components.add(text("buy", "Buy album", "Glanceable, but the ACTION is refused while driving"));

        // Catalog types, assigned after the text so the intent of each row stays readable above.
        components.get(1).put("component", "Text");
        components.get(2).put("component", "Video");
        components.get(3).put("component", "Image");
        components.get(4).put("component", "TextField");
        components.get(5).put("component", "ChoicePicker");
        components.get(6).put("component", "Slider");
        components.get(7).put("component", "Tabs");
        components.get(8).put("component", "Button");
        components.get(9).put("component", "Button");

        // Neither declares an actionClass, so the host's table alone classifies them.
        components.get(8).put("action", "play");
        components.get(9).put("action", "purchase");

        // The first row's title is a pointer, resolved from the data model.
        components.get(1).put("title", Collections.singletonMap("path", "/now/title"));

        return Arrays.asList(
                A2uiMessage.createSurface(VERSION, SURFACE_ID, CATALOG_ID),
                A2uiMessage.updateComponents(VERSION, SURFACE_ID, components),
                A2uiMessage.updateDataModel(VERSION, SURFACE_ID, "/now/title", "Currently playing"));
    }

    /** Processes the trace and projects the root — what a host renders. */
    @NonNull
    public static A2uiComponent project() {
        A2uiMessageProcessor processor = newProcessor();
        processor.processAll(messages());
        return processor.getSurface(SURFACE_ID).project(ROOT_ID);
    }

    private static Map<String, Object> container(String id, String type, List<String> children) {
        Map<String, Object> m = A2uiMessage.component(id, type);
        m.put("title", "Now playing");
        m.put("children", children);
        return m;
    }

    private static Map<String, Object> text(String id, String title, String subtitle) {
        Map<String, Object> m = A2uiMessage.component(id, "Text");
        if (title != null) {
            m.put("title", title);
        }
        m.put("subtitle", subtitle);
        return m;
    }
}
