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

import static art.dart.a2ui.policy.A2uiActionClass.CONSEQUENTIAL;
import static art.dart.a2ui.policy.A2uiActionClass.DESTRUCTIVE;
import static art.dart.a2ui.policy.A2uiActionClass.NAVIGATION;
import static art.dart.a2ui.policy.A2uiActionClass.SAFE;
import static art.dart.a2ui.policy.A2uiActionClass.TEXT_ENTRY;
import static art.dart.a2ui.policy.A2uiActionPolicy.Decision.ALLOW;
import static art.dart.a2ui.policy.A2uiActionPolicy.Decision.CONFIRM;
import static art.dart.a2ui.policy.A2uiActionPolicy.Decision.REFUSE;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Covers what the driver may DO, which nothing before this gated at all. */
public class A2uiActionPolicyTest {

    private static final boolean TRUSTED = true;
    private static final boolean UNTRUSTED = false;

    // 1. Classification.

    @Test
    public void knownNames_classifyByConsequence() {
        assertThat(A2uiActionPolicy.classify("pause", null)).isEqualTo(SAFE);
        assertThat(A2uiActionPolicy.classify("navigate", null)).isEqualTo(NAVIGATION);
        assertThat(A2uiActionPolicy.classify("compose", null)).isEqualTo(TEXT_ENTRY);
        assertThat(A2uiActionPolicy.classify("purchase", null)).isEqualTo(CONSEQUENTIAL);
        assertThat(A2uiActionPolicy.classify("delete", null)).isEqualTo(DESTRUCTIVE);
    }

    @Test
    public void classification_ignoresCaseAndSurroundingSpace() {
        // An agent writing "Play" means what one writing "play" means. A policy that turns on
        // capitalisation is a bug waiting for a different agent.
        assertThat(A2uiActionPolicy.classify("  PLAY ", null)).isEqualTo(SAFE);
        assertThat(A2uiActionPolicy.classify("Delete", null)).isEqualTo(DESTRUCTIVE);
    }

    @Test
    public void unknownAction_isConsequential_notSafe() {
        // Unknown must not mean safe, or inventing a name would bypass the policy.
        assertThat(A2uiActionPolicy.classify("frobnicate", null)).isEqualTo(CONSEQUENTIAL);
        assertThat(A2uiActionPolicy.classify(null, null)).isEqualTo(CONSEQUENTIAL);
        assertThat(A2uiActionPolicy.classify("", null)).isEqualTo(CONSEQUENTIAL);
    }

    @Test
    public void agentDeclaration_canRaiseTheClassification() {
        // A music app's "clear" may genuinely be destructive; the agent knows and the host does not.
        assertThat(A2uiActionPolicy.classify("play", DESTRUCTIVE)).isEqualTo(DESTRUCTIVE);
        assertThat(A2uiActionPolicy.classify("frobnicate", DESTRUCTIVE)).isEqualTo(DESTRUCTIVE);
    }

    @Test
    public void agentDeclaration_cannotLowerTheClassification() {
        // Accepting a downgrade would let the sender of an action choose the policy applied to it,
        // which is the same as having no policy.
        assertThat(A2uiActionPolicy.classify("delete", SAFE)).isEqualTo(DESTRUCTIVE);
        assertThat(A2uiActionPolicy.classify("purchase", SAFE)).isEqualTo(CONSEQUENTIAL);
        assertThat(A2uiActionPolicy.classify("frobnicate", SAFE)).isEqualTo(CONSEQUENTIAL);
    }

    // 2. Confirmation is not available at speed.

    @Test
    public void whileRestricted_consequentialIsRefused_notConfirmed() {
        // A confirmation dialog does not reduce the driver's attention on the road; it doubles it.
        assertThat(A2uiActionPolicy.decide(CONSEQUENTIAL, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED))
                .isEqualTo(REFUSE);
        assertThat(A2uiActionPolicy.decide(DESTRUCTIVE, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED))
                .isEqualTo(REFUSE);
    }

    @Test
    public void whileRestricted_noDecisionIsEverConfirm() {
        // No path to a confirmation while restricting, for any class, including future ones.
        for (A2uiActionClass cls : A2uiActionClass.values()) {
            assertThat(A2uiActionPolicy.decide(cls, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED))
                    .isNotEqualTo(CONFIRM);
            assertThat(A2uiActionPolicy.decide(cls, UX_RESTRICTIONS_NO_VIDEO, TRUSTED))
                    .isNotEqualTo(CONFIRM);
        }
    }

