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

package com.android.car.apps.common.util;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.car.content.pm.CarPackageManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for the <b>counter-example</b> in {@code docs/ARCHITECTURE.md} — the one place this
 * codebase gets the polarity right, and the strongest single piece of evidence for the rule the
 * whole taxonomy is built on.
 *
 * <p>Every safety predicate in this library defaults to something when its dependency is missing.
 * {@code CarUxRestrictionsUtil}'s catch block defaulted to {@code BASELINE} — no restrictions —
 * which is the fail-open defect this fork exists partly to fix. These methods default to
 * {@code false}, and because the question they ask is <em>"is this certified safe to show while
 * driving?"</em> rather than <em>"is this restricted?"</em>, the same careless default <b>denies</b>
 * instead of permitting.
 *
 * <p>That is not a different author being more careful. It is the phrasing of the predicate doing
 * the work, which is exactly why the taxonomy's rule is *phrase safety predicates as grants*.
 *
 * <p>These tests pin the polarity so it cannot drift. A change here to {@code return true} on the
 * null path would be silent in production — apps would simply stop being restricted — and nothing
 * else in the repository observes it.
 */
@RunWith(RobolectricTestRunner.class)
public class CarPackageManagerUtilsTest {

    // ---------------------------------------------------------------- PendingIntent overload

    /**
     * THE ASSERTION THIS FILE EXISTS FOR. A null {@code CarPackageManager} — car service absent,
     * exactly the condition that produced the fail-open defect elsewhere — must yield
     * "not certified", which callers route to <em>restricted</em>.
     */
    @Test
    public void pendingIntent_nullCarPackageManager_isNotDistractionOptimized() {
        assertThat(CarPackageManagerUtils.isDistractionOptimized(
                null, mock(PendingIntent.class))).isFalse();
    }

    @Test
    public void pendingIntent_delegatesToCarPackageManager_whenCertified() {
        CarPackageManager cpm = mock(CarPackageManager.class);
        PendingIntent intent = mock(PendingIntent.class);
        when(cpm.isPendingIntentDistractionOptimized(intent)).thenReturn(true);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(cpm, intent)).isTrue();
    }

    @Test
    public void pendingIntent_delegatesToCarPackageManager_whenNotCertified() {
        CarPackageManager cpm = mock(CarPackageManager.class);
        PendingIntent intent = mock(PendingIntent.class);
        when(cpm.isPendingIntentDistractionOptimized(intent)).thenReturn(false);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(cpm, intent)).isFalse();
    }

    // ---------------------------------------------------------------- ActivityInfo overload

    @Test
    public void activityInfo_nullCarPackageManager_isNotDistractionOptimized() {
        ActivityInfo info = new ActivityInfo();
        info.packageName = "com.example";
        info.name = "com.example.Main";

        assertThat(CarPackageManagerUtils.isDistractionOptimized(null, info)).isFalse();
    }

    @Test
    public void activityInfo_delegatesUsingPackageAndClassName() {
        CarPackageManager cpm = mock(CarPackageManager.class);
        ActivityInfo info = new ActivityInfo();
        info.packageName = "com.example";
        info.name = "com.example.Main";
        when(cpm.isActivityDistractionOptimized("com.example", "com.example.Main"))
                .thenReturn(true);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(cpm, info)).isTrue();
    }

    // ---------------------------------------------------------------- Intent overload

    /**
     * An intent that resolves to nothing must also deny.
     *
     * <p>This is the second absence on the same path — no car service, and no resolvable activity —
     * and it is handled the same way. Worth pinning separately because it is a different branch.
     */
    @Test
    public void intent_unresolvable_isNotDistractionOptimized() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveActivity(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(
                mock(CarPackageManager.class), pm, new Intent())).isFalse();
    }

    @Test
    public void intent_resolvesAndDelegates() {
        CarPackageManager cpm = mock(CarPackageManager.class);
        PackageManager pm = mock(PackageManager.class);
        ActivityInfo info = new ActivityInfo();
        info.packageName = "com.example";
        info.name = "com.example.Main";
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = info;
        when(pm.resolveActivity(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(resolved);
        when(cpm.isActivityDistractionOptimized("com.example", "com.example.Main"))
                .thenReturn(true);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(cpm, pm, new Intent())).isTrue();
    }

    /**
     * Both absences at once: unresolvable intent <em>and</em> no car service. Still denies.
     */
    @Test
    public void intent_unresolvableAndNoCarService_isNotDistractionOptimized() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.resolveActivity(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);

        assertThat(CarPackageManagerUtils.isDistractionOptimized(null, pm, new Intent())).isFalse();
    }
}
