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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether an action may run, given what it does and what the vehicle is doing.
 *
 * <p>Under restriction there is no confirmation, only refusal: a dialog at speed doubles the
 * glances. A confirmation is decided again when answered, in {@code A2uiMessageProcessor}.
 */
public final class A2uiActionPolicy {

    /** What the host should do with an action. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
    // (docs/api-surface/a2ui-model.txt), and the memory rationale does not apply at this scale.
    @SuppressWarnings("Enum")
    public enum Decision {
        /** Run it. */
        ALLOW,
        /** Ask the driver first. Reachable only when the vehicle is not restricting. */
        CONFIRM,
        /** Do not run it, and do not offer a way to. */
        REFUSE
    }

    /**
     * The host's own reading of action names, matched case-insensitively after trimming. Kept
     * short: every entry is a name that escapes the fail-closed default.
     */
    private static final Map<String, A2uiActionClass> KNOWN;

    static {
        Map<String, A2uiActionClass> m = new HashMap<>();
        for (String s : new String[]{"play", "pause", "stop", "resume", "next", "previous",
                "skip", "dismiss", "close", "scroll", "mute", "unmute"}) {
            m.put(s, A2uiActionClass.SAFE);
        }
        for (String s : new String[]{"open", "back", "navigate", "select", "expand", "collapse"}) {
            m.put(s, A2uiActionClass.NAVIGATION);
        }
        for (String s : new String[]{"search", "compose", "edit", "rename"}) {
            m.put(s, A2uiActionClass.TEXT_ENTRY);
        }
        for (String s : new String[]{"send", "call", "purchase", "buy", "share", "post",
                "subscribe", "checkout"}) {
            m.put(s, A2uiActionClass.CONSEQUENTIAL);
        }
        for (String s : new String[]{"delete", "remove", "erase", "clear", "unsubscribe",
                "reset"}) {
            m.put(s, A2uiActionClass.DESTRUCTIVE);
        }
        KNOWN = Collections.unmodifiableMap(m);
    }

    /**
     * Unrecognised actions are consequential, not safe: the agent chooses the name, so a safe
     * default would let any invented name bypass the policy. Not destructive either, so the two
     * stay distinguishable.
     */
    private static final A2uiActionClass UNKNOWN_ACTION = A2uiActionClass.CONSEQUENTIAL;

    private A2uiActionPolicy() {
    }

    /**
     * Classifies an action, taking the agent's declaration into account only as an upgrade: a
     * declared downgrade would let the sender choose its own policy.
     *
     * @param actionName the action string from the surface
     * @param agentDeclared the class the agent claims, or null if it did not say
     */
    @NonNull
    public static A2uiActionClass classify(
            @Nullable String actionName, @Nullable A2uiActionClass agentDeclared) {
        A2uiActionClass fromTable = UNKNOWN_ACTION;
        if (actionName != null) {
            A2uiActionClass known = KNOWN.get(actionName.trim().toLowerCase(Locale.ROOT));
            if (known != null) {
                fromTable = known;
            }
        }
        return agentDeclared == null
                ? fromTable
                : A2uiActionClass.mostRestrictive(fromTable, agentDeclared);
    }

    /**
     * Decides an action against the current vehicle state.
     *
     * @param actionClass the effective class, from {@link #classify}
     * @param activeRestrictions the mask from {@code CarUxRestrictions.getActiveRestrictions()}
     * @param trustworthy whether that mask is a real reading; false means a fail-closed default
     *        whose bits describe no vehicle
     */
    @NonNull
    public static Decision decide(
            @NonNull A2uiActionClass actionClass, int activeRestrictions, boolean trustworthy) {
        if (!trustworthy) {
            // No reading: decide as FULLY_RESTRICTED, the harshest state a vehicle can report;
            // anything stricter would be a rule without a safety argument. CONFIRM stays
            // unreachable and TEXT_ENTRY refused.
            return decide(actionClass,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true);
        }

        boolean restricting = activeRestrictions != A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
        if (!restricting) {
            // Stationary, or at least not restricted. Confirmation is a real mechanism here.
            return actionClass.isAtLeast(A2uiActionClass.CONSEQUENTIAL)
                    ? Decision.CONFIRM : Decision.ALLOW;
        }

        switch (actionClass) {
            case SAFE:
            case NAVIGATION:
                return Decision.ALLOW;
            case TEXT_ENTRY:
                // Gated by capability: typing takes the driver's eyes and hands.
                return isSet(activeRestrictions,
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD)
                        ? Decision.REFUSE : Decision.ALLOW;
            case CONSEQUENTIAL:
            case DESTRUCTIVE:
            default:
                // Not CONFIRM (see the class javadoc). `default` shares this arm so a new class
                // fails closed.
                return Decision.REFUSE;
        }
    }

    private static boolean isSet(int active, int flag) {
        return (active & flag) != 0;
    }
}