    @Test
    public void whenNotRestricted_consequentialIsConfirmed() {
        assertThat(A2uiActionPolicy.decide(CONSEQUENTIAL, UX_RESTRICTIONS_BASELINE, TRUSTED))
                .isEqualTo(CONFIRM);
        assertThat(A2uiActionPolicy.decide(DESTRUCTIVE, UX_RESTRICTIONS_BASELINE, TRUSTED))
                .isEqualTo(CONFIRM);
        assertThat(A2uiActionPolicy.decide(SAFE, UX_RESTRICTIONS_BASELINE, TRUSTED))
                .isEqualTo(ALLOW);
    }

    @Test
    public void whileRestricted_safeAndNavigationStillWork() {
        // Refusing everything would be safe and useless. Pausing music at a red light is the case
        // this library exists to serve.
        assertThat(A2uiActionPolicy.decide(SAFE, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED))
                .isEqualTo(ALLOW);
        assertThat(A2uiActionPolicy.decide(NAVIGATION, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED))
                .isEqualTo(ALLOW);
    }

    @Test
    public void textEntry_isGatedByTheKeyboardBit_notByConsequence() {
        assertThat(A2uiActionPolicy.decide(TEXT_ENTRY, UX_RESTRICTIONS_NO_KEYBOARD, TRUSTED))
                .isEqualTo(REFUSE);
        // Restricted, but not the keyboard: typing is permitted because the vehicle permits it.
        assertThat(A2uiActionPolicy.decide(TEXT_ENTRY, UX_RESTRICTIONS_NO_VIDEO, TRUSTED))
                .isEqualTo(ALLOW);
    }

    // 3. Provenance: the bits cannot unlock anything they did not come from.

    @Test
    public void untrustworthyReading_refusesEverythingAboveSafe_evenAtBaseline() {
        // BASELINE bits on an untrustworthy reading are the fail-closed default's, not the vehicle's.
        assertThat(A2uiActionPolicy.decide(CONSEQUENTIAL, UX_RESTRICTIONS_BASELINE, UNTRUSTED))
                .isEqualTo(REFUSE);
        assertThat(A2uiActionPolicy.decide(DESTRUCTIVE, UX_RESTRICTIONS_BASELINE, UNTRUSTED))
                .isEqualTo(REFUSE);
        assertThat(A2uiActionPolicy.decide(TEXT_ENTRY, UX_RESTRICTIONS_BASELINE, UNTRUSTED))
                .isEqualTo(REFUSE);
    }

    @Test
    public void untrustworthyReading_decidesExactlyAsTheStrictestRealStateDoes() {
        // "No reading" decides as the worst state a vehicle can report, asserted for every class.
        for (A2uiActionClass cls : A2uiActionClass.values()) {
            assertThat(A2uiActionPolicy.decide(cls, UX_RESTRICTIONS_BASELINE, UNTRUSTED))
                    .isEqualTo(A2uiActionPolicy.decide(
                            cls, UX_RESTRICTIONS_FULLY_RESTRICTED, TRUSTED));
        }
    }

    @Test
    public void untrustworthyReading_neverConfirms() {
        for (A2uiActionClass cls : A2uiActionClass.values()) {
            assertThat(A2uiActionPolicy.decide(cls, UX_RESTRICTIONS_BASELINE, UNTRUSTED))
                    .isNotEqualTo(CONFIRM);
        }
    }

    // 4. The ordering the policy is written in terms of.

    @Test
    public void severitiesAreStrictlyIncreasing_andIndependentOfDeclarationOrder() {
        // Every rule is "nothing above this class", so a changed severity would invert decisions.
        assertThat(SAFE.severity()).isEqualTo(0);
        assertThat(NAVIGATION.severity()).isEqualTo(1);
        assertThat(TEXT_ENTRY.severity()).isEqualTo(2);
        assertThat(CONSEQUENTIAL.severity()).isEqualTo(3);
        assertThat(DESTRUCTIVE.severity()).isEqualTo(4);
    }

    @Test
    public void mostRestrictive_resolvesUpward() {
        assertThat(A2uiActionClass.mostRestrictive(SAFE, DESTRUCTIVE)).isEqualTo(DESTRUCTIVE);
        assertThat(A2uiActionClass.mostRestrictive(DESTRUCTIVE, SAFE)).isEqualTo(DESTRUCTIVE);
        assertThat(A2uiActionClass.mostRestrictive(SAFE, SAFE)).isEqualTo(SAFE);
    }

    @Test
    public void isAtLeast_isInclusive() {
        assertThat(CONSEQUENTIAL.isAtLeast(CONSEQUENTIAL)).isTrue();
        assertThat(CONSEQUENTIAL.isAtLeast(DESTRUCTIVE)).isFalse();
        assertThat(DESTRUCTIVE.isAtLeast(SAFE)).isTrue();
    }
}
