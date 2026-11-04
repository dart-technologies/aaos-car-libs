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

package com.chassis.car.ui.plugin;

import static android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for the plugin's copy of {@link SecureView} — the obscured-touch ("tapjacking") defence.
 *
 * <p>This is the <b>second</b> {@code SecureView} in the tree; `car-ui-lib` has its own. Both choose
 * {@code mSecure = true}, and that agreement is the load-bearing observation in
 * {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md}: two independent components picked secure by
 * default, and one list-item default overrides both.
 *
 * <p>`car-ui-lib`'s copy is <b>provably unobservable</b> by the inherited instrumented suite —
 * mutating its initialiser to {@code false} changes nothing across 497 tests, re-confirmed on a
 * corrected environment. These tests are the coverage that does not otherwise exist for this
 * behaviour anywhere in the repository, and they exist because Robolectric can stage an obscured
 * {@link MotionEvent} where instrumentation cannot stage an obscuring window.
 *
 * <p>That is the seam argument in `docs/VERIFICATION.md` applied rather than restated: the
 * input was unstageable in one environment and settable in another, so the choice of environment
 * <em>is</em> the coverage decision.
 */
@RunWith(RobolectricTestRunner.class)
public class SecureViewTest {

    private SecureView newView() {
        return new SecureView(ApplicationProvider.getApplicationContext());
    }

    private static MotionEvent obscured(boolean isObscured) {
        MotionEvent e = mock(MotionEvent.class);
        when(e.getFlags()).thenReturn(isObscured ? FLAG_WINDOW_IS_OBSCURED : 0);
        return e;
    }

    // ------------------------------------------------------------------ the default

    /**
     * The decision the upstream report rests on. If this ever flips, that report's central claim —
     * "a component that documents itself as secure-by-default" — has changed underneath it.
     */
    @Test
    public void defaultsToSecure() {
        assertThat(newView().isSecure()).isTrue();
    }

    @Test
    public void secureIsSettableBothWays() {
        SecureView v = newView();

        v.setSecure(false);
        assertThat(v.isSecure()).isFalse();

        v.setSecure(true);
        assertThat(v.isSecure()).isTrue();
    }

    // ------------------------------------------------------------------ the filtering itself

    @Test
    public void secure_rejectsTouchWhileWindowObscured() {
        assertThat(newView().onFilterTouchEventForSecurity(obscured(true))).isFalse();
    }

    @Test
    public void secure_allowsTouchWhenNotObscured() {
        assertThat(newView().onFilterTouchEventForSecurity(obscured(false))).isTrue();
    }

    /**
     * The condition {@code CarUiContentListItem} produces on every bind, and the one the upstream
     * report is about: with {@code secure = false} an obscured touch is <b>accepted</b>.
     *
     * <p>Characterisation of a documented setter, not of a defect — {@code setSecure(false)} is
     * meant to do this. The defect is that a list item reaches it without anyone asking.
     */
    @Test
    public void notSecure_acceptsTouchEvenWhileWindowObscured() {
        SecureView v = newView();
        v.setSecure(false);

        assertThat(v.onFilterTouchEventForSecurity(obscured(true))).isTrue();
    }

    @Test
    public void notSecure_acceptsTouchWhenNotObscured() {
        SecureView v = newView();
        v.setSecure(false);

        assertThat(v.onFilterTouchEventForSecurity(obscured(false))).isTrue();
    }

    /**
     * Only the obscured flag may matter. A view that filtered on unrelated flags would reject
     * legitimate touches, and nothing else in the suite would notice.
     */
    @Test
    public void unrelatedFlagsDoNotCauseFiltering() {
        // A bit that is not FLAG_WINDOW_IS_OBSCURED (0x1). Written as a literal rather than a named
        // constant because the neighbouring MotionEvent flags are @hide and absent from the public
        // SDK — the same surface constraint that shapes this whole fork.
        MotionEvent e = mock(MotionEvent.class);
        when(e.getFlags()).thenReturn(0x8);

        assertThat(newView().onFilterTouchEventForSecurity(e)).isTrue();
    }
}
