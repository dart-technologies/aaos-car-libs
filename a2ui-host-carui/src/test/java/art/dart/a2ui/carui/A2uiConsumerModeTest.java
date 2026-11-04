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

import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Arrays;
import java.util.Collections;

/** Consumer screens: no engineering chrome, cards from the same rows, contextual confirmations. */
@RunWith(RobolectricTestRunner.class)
public class A2uiConsumerModeTest {

    private Application mApp;
    private int mOriginalFlags;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        CarUiInstaller.register(mApp);
        CarUxRestrictionsUtil.clearInstancesForTesting();
        mOriginalFlags = mApp.getApplicationInfo().flags;
        Settings.Global.putFloat(mApp.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        mApp.getApplicationInfo().flags = mOriginalFlags;
    }

    @Test
    public void engineeringChromeHidesUntilTechnicalDetailsAreAskedFor() {
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("poi_discovery_order");
        assertThat(activity.mProviderItem.isVisible()).isFalse();
        TextView badge = activity.findViewById(R.id.status_badge);
        assertThat(badge.getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.demoPillForTesting().getVisibility()).isEqualTo(View.VISIBLE);

        // A long-press on the screen's title opens the details, and the chip with them.
        activity.findViewById(R.id.surface_heading).performLongClick();
        assertThat(activity.mProviderItem.isVisible()).isTrue();
        assertThat(badge.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void visitorSurfacesShowCardsAndTheComparisonKeepsItsLists() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.cardListForTesting().getVisibility()).isEqualTo(View.GONE);
        activity.selectCase("poi_discovery_order");
        assertThat(activity.cardListForTesting().getVisibility()).isEqualTo(View.VISIBLE);
        // The cards read the same rows: no more of them than the rows held.
        assertThat(activity.cardListForTesting().getAdapter().getItemCount())
                .isEqualTo(activity.renderedRowsForTesting().size());
        assertThat(activity.findViewById(R.id.limited_column).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void aConfirmationAsksTheActionsOwnQuestion() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("poi_discovery_order");
        CarUiContentListItem order = A2uiHostCardsTest.row(activity, "Order cold brew + bagel");
        order.getOnClickListener().onClick(order);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(texts(dialog.getWindow().getDecorView()))
                .contains("Order a cold brew and a bagel for pickup?");
        assertThat(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString())
                .isEqualTo("Confirm");
        assertThat(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString())
                .isEqualTo("Cancel");
    }

    private static java.util.List<String> texts(View root) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (root instanceof TextView) out.add(((TextView) root).getText().toString());
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(texts(group.getChildAt(i)));
        }
        return out;
    }

    @Test
    public void cardsLiftMetaAndDropVoiceHintsButInventNothing() {
        CardListAdapter.Card route = CardListAdapter.cardFor("Arrive 2:41 PM · 19 min",
                Collections.singletonList("Hudson Yards → The Met"));
        assertThat(route.mTitle).isEqualTo("Arrive 2:41 PM");
        assertThat(route.mMeta).isEqualTo("19 min");

        CardListAdapter.Card charger = CardListAdapter.cardFor("Tesla Supercharger",
                Arrays.asList("0.9 mi · 72 kW", "say “take me to the first one”"));
        assertThat(charger.mMeta).isEqualTo("0.9 mi");
        assertThat(charger.mSubtitle).isEqualTo("72 kW");
        assertThat(charger.mExtra).isEmpty();

        CardListAdapter.Card guide = CardListAdapter.cardFor("Now playing · Audio guide",
                Collections.singletonList("The Harvesters (4/6)"));
        assertThat(guide.mSubtitle).isEqualTo("The Harvesters");
        assertThat(guide.mMeta).isEqualTo("4/6");

        CardListAdapter.Card plain = CardListAdapter.cardFor("Today at The Met",
                Arrays.asList("Open 10 AM–5 PM", "Source · metmuseum.org"));
        assertThat(plain.mMeta).isNull();
        assertThat(plain.mExtra).containsExactly("Source · metmuseum.org");
    }
}
