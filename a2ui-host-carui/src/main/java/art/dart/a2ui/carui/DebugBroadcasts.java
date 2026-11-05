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
package art.dart.a2ui.carui;

/**
 * The action and extra names this host's debug broadcasts use, shared by sender and receiver. Named
 * {@code <package>.action.<NAME>} per metalava's ActionValue rule, not for security.
 *
 * <p>The receivers are exported without a permission and registered only on debuggable builds,
 * which are drivable over adb anyway.
 * {@code A2uiHandoffLifecycleTest#debugReceiversAreNotRegisteredOnAReleaseBuild} keeps that true.
 */
final class DebugBroadcasts {

    private DebugBroadcasts() {
    }

    /** Speak a phrase at the surface, as a recognition service would. */
    static final String ACTION_SPEAK = "art.dart.a2ui.carui.action.SPEAK";

    /** String extra on {@link #ACTION_SPEAK}: the phrase. */
    static final String EXTRA_PHRASE = "art.dart.a2ui.carui.extra.PHRASE";

    /** Hand the surface to another display. */
    static final String ACTION_HANDOFF = "art.dart.a2ui.carui.action.HANDOFF";

    /** Int extra on {@link #ACTION_HANDOFF}: the destination display id. */
    static final String EXTRA_TO = "art.dart.a2ui.carui.extra.TO";

    /**
     * Int extra on {@link #ACTION_HANDOFF}: a synthetic restriction bitmask for the destination,
     * or absent to use the destination's real reading. A negative value restores the real one.
     */
    static final String EXTRA_DEST_ACTIVE = "art.dart.a2ui.carui.extra.DEST_ACTIVE";

    /** Select a recorded corpus case in both demo hosts. */
    static final String ACTION_CASE = "art.dart.a2ui.carapp.action.CASE";
    static final String EXTRA_CASE_ID = "art.dart.a2ui.carapp.extra.CASE_ID";

    /** Drive the live-agent lane: PROVIDER is applied first, then ASK is sent. */
    static final String ACTION_AGENT = "art.dart.a2ui.carui.action.AGENT";

    /** String extra on {@link #ACTION_AGENT}: a provider wire name, e.g. {@code muse}. */
    static final String EXTRA_PROVIDER = "art.dart.a2ui.carui.extra.PROVIDER";

    /** String extra on {@link #ACTION_AGENT}: the request text for one turn. */
    static final String EXTRA_ASK = "art.dart.a2ui.carui.extra.ASK";

    /**
     * Drive the passenger display. The passenger activity decides with its own display's reading.
     *
     *   adb shell am broadcast --user current -a art.dart.a2ui.carui.action.PASSENGER \
     *       --es art.dart.a2ui.carui.extra.MEDIA video
     */
    static final String ACTION_PASSENGER = "art.dart.a2ui.carui.action.PASSENGER";

    /** String extra on {@link #ACTION_PASSENGER}: a {@link PassengerMedia} name, or {@code stop}. */
    static final String EXTRA_MEDIA = "art.dart.a2ui.carui.extra.MEDIA";

    /** Show the closing card. Parked only; any later restriction change takes it away. */
    static final String ACTION_END_CARD = "art.dart.a2ui.carui.action.END_CARD";
}
