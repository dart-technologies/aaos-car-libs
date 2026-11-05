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

import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

import java.util.Set;

/** The derivation rules, and the composite-mask trap that {@code car-ui-lib} fell into. */
public class A2uiRestrictionPolicyTest {

    // Voice transcription.

    @Test
    public void baselineAllowsVoiceTranscription() {
        assertThat(A2uiRestrictionPolicy.allowsVoiceTranscription(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)).isTrue();
    }

    @Test
    public void theVoiceBitForbidsTranscription() {
        assertThat(A2uiRestrictionPolicy.allowsVoiceTranscription(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION)).isFalse();
    }

    /**
     * FULLY_RESTRICTED contains the voice bit; testing for equality with the bit instead of its
     * presence would pass the test above and fail here.
     */
    @Test
    public void aFullyRestrictedMaskForbidsTranscription() {
        assertThat(A2uiRestrictionPolicy.allowsVoiceTranscription(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)).isFalse();
    }

    /** Anti-vacuity: every other single bit allows transcription, so a constant false fails. */
    @Test
    public void noOtherRestrictionBitForbidsTranscription() {
        int[] others = {
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_DIALPAD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_TEXT_MESSAGE,
        };
        for (int bit : others) {
            assertThat(A2uiRestrictionPolicy.allowsVoiceTranscription(bit)).isTrue();
        }
        // The eight above plus the voice bit are the nine that compose FULLY_RESTRICTED. If a bit
        // is ever added, this sum stops matching and the loop stops silently covering less.
        int sum = A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION;
        for (int bit : others) {
            sum |= bit;
        }
        assertThat(sum).isEqualTo(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /** The constants duplicated in A2uiRestrictionPolicy must equal the platform's. */
    @Test
    public void constantsMatchThePlatform() {
        assertThat(UX_RESTRICTIONS_BASELINE)
                .isEqualTo(android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
        assertThat(UX_RESTRICTIONS_NO_VIDEO)
                .isEqualTo(android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO);
        assertThat(UX_RESTRICTIONS_NO_KEYBOARD)
                .isEqualTo(android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD);
        assertThat(UX_RESTRICTIONS_NO_SETUP)
                .isEqualTo(android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP);
        assertThat(UX_RESTRICTIONS_LIMIT_CONTENT)
                .isEqualTo(
                        android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT);
        assertThat(UX_RESTRICTIONS_FULLY_RESTRICTED)
                .isEqualTo(
                        android.car.drivingstate.CarUxRestrictions
                                .UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Test
    public void baselineAllowsTheWholeCatalog() {
        assertThat(A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_BASELINE))
                .hasSize(ComponentType.values().length);
    }

    // The composite: FULLY_RESTRICTED is 0x1ff, the union of the nine bits. An any-of test on it
    // answers "is anything restricted?" instead.

    @Test
    public void fullyRestrictedPrunesToTheGlanceableSet() {
        Set<ComponentType> allowed =
                A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_FULLY_RESTRICTED);

        assertThat(allowed).containsExactly(
                ComponentType.TEXT, ComponentType.ICON, ComponentType.ROW,
                ComponentType.COLUMN, ComponentType.DIVIDER, ComponentType.BUTTON);
    }

    /** A single unrelated bit is not the composite; an any-of test would prune a parked car. */
    @Test
    public void singleBitIsNotTheComposite() {
        Set<ComponentType> noVideo =
                A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_NO_VIDEO);

        assertThat(noVideo).hasSize(ComponentType.values().length - 1);
        assertThat(noVideo).doesNotContain(ComponentType.VIDEO);
        assertThat(noVideo).contains(ComponentType.LIST);
        assertThat(noVideo).contains(ComponentType.TEXT_FIELD);
    }

    /** 0x1ff minus one bit is not the composite; a >= or bit-count test passes the one above. */
    @Test
    public void almostFullyRestrictedIsNotFullyRestricted() {
        int almost = UX_RESTRICTIONS_FULLY_RESTRICTED & ~UX_RESTRICTIONS_NO_VIDEO;

        Set<ComponentType> allowed = A2uiRestrictionPolicy.allowedComponents(almost);

        // Not the glanceable set: LIST survives, because nothing in the individual bits removes it.
        assertThat(allowed).contains(ComponentType.LIST);
    }

    /** A future bit alongside the composite still reads as the composite; == 0x1ff would fail. */
    @Test
    public void futureBitsAlongsideTheCompositeStillReadAsFullyRestricted() {
        int withFutureBit = UX_RESTRICTIONS_FULLY_RESTRICTED | 0x200;

        assertThat(A2uiRestrictionPolicy.allowedComponents(withFutureBit))
                .isEqualTo(A2uiRestrictionPolicy.failClosedComponents());
    }

    // Composition: two bits remove the union of what each removes.

    @Test
    public void twoBitsComposeByIntersection() {
        Set<ComponentType> both = A2uiRestrictionPolicy.allowedComponents(
                UX_RESTRICTIONS_NO_VIDEO | UX_RESTRICTIONS_NO_KEYBOARD);

        assertThat(both).doesNotContain(ComponentType.VIDEO);
        assertThat(both).doesNotContain(ComponentType.TEXT_FIELD);
        assertThat(both).doesNotContain(ComponentType.DATE_TIME_INPUT);
        assertThat(both).contains(ComponentType.LIST);
    }

    /** An unrecognised bit must never widen the allowed set. */
    @Test
    public void unknownBitsNeverWidenTheSet() {
        Set<ComponentType> known = A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_NO_VIDEO);
        Set<ComponentType> withUnknown =
                A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_NO_VIDEO | 0x4000);

        assertThat(withUnknown).containsNoneIn(setDifference(withUnknown, known));
    }

