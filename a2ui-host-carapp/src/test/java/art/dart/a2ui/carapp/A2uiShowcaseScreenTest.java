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
package art.dart.a2ui.carapp;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;
import android.content.pm.ApplicationInfo;

import com.android.car.ui.utils.CarUxRestrictionsUtil.RestrictionsSource;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;

import org.junit.Test;

import java.util.Set;

/**
 * The provenance rule the conformance format cannot check ("Scope note 2"): a never-delivered or
 * stale reading must not decide the allowed set.
 */
public class A2uiShowcaseScreenTest {

    @Test
    public void debugBusRequiresDebuggableAppOnANonUserBuild() {
        ApplicationInfo info = new ApplicationInfo();
        info.flags = ApplicationInfo.FLAG_DEBUGGABLE;
        assertThat(A2uiShowcaseScreen.debugBusAllowed(info, "userdebug")).isTrue();
        assertThat(A2uiShowcaseScreen.debugBusAllowed(info, "user")).isFalse();
        info.flags = 0;
        assertThat(A2uiShowcaseScreen.debugBusAllowed(info, "eng")).isFalse();
    }

    // May the microphone be opened at all.

    @Test
    public void aDeliveredBaselineReadingAllowsListening() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isTrue();
    }

    @Test
    public void anExplicitDemoReadingIsAuthoritativeWithoutPretendingToBeDelivered() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.DEMO,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isTrue();
        assertThat(A2uiShowcaseScreen.isReadingTrustworthy(null, RestrictionsSource.DEMO)).isTrue();
    }

    /** The vehicle's no-transcription bit refuses listening. */
    @Test
    public void theVehiclesNoVoiceTranscriptionBitRefusesListening() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION)))
                .isFalse();
    }

    /** A never-delivered reading has all-zero bits, which alone would permit transcription. */
    @Test
    public void aNeverDeliveredReadingRefusesListening_thoughItsBitsSayBaseline() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.NEVER_DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isFalse();
    }

    @Test
    public void aDisconnectedReadingRefusesListening() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.DISCONNECTED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isFalse();
    }

    /** A demo override changes bits but never upgrades an untrustworthy live provenance. */
    @Test
    public void aDemoOverrideCannotUnlockANeverDeliveredReading() {
        assertThat(A2uiShowcaseScreen.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
                RestrictionsSource.NEVER_DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isFalse();
        assertThat(A2uiShowcaseScreen.allowsListening(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                RestrictionsSource.NEVER_DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))).isFalse();
    }

    /** FULLY_RESTRICTED contains the voice bit, so it must refuse too. */
    @Test
    public void aFullyRestrictedVehicleRefusesListening() {
        assertThat(A2uiShowcaseScreen.allowsListening(null, RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED))).isFalse();
    }

    // Asking for the microphone: once, and only for a refusal asking could fix.

    @Test
    public void aMissingPermissionIsWorthAskingAbout() {
        assertThat(A2uiShowcaseScreen.shouldRequestMicrophone(
                TranscriptSource.Unavailable.PERMISSION_DENIED, false)).isTrue();
    }

    /** A driver who declines is not asked again. Re-prompting is how dialogs stop being read. */
    @Test
    public void havingAskedOnceItDoesNotAskAgain() {
        assertThat(A2uiShowcaseScreen.shouldRequestMicrophone(
                TranscriptSource.Unavailable.PERMISSION_DENIED, true)).isFalse();
    }

    /**
     * No other refusal raises a dialog; the size assertion keeps the loop over the whole enum.
     */
    @Test
    public void noOtherRefusalRaisesAMicrophoneDialog() {
        int seen = 0;
        for (TranscriptSource.Unavailable reason : TranscriptSource.Unavailable.values()) {
            seen++;
            if (reason == TranscriptSource.Unavailable.PERMISSION_DENIED) {
                continue;
            }
            assertThat(A2uiShowcaseScreen.shouldRequestMicrophone(reason, false)).isFalse();
        }
        assertThat(seen).isEqualTo(5);
    }

    private static CarUxRestrictions restrictions(int activeFlags) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(activeFlags);
        return uxr;
    }

    // Provenance gates the bits, not the other way round.

    /** A never-delivered reading whose bits say BASELINE must not unlock the whole catalog. */
    @Test
    public void neverDelivered_failsClosed_evenWhenTheBitsSayBaseline() {
        Set<ComponentType> allowed = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.NEVER_DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        assertThat(allowed).isEqualTo(A2uiRestrictionPolicy.failClosedComponents());
        assertThat(allowed).doesNotContain(ComponentType.VIDEO);
    }

    /** Same for a connection that arrived and then went away. */
    @Test
    public void disconnected_failsClosed_evenWhenTheBitsSayBaseline() {
        Set<ComponentType> allowed = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.DISCONNECTED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        assertThat(allowed).isEqualTo(A2uiRestrictionPolicy.failClosedComponents());
    }

    /** The counter-test: a genuine BASELINE reading unlocks the catalog. */
    @Test
    public void delivered_baseline_allowsTheWholeCatalog() {
        Set<ComponentType> allowed = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        assertThat(allowed).hasSize(ComponentType.values().length);
    }

    @Test
    public void delivered_restrictedReadingIsHonoured() {
        Set<ComponentType> allowed = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO));

        assertThat(allowed).doesNotContain(ComponentType.VIDEO);
        assertThat(allowed).contains(ComponentType.LIST);
    }

    /**
     * A genuine FULLY_RESTRICTED reading and a never-delivered one produce the same set by design;
     * only provenance differs.
     */
    @Test
    public void genuineFullyRestrictedAndNeverDelivered_agreeOnTheSetAndDifferOnlyInProvenance() {
        Set<ComponentType> genuine = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED));
        Set<ComponentType> absent = A2uiShowcaseScreen.allowedFor(
                RestrictionsSource.NEVER_DELIVERED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        assertThat(genuine).isEqualTo(absent);
    }

    // The title: a demo override must be legible in a screenshot.

    @Test
    public void liveTitleIsTheSurfacesOwnWords() {
        String title = A2uiShowcaseScreen.title(null, "Coffee on the way");

        assertThat(title).isEqualTo("Coffee on the way");
        assertThat(title).doesNotContain("DEMO");
    }

    @Test
    public void overriddenTitleSaysDemo_andNamesTheMask() {
        String title = A2uiShowcaseScreen.title(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO, "Coffee on the way");

        assertThat(title).isEqualTo("Coffee on the way · DEMO 0x10");
    }

    /** B7: the header reads the root's title from the trace; ids and counts stay in the log. */
    @Test
    public void subjectComesFromTheTraceRoot_neverFromTheId() {
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").setTitle("Coffee on the way").build();
        A2uiComponent untitled = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").build();

        assertThat(A2uiShowcaseScreen.subjectFor("poi_discovery_order", root))
                .isEqualTo("Coffee on the way");
        assertThat(A2uiShowcaseScreen.subjectFor("poi_discovery_order", untitled))
                .isEqualTo("A2UI");
        assertThat(A2uiShowcaseScreen.subjectFor("poi_discovery_order", null))
                .isEqualTo("A2UI");
        assertThat(A2uiShowcaseScreen.subjectFor(DemoTrace.SURFACE_ID, root))
                .isEqualTo("A2UI showcase");
    }

    // Provenance gates the value limits too: a fail-closed reading's numbers describe no vehicle.

    private static CarUxRestrictions restrictionsWithLimits(
            int activeFlags, int maxString, int maxItems, int maxDepth) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(activeFlags);
        when(uxr.getMaxRestrictedStringLength()).thenReturn(maxString);
        when(uxr.getMaxCumulativeContentItems()).thenReturn(maxItems);
        when(uxr.getMaxContentDepth()).thenReturn(maxDepth);
        return uxr;
    }

    @Test
    public void neverDelivered_ignoresTheNumbersOnTheDefaultObject() {
        // Bits say BASELINE and the numbers are generous; provenance must override both.
        A2uiValueLimits limits = A2uiShowcaseScreen.limitsFor(
                RestrictionsSource.NEVER_DELIVERED,
                restrictionsWithLimits(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                        9999, 9999, 9999));

        assertThat(limits.isUnrestricted()).isFalse();
        assertThat(limits.getMaxStringLength())
                .isEqualTo(A2uiValueLimits.failClosed().getMaxStringLength());
    }

    @Test
    public void disconnected_ignoresTheNumbersOnTheDefaultObject() {
        A2uiValueLimits limits = A2uiShowcaseScreen.limitsFor(
                RestrictionsSource.DISCONNECTED,
                restrictionsWithLimits(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                        9999, 9999, 9999));

        assertThat(limits.getMaxContentItems())
                .isEqualTo(A2uiValueLimits.failClosed().getMaxContentItems());
    }

    @Test
    public void delivered_usesTheVehiclesOwnNumbers() {
        A2uiValueLimits limits = A2uiShowcaseScreen.limitsFor(
                RestrictionsSource.DELIVERED,
                restrictionsWithLimits(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH
                                | A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                        120, 21, 3));

        // Not the fail-closed values: a real reading's configuration is the vehicle's to set, and
        // substituting ours would be as wrong as ignoring the restriction.
        assertThat(limits.getMaxStringLength()).isEqualTo(120);
        assertThat(limits.getMaxContentItems()).isEqualTo(21);
        assertThat(limits.getMaxContentDepth()).isEqualTo(3);
    }

    @Test
    public void delivered_andUnrestricted_appliesNoLimits() {
        A2uiValueLimits limits = A2uiShowcaseScreen.limitsFor(
                RestrictionsSource.DELIVERED,
                restrictionsWithLimits(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0, 0, 0));

        assertThat(limits.isUnrestricted()).isTrue();
    }

    // The reader: isReadingTrustworthy encodes a demo-only concession.

    @Test
    public void withNoOverride_theLiveMaskIsUsed() {
        assertThat(A2uiShowcaseScreen.effectiveRestrictions(
                null, restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO)))
                .isEqualTo(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
    }

    @Test
    public void anOverrideReplacesTheLiveMask() {
        assertThat(A2uiShowcaseScreen.effectiveRestrictions(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)))
                .isEqualTo(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Test
    public void anOverrideOfZeroIsStillAnOverride() {
        // BASELINE is 0x0, which a truthiness check would get wrong.
        assertThat(A2uiShowcaseScreen.effectiveRestrictions(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)))
                .isEqualTo(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(A2uiShowcaseScreen.isReadingTrustworthy(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                RestrictionsSource.NEVER_DELIVERED)).isFalse();
    }

    @Test
    public void withoutAnOverride_trustFollowsProvenanceExactly() {
        // With no override, as in every release build, only provenance decides.
        for (RestrictionsSource source : RestrictionsSource.values()) {
            assertThat(A2uiShowcaseScreen.isReadingTrustworthy(null, source))
                    .isEqualTo(source == RestrictionsSource.DELIVERED
                            || source == RestrictionsSource.DEMO);
        }
    }

    @Test
    public void anOverrideNeverUpgradesProvenance() {
        for (RestrictionsSource source : RestrictionsSource.values()) {
            assertThat(A2uiShowcaseScreen.isReadingTrustworthy(
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO, source))
                    .isEqualTo(source == RestrictionsSource.DELIVERED
                            || source == RestrictionsSource.DEMO);
        }
    }
}
