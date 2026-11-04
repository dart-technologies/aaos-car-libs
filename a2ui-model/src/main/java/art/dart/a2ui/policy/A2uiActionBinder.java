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
package art.dart.a2ui.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;

/**
 * How a renderer asks whether a component may be offered, and reports that it was invoked. Shared
 * by both hosts so "may this be pressed" exists once; renderers ask and never read the vehicle.
 */
public interface A2uiActionBinder {

    /**
     * The line a host appends to a control that is present but not offerable, so a refused control
     * does not read as broken and invite another press.
     */
    String REFUSED_NOTE = "Unavailable while driving";

    /**
     * The line a host shows when the prune left nothing to draw; a blank screen looks like a crash.
     * Pinned for both hosts by {@code CarAppRendererTest} and {@code CarUiRendererTest}.
     */
    String EMPTY_WHILE_DRIVING_NOTE = "Nothing may be shown while driving";

    /**
     * Renders the spoken form of a control, e.g. {@code say "play"}, beside the tappable control.
     * Never shown on a refused control: voice is refused too.
     */
    @NonNull
    static String voiceHint(@NonNull String phrase) {
        return "say \u201c" + phrase.trim().toLowerCase(java.util.Locale.ROOT) + "\u201d";
    }

    /** The decision for this component's action, or null if it carries no action. */
    @Nullable
    A2uiActionPolicy.Decision decisionFor(@NonNull A2uiComponent component);

    /** Called when the driver invokes a control whose decision permits it being offered. */
    void onInvoke(@NonNull A2uiComponent component);
}