    @Test
    public void allowedSetsAreImmutable() {
        Set<ComponentType> allowed =
                A2uiRestrictionPolicy.allowedComponents(UX_RESTRICTIONS_NO_VIDEO);

        try {
            allowed.add(ComponentType.VIDEO);
            throw new AssertionError(
                    "allowed set is mutable: a caller could reintroduce a pruned component");
        } catch (UnsupportedOperationException expected) {
            // The point of the test.
        }
    }

    /**
     * failClosedComponents() equals allowedComponents(FULLY_RESTRICTED) today, though they are
     * declared separately so they can diverge.
     */
    @Test
    public void failClosedAgreesWithFullyRestrictedToday() {
        assertThat(A2uiRestrictionPolicy.failClosedComponents())
                .isEqualTo(A2uiRestrictionPolicy.allowedComponents(
                        UX_RESTRICTIONS_FULLY_RESTRICTED));
    }

    private static Set<ComponentType> setDifference(
            Set<ComponentType> a, Set<ComponentType> b) {
        java.util.EnumSet<ComponentType> diff = java.util.EnumSet.noneOf(ComponentType.class);
        diff.addAll(a);
        diff.removeAll(b);
        return diff;
    }

    // -------------------------------------------------------------------------------------
    // allowsListening — the bit AND the provenance.

    @Test
    public void listeningNeedsBothTheBitAndATrustworthyReading() {
        // The full truth table: each cell fails differently.
        assertThat(A2uiRestrictionPolicy.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, true)).isTrue();
        assertThat(A2uiRestrictionPolicy.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, false)).isFalse();
        assertThat(A2uiRestrictionPolicy.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION, true)).isFalse();
        assertThat(A2uiRestrictionPolicy.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION, false)).isFalse();
    }

    @Test
    public void anUntrustworthyReadingRefusesAtEveryMask() {
        // Provenance dominates. Iterated over the same bits the class publishes rather than a
        // sample, so a restriction added later cannot quietly acquire an exception.
        for (int mask : new int[]{
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_DIALPAD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_TEXT_MESSAGE,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED}) {
            assertThat(A2uiRestrictionPolicy.allowsListening(mask, false)).isFalse();
        }
    }

    @Test
    public void withATrustworthyReadingOnlyTheTranscriptionBitRefuses() {
        // The other direction: bits unrelated to speech must not refuse transcription.
        for (int mask : new int[]{
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_DIALPAD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_FILTERING,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP}) {
            assertThat(A2uiRestrictionPolicy.allowsListening(mask, true)).isTrue();
        }
        assertThat(A2uiRestrictionPolicy.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION, true)).isFalse();
    }
}
