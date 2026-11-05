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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;

import com.android.car.ui.utils.CarUxRestrictionsUtil.RestrictionsSource;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.Test;

/**
 * Host B may not open a microphone the vehicle has forbidden. Tests the seam and the rule
 * separately; {@link #hostBAsksTheSameQuestionAsHostA} pins Host B's seam to
 * {@code A2uiRestrictionPolicy}'s answer across every source and mask.
 */
public class A2uiVoiceGateTest {

    private static CarUxRestrictions restrictions(int activeFlags) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(activeFlags);
        return uxr;
    }

    // -------------------------------------------------------------------------------------
    // The bit. The vehicle's explicit "do not transcribe".

    @Test
    public void theNoTranscriptionBitRefusesListening() {
        assertThat(A2uiCarUiActivity.allowsListening(RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION)))
                .isFalse();
    }

    @Test
    public void aFullyRestrictedVehicleRefusesListening() {
        assertThat(A2uiCarUiActivity.allowsListening(RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)))
                .isFalse();
    }

    @Test
    public void aParkedVehicleAllowsListening() {
        // The other half: a gate that never opens is indistinguishable from a broken microphone.
        assertThat(A2uiCarUiActivity.allowsListening(RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)))
                .isTrue();
    }

    // -------------------------------------------------------------------------------------
    // Provenance. The half a bitmask cannot carry.

    @Test
    public void aReadingThatWasNeverDeliveredRefusesListening_evenThoughItsBitsAreZero() {
        // A never-delivered reading is byte-identical to a genuine BASELINE, so a mask-only gate
        // would open here.
        for (RestrictionsSource source : RestrictionsSource.values()) {
            if (source == RestrictionsSource.DELIVERED || source == RestrictionsSource.DEMO) {
                continue;
            }
            assertThat(A2uiCarUiActivity.allowsListening(
                    source, restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)))
                    .isFalse();
        }
    }

    @Test
    public void everyNonDeliveredSourceRefusesAtEveryMask() {
        // The whole enum, so a source added later cannot slip past.
        int[] masks = {
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED,
        };
        for (RestrictionsSource source : RestrictionsSource.values()) {
            for (int mask : masks) {
                boolean expected = (source == RestrictionsSource.DELIVERED
                        || source == RestrictionsSource.DEMO)
                        && A2uiRestrictionPolicy.allowsVoiceTranscription(mask);
                assertThat(A2uiCarUiActivity.allowsListening(source, restrictions(mask)))
                        .isEqualTo(expected);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // The seam is the model's rule, not a second copy of it.

    @Test
    public void hostBAsksTheSameQuestionAsHostA() {
        // Both hosts call A2uiRestrictionPolicy.allowsListening, so this compares the seam with the
        // shared rule itself, unlike CarUiRendererTest#provenanceRuleAgreesWithHostA's restatement.
        for (RestrictionsSource source : RestrictionsSource.values()) {
            for (int mask : new int[]{0x0, 0x8, 0x10, 0x20, 0x100, 0x1ff}) {
                boolean trustworthy = source == RestrictionsSource.DELIVERED
                        || source == RestrictionsSource.DEMO;
                assertThat(A2uiCarUiActivity.allowsListening(source, restrictions(mask)))
                        .isEqualTo(A2uiRestrictionPolicy.allowsListening(mask, trustworthy));
            }
        }
    }
}
