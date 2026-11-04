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

import art.dart.a2ui.model.ComponentType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Turns a {@code CarUxRestrictions} bitmask into the set of catalog components a surface may show.
 *
 * <p>The executable half of {@code a2ui-automotive/conformance/automotive.yaml};
 * {@code A2uiCatalogContractTest} asserts the two agree. The platform constants are duplicated as
 * plain ints so this runs in a local JVM, and {@code A2uiRestrictionPolicyTest} pins each one.
 */
public final class A2uiRestrictionPolicy {

    // Values read from android.car.jar with `javap -constants`. FULLY_RESTRICTED is a composite of
    // the nine bits below it, not a flag of its own.
    public static final int UX_RESTRICTIONS_BASELINE = 0x0;
    public static final int UX_RESTRICTIONS_NO_DIALPAD = 0x1;
    public static final int UX_RESTRICTIONS_NO_FILTERING = 0x2;
    public static final int UX_RESTRICTIONS_LIMIT_STRING_LENGTH = 0x4;
    public static final int UX_RESTRICTIONS_NO_KEYBOARD = 0x8;
    public static final int UX_RESTRICTIONS_NO_VIDEO = 0x10;
    public static final int UX_RESTRICTIONS_LIMIT_CONTENT = 0x20;
    public static final int UX_RESTRICTIONS_NO_SETUP = 0x40;
    public static final int UX_RESTRICTIONS_NO_TEXT_MESSAGE = 0x80;
    public static final int UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION = 0x100;
    public static final int UX_RESTRICTIONS_FULLY_RESTRICTED = 0x1ff;

    /** What a surface may show when nothing is restricted: the whole catalog. */
    private static final Set<ComponentType> FULL_CATALOG =
            Collections.unmodifiableSet(EnumSet.allOf(ComponentType.class));

    /**
     * The glanceable set: what survives {@code FULLY_RESTRICTED}, matching
     * {@code test_automotive_fully_restricted_prunes_to_glanceable_set}. No Image (a dwell target)
     * and no List (scrolling is what {@code LIMIT_CONTENT} prevents).
     */
    private static final Set<ComponentType> GLANCEABLE = Collections.unmodifiableSet(EnumSet.of(
            ComponentType.TEXT,
            ComponentType.ICON,
            ComponentType.ROW,
            ComponentType.COLUMN,
            ComponentType.DIVIDER,
            ComponentType.BUTTON));

    private A2uiRestrictionPolicy() {
    }

    /**
     * The components permitted under the given active restrictions. Bits compose by intersection,
     * so no 512-entry table is needed.
     */
    @NonNull
    public static Set<ComponentType> allowedComponents(int activeRestrictions) {
        // The composite first, compared with all bits present, to keep its meaning visible.
        if ((activeRestrictions & UX_RESTRICTIONS_FULLY_RESTRICTED)
                == UX_RESTRICTIONS_FULLY_RESTRICTED) {
            return GLANCEABLE;
        }

        EnumSet<ComponentType> allowed = EnumSet.copyOf(FULL_CATALOG);

        if (isSet(activeRestrictions, UX_RESTRICTIONS_NO_VIDEO)) {
            // AudioPlayer deliberately survives: audio is not a visual distraction, and muting it
            // is not what this restriction asks for.
            allowed.remove(ComponentType.VIDEO);
        }
        if (isSet(activeRestrictions, UX_RESTRICTIONS_NO_KEYBOARD)) {
            allowed.remove(ComponentType.TEXT_FIELD);
            allowed.remove(ComponentType.DATE_TIME_INPUT);
        }
        if (isSet(activeRestrictions, UX_RESTRICTIONS_NO_FILTERING)) {
            allowed.remove(ComponentType.TEXT_FIELD);
            allowed.remove(ComponentType.CHOICE_PICKER);
        }
        if (isSet(activeRestrictions, UX_RESTRICTIONS_NO_SETUP)) {
            allowed.remove(ComponentType.TEXT_FIELD);
            allowed.remove(ComponentType.CHECK_BOX);
            allowed.remove(ComponentType.CHOICE_PICKER);
            allowed.remove(ComponentType.SLIDER);
            allowed.remove(ComponentType.DATE_TIME_INPUT);
        }
        if (isSet(activeRestrictions, UX_RESTRICTIONS_LIMIT_CONTENT)) {
            // List survives as a container: what LIMIT_CONTENT bounds is its length, which prune
            // cannot express (CarAppRenderer applies it via ConstraintManager).
            allowed.remove(ComponentType.VIDEO);
            allowed.remove(ComponentType.TABS);
            allowed.remove(ComponentType.MODAL);
        }

        return Collections.unmodifiableSet(allowed);
    }

    /**
     * The fail-closed answer, for use when there is no trustworthy reading. Not derived from
     * {@code allowedComponents(FULLY_RESTRICTED)}, so the two decisions can move independently.
     */
    @NonNull
    public static Set<ComponentType> failClosedComponents() {
        return GLANCEABLE;
    }

    /**
     * Whether speech may be transcribed under the given active restrictions. Prefer
     * {@link #allowsListening(int, boolean)}: this reads the bit only. It gates capture; every
     * action a transcript reaches is still decided by {@code A2uiActionPolicy}.
     */
    public static boolean allowsVoiceTranscription(int activeRestrictions) {
        return !isSet(activeRestrictions, UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);
    }

    /**
     * Whether a host may open a microphone: the vehicle must allow transcription and the reading
     * must be trustworthy, since a never-delivered reading is byte-identical to {@code BASELINE}.
     * It gates capture; every action a transcript reaches is still decided by
     * {@code A2uiActionPolicy}.
     */
    public static boolean allowsListening(int activeRestrictions, boolean trustworthy) {
        return trustworthy && allowsVoiceTranscription(activeRestrictions);
    }

    private static boolean isSet(int active, int flag) {
        return (active & flag) != 0;
    }
}
