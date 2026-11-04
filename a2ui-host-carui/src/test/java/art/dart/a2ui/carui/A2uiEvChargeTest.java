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

import static art.dart.a2ui.carui.A2uiHostCardsTest.assertGlanceable;
import static art.dart.a2ui.carui.A2uiHostCardsTest.body;
import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.row;
import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static art.dart.a2ui.carui.A2uiHostCardsTest.titles;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.provider.Settings;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.List;

/** Charge and a coffee: the vehicle's battery reading starts it, inside the prune/decide path. */
@RunWith(RobolectricTestRunner.class)
public class A2uiEvChargeTest {

    private static final int DRIVE = 0xff;
    private static final String SUGGESTION_20 = "Battery 20% · 3 fast chargers on your way";
    private static final String SESSION = "Charging · 20% → 80% · about 22 min · DEMO";
    private static final String COFFEE = "Coffee ready at the counter";

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
    public void theThresholdIsTwentyPercentOfTheVehiclesOwnCapacity() {
        assertThat(A2uiCarUiActivity.lowBattery(null)).isFalse();
        assertThat(A2uiCarUiActivity.lowBattery(21)).isFalse();
        assertThat(A2uiCarUiActivity.lowBattery(20)).isTrue();
        assertThat(A2uiCarUiActivity.lowBattery(18)).isTrue();
        assertThat(VehicleEnergy.percentOf(27000f, 150000f)).isEqualTo(18);
        assertThat(VehicleEnergy.percentOf(30000f, 150000f)).isEqualTo(20);
        // No capacity, or a level the capacity cannot hold: no reading rather than a guess.
        assertThat(VehicleEnergy.percentOf(27000f, 0f)).isEqualTo(-1);
        assertThat(VehicleEnergy.percentOf(200000f, 150000f)).isEqualTo(-1);
    }

    @Test
    public void lowBatteryFromTheVehiclePromptsAGlanceableComparisonWhileDriving() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("ev_charge_coffee");
        activity.onVehicleBattery(21);
        assertThat(titles(activity)).doesNotContain("Battery 21% · 3 fast chargers on your way");

        activity.onVehicleBattery(20);
        assertThat(activity.renderedRowsForTesting().get(0))
                .isSameInstanceAs(row(activity, SUGGESTION_20));
        assertThat(body(row(activity, SUGGESTION_20)))
                .containsExactly("From the vehicle · low battery");
        assertGlanceable(row(activity, SUGGESTION_20));
        // The comparison rows are the surface's, through the prune; the tour is not.
        assertThat(titles(activity)).containsAtLeast("Fast charger A · DEMO",
                "Fast charger B · DEMO", "Fast charger C · DEMO");
        assertThat(titles(activity)).doesNotContain("Finish the Met tour");
    }

    @Test
    public void choosingAChargerByVoiceIsNavigationAndRunsInMotion() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("ev_charge_coffee");
        activity.onVehicleBattery(20);
        ShadowAlertDialog.reset();

        activity.onHeard("Take me to the first one.");

        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull();
        assertThat(activity.mChosenCharger).isEqualTo(0);
        assertThat(body(row(activity, SUGGESTION_20)))
                .containsExactly("Navigating to Fast charger A · DEMO");
    }

    @Test
    public void theParkedWindowOpensThenItsCardsCollapseWhenDrivingAway() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("ev_charge_coffee");
        activity.onVehicleBattery(20);
        activity.onHeard("take me to the first one");
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        assertThat(activity.mAtCharger).isTrue();
        assertGlanceable(row(activity, SESSION));
        assertThat(titles(activity)).doesNotContain(SUGGESTION_20);
        assertThat(titles(activity)).contains("Finish the Met tour");

        // The existing order flow: parked, confirmed, then the pickup card.
        activity.onHeard("reorder my coffee");
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertGlanceable(row(activity, COFFEE));

        drive(activity, DRIVE);
        assertThat(titles(activity)).containsNoneOf(SESSION, COFFEE, "Finish the Met tour",
                SUGGESTION_20);
        assertThat(activity.changeStripForTesting().getText().toString())
                .startsWith("Artwork hidden · Charging & order cards collapsed · ");
    }

    @Test
    public void aDemoReadingIsLabelledAndOnlyDebugBuildsAcceptIt() {
        mApp.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity release = started(DRIVE);
        release.selectCase("ev_charge_coffee");
        mApp.sendBroadcast(vehicle(18));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(titles(release)).doesNotContain("Battery 18% · 3 fast chargers on your way");

        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity debug = started(DRIVE);
        debug.selectCase("ev_charge_coffee");
        mApp.sendBroadcast(vehicle(18));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(body(row(debug, "Battery 18% · 3 fast chargers on your way")))
                .containsExactly("From the vehicle · DEMO reading");
    }

    @Test
    public void chargerFixtureRowsAreNearestFirstAndNeedThree() {
        String json = "{\"chargers\":["
                + "{\"name\":\"Far\",\"lat\":40.80,\"lon\":-73.95,\"kW\":350,\"operator\":\"X\"},"
                + "{\"name\":\"Near\",\"lat\":40.7800,\"lon\":-73.9640,\"kW\":150},"
                + "{\"name\":\"Mid\",\"lat\":40.79,\"lon\":-73.96,\"kw\":50},"
                + "{\"name\":\"NoPower\",\"lat\":40.78,\"lon\":-73.96}]}";
        List<ChargerFixture.Charger> chargers = ChargerFixture.parse(json);
        assertThat(chargers).hasSize(3);
        assertThat(chargers.get(0).mName).isEqualTo("Near");
        assertThat(chargers.get(2).mName).isEqualTo("Far · X");
        assertThat(ChargerFixture.messagesFor(chargers)).hasSize(6);
        assertThat(ChargerFixture.messagesFor(chargers.subList(0, 2))).isEmpty();
        assertThat(ChargerFixture.parse("nope")).isEmpty();
    }

    private static Intent vehicle(int pct) {
        return new Intent(DebugBroadcasts.ACTION_VEHICLE)
                .putExtra(DebugBroadcasts.EXTRA_BATTERY_PCT, pct);
    }
}
