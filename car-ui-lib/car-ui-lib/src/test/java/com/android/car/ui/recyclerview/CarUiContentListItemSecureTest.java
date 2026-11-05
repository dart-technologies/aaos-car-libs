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
package com.android.car.ui.recyclerview;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Dart fork test: CHARACTERISATION of a known defect, not a test of correct behaviour.
 *
 * <p>This file pins what the code currently does, which is the WRONG thing, so that changing it is
 * a deliberate act taken against recorded evidence rather than an easy-looking one-line fix. It was
 * written as a fix-pinning test, the fix was applied, and the fix was reverted the same day — see
 * {@code CarUiContentListItem.mIsSecure} for the measurement that caused the reversal (secure-by-
 * default destabilises the instrumented suite: 0 failures in 4 runs permissive, 3 failures in 1 of
 * 4 runs secure, with click-shaped symptoms indicating filtered touches and no identified
 * mechanism).
 *
 * <p>{@code SecureView} exists to reject touches while the window is obscured — the standard
 * defence against tapjacking — and it defaults to secure:
 * {@code SecureView.java:35  private boolean mSecure = true;}
 *
 * <p>{@code CarUiContentListItem} carried the same property and did NOT default it, so it was
 * {@code false}. {@code CarUiListItemAdapter} then pushes that value onto the view on every bind,
 * at three sites ({@code CarUiListItemAdapter.java:257, 263, 269}):
 *
 * <pre>((SecureView) mTouchInterceptor).setSecure(item.isSecure());</pre>
 *
 * <p>So two defaults disagree and only one of them is ever read: {@code SecureView} is instantiated
 * only from {@code car_ui_list_item.xml} and {@code car_ui_list_item_compact.xml}, so its
 * {@code mSecure = true} initialiser is unreachable by construction. An earlier version of this
 * docstring said a deliberate secure default "was made insecure by the act of binding", in the past
 * tense, and kept saying it after the fix was reverted — describing the shipping state as history.
 *
 * <p><b>The suite's blindness is one-directional, and that is new information.</b> The upstream
 * report measured that mutating {@code SecureView}'s initialiser from {@code true} to {@code false}
 * — making it MORE permissive, exactly the state every bind produced — left car-ui-lib's
 * instrumented suite at 497 tests and 13 failures, an identical failing set. **That measurement is
 * stale**: this suite is now 503 tests with 3 baseline failures, and it has not been re-taken.
 * What the reversal suggested is the other direction — switching the default to secure appeared to
 * make the filtering fire — but on 1 failing run in 4, plus a clean full-gate run with the fix in
 * place. "The suite notices immediately" was too strong; three of four runs did not notice.
 *
 * <p>And the framing both rest on is wrong. The condition is NOT unobservable: upstream had a test
 * that staged a real overlay from a companion APK and deleted it in {@code 627a58b5a} because
 * "It doesn't work in gradle". The blindness belongs to this Gradle-only build.
 *
 * <p>See {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md} and {@code docs/STATUS.md} §24.
 * The question of oversight-versus-intent is answered by the commit that added the API
 * ({@code fc27816ff}): "Add a setSecure() method THAT WHEN SET…", for PermissionController. It is
 * opt-in by design. Arguing the default is wrong is still legitimate; calling it an oversight is
 * not.
 */
public class CarUiContentListItemSecureTest {

    @Test
    public void aListItemIsNOTSecureByDefault_whichIsTheDefect() {
        // Pinned deliberately. SecureView defaults to secure; this does not, and the adapter pushes
        // this value onto that view on every bind. If you are here because you changed this to
        // true and this test failed: that IS the right fix in principle, and it was tried. Read
        // CarUiContentListItem.mIsSecure first -- it destabilised the instrumented suite, and the
        // reason is not yet understood.
        for (CarUiContentListItem.Action action : CarUiContentListItem.Action.values()) {
            CarUiContentListItem item = new CarUiContentListItem(action);
            assertThat(item.isSecure()).isFalse();
        }
    }

    @Test
    public void securityCanStillBeOptedIntoExplicitly_andRoundTrips() {
        // The mechanism itself is intact: a caller that wants overlay protection can ask for it,
        // and can change its mind. Only the DEFAULT is wrong, and that is what is pinned above.
        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);

        // setSecure(false) on a field that is ALREADY false asserts nothing -- it passes against an
        // empty method body. Ordered true-then-false so both transitions are observable, which is
        // what "round trips" has to mean once the default went back to false.
        item.setSecure(true);
        assertThat(item.isSecure()).isTrue();

        item.setSecure(false);
        assertThat(item.isSecure()).isFalse();

        item.setSecure(true);
        assertThat(item.isSecure()).isTrue();
    }

    @Test
    public void everyActionVariantCarriesTheSameWrongDefault() {
        // The adapter binds different interceptor views per Action and reads isSecure() for each,
        // so the defect must be uniform across Actions or the inconsistency would look deliberate.
        // It is uniform: every Action defaults permissive, and every one can be set explicitly.
        for (CarUiContentListItem.Action action : CarUiContentListItem.Action.values()) {
            // setSecure(true), not (false): opting IN is the transition that is observable while
            // the default is false. The previous version set it to false and asserted false, which
            // passed whether or not setSecure did anything at all.
            CarUiContentListItem item = new CarUiContentListItem(action);
            item.setSecure(true);
            assertThat(item.isSecure()).isTrue();

            CarUiContentListItem untouched = new CarUiContentListItem(action);
            assertThat(untouched.isSecure()).isFalse();
        }
    }
}
