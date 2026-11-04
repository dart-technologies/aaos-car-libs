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
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

import java.util.ArrayList;
import java.util.List;

/** Charge and a coffee: the vehicle's battery reading starts it, inside the prune/decide path. */
@RunWith(RobolectricTestRunner.class)
public class A2uiEvChargeTest {

    private static final int DRIVE = 0xff;
    private static final String SUGGESTION_20 = "Battery 20% · 3 chargers on your way";
    /** The station and the vehicle's reading only: ChargePoint tags no kW, so no rate or ETA. */
    private static final String SESSION = "Charging at ChargePoint · 20%";
    private static final String CREDIT = " · © OpenStreetMap";
    private static final String COFFEE = "Coffee ready at the counter";
    private static final String NOTE = "Demo · nothing sent to the café";

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
        assertThat(titles(activity)).doesNotContain("Battery 21% · 3 chargers on your way");

        activity.onVehicleBattery(20);
        assertThat(activity.renderedRowsForTesting().get(0))
                .isSameInstanceAs(row(activity, SUGGESTION_20));
        assertThat(body(row(activity, SUGGESTION_20)))
                .containsExactly("From the vehicle · low battery" + CREDIT);
        assertGlanceable(row(activity, SUGGESTION_20));
        // No station is called fast unless OSM tags its output; nothing here says "fast".
        assertThat(String.join(" ", titles(activity))).doesNotContain("fast");
        // The comparison rows are the surface's (OSM names via the processor); the tour is not.
        assertThat(titles(activity)).containsAtLeast("ChargePoint", "FLO",
                "Tesla Supercharger");
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
                .containsExactly("Navigating to ChargePoint" + CREDIT);
    }

    @Test
    public void theParkedWindowOpensThenItsCardsCollapseWhenDrivingAway() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("ev_charge_coffee");
        CoffeeFixture.Pick cafe = bundledCafe();
        activity.onVehicleBattery(20);
        activity.onHeard("take me to the first one");
        // Not while driving: why the café was picked is parked-only detail.
        assertThat(titles(activity)).doesNotContain(cafe.whyTitle());
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        assertThat(activity.mAtCharger).isTrue();
        assertGlanceable(row(activity, SESSION));
        assertThat(body(row(activity, SESSION))).isEmpty();
        assertThat(String.join(" ", titles(activity))).doesNotContain("80%");
        assertThat(titles(activity)).doesNotContain(SUGGESTION_20);
        assertThat(titles(activity)).contains("Finish the Met tour");
        // Parked at the charger it was picked for: why this café, and where that came from.
        assertGlanceable(row(activity, cafe.whyTitle()));
        assertThat(body(row(activity, cafe.whyTitle())))
                .containsExactly(cafe.mWhy, cafe.mSource).inOrder();

        // The existing order flow: parked, confirmed, then the pickup card.
        activity.onHeard("Order my usual coffee.");
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(dialogTexts(confirm)).containsAtLeast(cafe.mPrice == null
                ? "Order your usual: oat milk latte?"
                : "Order your usual: oat milk latte, " + cafe.mPrice + "?",
                cafe.mName + " · " + cafe.mWalkMinutes + " min walk from the charger");
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        // A demo order, never sent: nothing on the card says the coffee is ready.
        String pickup = "Demo order · " + cafe.mName;
        assertGlanceable(row(activity, pickup));
        assertThat(body(row(activity, pickup))).containsExactly(
                "Oat milk latte · not sent · " + cafe.mWalkMinutes + " min walk");
        assertThat(titles(activity)).doesNotContain(COFFEE);
        assertThat(String.join(" ", titles(activity))).doesNotContain("ready");
        assertThat(titles(activity)).contains(cafe.whyTitle());

        drive(activity, DRIVE);
        assertThat(titles(activity)).containsNoneOf(SESSION, pickup, cafe.whyTitle(),
                "Finish the Met tour", SUGGESTION_20);
        assertThat(activity.changeStripForTesting().getText().toString())
                .isEqualTo("Put away for the drive: artwork, charging, order, café");
    }

    @Test
    public void anotherChargerKeepsTheGenericWordsAndNamesNoCafe() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("ev_charge_coffee");
        CoffeeFixture.Pick cafe = bundledCafe();
        activity.onVehicleBattery(20);
        activity.onHeard("take me to the second one");
        assertThat(activity.mChosenCharger).isEqualTo(1);
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        // The walk was measured from the first charger, so nothing here names the café.
        assertThat(activity.mAtCharger).isTrue();
        assertThat(String.join(" ", titles(activity))).doesNotContain(cafe.mName);
        activity.onHeard("Order my usual coffee.");
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(dialogTexts(confirm))
                .containsAtLeast("Order your usual: oat milk latte?", "Pickup at the counter");
        assertThat(String.join(" ", dialogTexts(confirm))).doesNotContain(cafe.mName);
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(body(row(activity, COFFEE))).containsExactly("Oat milk latte", NOTE).inOrder();
        assertThat(String.join(" ", titles(activity))).doesNotContain(cafe.mName);
    }

    @Test
    public void theBundledCafeIsTheOneTheActivityLoads() {
        A2uiCarUiActivity activity = started(DRIVE);
        CoffeeFixture.Pick cafe = bundledCafe();
        assertThat(activity.mCoffee).isNotNull();
        assertThat(activity.mCoffee.mName).isEqualTo(cafe.mName);
        // Measured from the charger "take me to the first one" picks: the nearest Host B draws.
        assertThat(cafe.mCharger).isEqualTo("ChargePoint");
        assertThat(cafe.mWalkMinutes).isAtMost(CoffeeFixture.MAX_WALK_MINUTES);
        assertThat(cafe.mWhy.startsWith("Muse picked it · ")
                || cafe.mWhy.startsWith("Muse compared ")).isTrue();
        assertThat(cafe.mWhy).endsWith(" · " + cafe.mWalkMinutes + " min walk");
        assertThat(cafe.mSource.startsWith("Source · ")
                || cafe.mSource.startsWith("Runner-up: ")).isTrue();
    }

    @Test
    public void cafeFixtureNeedsEveryDisplayFieldAndNeverInventsAPrice() {
        String ok = "{'display':{'name':'Cafe','walkMinutes':2,'why':'Muse picked it · 2 min walk',"
                + "'source':'Source · a.com','price':null,'charger':'ChargePoint'}}";
        CoffeeFixture.Pick pick = CoffeeFixture.parse(ok);
        assertThat(pick).isNotNull();
        assertThat(pick.confirmTitle()).isEqualTo("Order your usual: oat milk latte?");
        assertThat(pick.confirmDetail()).isEqualTo("Cafe · 2 min walk from the charger");
        assertThat(pick.pickupTitle()).isEqualTo("Demo order · Cafe");
        assertThat(pick.pickupDetail()).isEqualTo("Oat milk latte · not sent · 2 min walk");
        assertThat(pick.whyBody()).containsExactly("Muse picked it · 2 min walk", "Source · a.com")
                .inOrder();
        assertThat(pick.isNear("ChargePoint")).isTrue();
        assertThat(pick.isNear("FLO")).isFalse();
        assertThat(pick.isNear(null)).isFalse();
        assertThat(CoffeeFixture.parse(ok.replace("null", "'$6.25'")).confirmTitle())
                .isEqualTo("Order your usual: oat milk latte, $6.25?");
        assertThat(CoffeeFixture.parse(ok.replace("null", "'about six dollars'")).mPrice)
                .isNull();
        assertThat(CoffeeFixture.parse(ok.replace("'walkMinutes':2", "'walkMinutes':7")))
                .isNull();
        assertThat(CoffeeFixture.parse(ok.replace("Source · ", ""))).isNull();
        // The runner-up may stand in for the source line; any other lead-in fails closed.
        assertThat(CoffeeFixture.parse(ok.replace("Source · a.com", "Runner-up: Other Cafe"))
                .whyBody()).containsExactly("Muse picked it · 2 min walk",
                        "Runner-up: Other Cafe").inOrder();
        assertThat(CoffeeFixture.parse(ok.replace("Source · a.com", "Runner-up: "))).isNull();
        assertThat(CoffeeFixture.parse(ok.replace("Source · a.com", "Source · "))).isNull();
        assertThat(CoffeeFixture.parse(ok.replace("Source · a.com", "Rated: Other Cafe")))
                .isNull();
        assertThat(CoffeeFixture.parse(ok.replace("'name':'Cafe'", "'name':' '"))).isNull();
        assertThat(CoffeeFixture.parse("{}")).isNull();
        assertThat(CoffeeFixture.parse("nope")).isNull();
        assertThat(CoffeeFixture.parse(null)).isNull();
    }

    /** Every string a dialog draws: car-ui's builder puts the title in its own view. */
    private static List<String> dialogTexts(AlertDialog dialog) {
        List<String> out = new ArrayList<>();
        collectTexts(dialog.getWindow().getDecorView(), out);
        return out;
    }

    private static void collectTexts(View view, List<String> out) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out);
        }
    }

    /** The café the debug build bundles (fixtures/coffee/COFFEE.json), read as the app reads it. */
    private CoffeeFixture.Pick bundledCafe() {
        CoffeeFixture.Pick cafe = CoffeeFixture.parse(
                ChargerFixture.readAsset(mApp, CoffeeFixture.ASSET));
        assertThat(cafe).isNotNull();
        return cafe;
    }

    @Test
    public void onlyDebugBuildsAcceptABroadcastReading() {
        mApp.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity release = started(DRIVE);
        release.selectCase("ev_charge_coffee");
        mApp.sendBroadcast(vehicle(18));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(titles(release)).doesNotContain("Battery 18% · 3 chargers on your way");

        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity debug = started(DRIVE);
        debug.selectCase("ev_charge_coffee");
        mApp.sendBroadcast(vehicle(18));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(body(row(debug, "Battery 18% · 3 chargers on your way")))
                .containsExactly("From the vehicle · low battery" + CREDIT);
    }

    @Test
    public void chargerFixtureRowsAreNearestFirstAndNeedThree() {
        String json = "{\"chargers\":["
                + "{\"name\":\"Far\",\"lat\":40.80,\"lon\":-73.95,\"kW\":350,\"operator\":\"X\"},"
                + "{\"name\":\"Near\",\"lat\":40.7800,\"lon\":-73.9640,\"kW\":150},"
                + "{\"name\":\"Mid\",\"lat\":40.79,\"lon\":-73.96,\"kw\":50},"
                + "{\"name\":\"NoPower\",\"lat\":40.78,\"lon\":-73.96}]}";
        List<ChargerFixture.Charger> chargers = ChargerFixture.parse(json);
        // Untagged power is kept and simply not shown; nothing is guessed.
        assertThat(chargers).hasSize(4);
        assertThat(chargers.get(0).mName).isEqualTo("Near");
        assertThat(chargers.get(3).mName).isEqualTo("Far · X");
        assertThat(ChargerFixture.detail(chargers.get(0))).isEqualTo("0.1 mi · 150 kW");
        assertThat(ChargerFixture.detail(chargers.get(1))).isEqualTo("0.2 mi");
        assertThat(ChargerFixture.messagesFor(chargers)).hasSize(6);
        assertThat(ChargerFixture.messagesFor(chargers.subList(0, 2))).isEmpty();
        assertThat(ChargerFixture.parse("nope")).isEmpty();
    }

    private static Intent vehicle(int pct) {
        return new Intent(DebugBroadcasts.ACTION_VEHICLE)
                .putExtra(DebugBroadcasts.EXTRA_BATTERY_PCT, pct);
    }
}
