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

import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.row;
import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The confirmation dialog on the 1408 x 792 reference display: one layout for tapped and spoken
 * confirmations, a single visible Confirm, and that Confirm decided by the policy as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1408dp-h792dp-land-mdpi")
public class A2uiConfirmDialogTest {
    private static final int BASELINE = A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
    private static final int DRIVE = 0xff;
    private static final String RESERVE_QUESTION = "Reserve 2 timed-entry tickets for 3:00 PM?";
    private static final String ORDER = "Order cold brew + bagel";

    @Before
    public void setUp() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity.clearGenuineDestinationRestrictionsForTesting();
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void theQuestionAndDetailShareAnEdgeAndTheButtonsOneRowInsideTheDisplay() {
        A2uiCarUiActivity activity = started(BASELINE);
        activity.selectCase("met_visit");
        activity.onHeard("reserve entry for two at three");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(dialog).isNotNull();
        View root = dialog.findViewById(R.id.a2ui_confirm_root);
        TextView title = dialog.findViewById(R.id.a2ui_confirm_title);
        TextView message = dialog.findViewById(R.id.a2ui_confirm_message);
        Button cancel = dialog.findViewById(R.id.a2ui_confirm_cancel);
        Button confirm = dialog.findViewById(R.id.a2ui_confirm_ok);
        assertThat(title.getText().toString()).isEqualTo(RESERVE_QUESTION);
        assertThat(message.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(message.getText().toString()).isNotEmpty();

        // Capped and centred on the wide display, never closer than the margin to an edge.
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        assertThat(metrics.widthPixels).isEqualTo(1408);
        int margin = Math.round(A2uiCarUiActivity.DIALOG_MARGIN_DP * metrics.density);
        int width = dialog.getWindow().getAttributes().width;
        assertThat(width).isEqualTo(
                Math.round(A2uiCarUiActivity.DIALOG_MAX_WIDTH_DP * metrics.density));
        assertThat(width).isAtMost(metrics.widthPixels - 2 * margin);

        // Laid out at the window's width, so the geometry below is measured, not defaulted.
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        assertThat(title.getWidth()).isGreaterThan(0);
        assertThat(confirm.getWidth()).isGreaterThan(0);
        assertThat(root.getMeasuredHeight()).isAtMost(metrics.heightPixels - 2 * margin);

        // The question and its detail start at one left edge: the dialog's padding.
        assertThat(xIn(title, root)).isEqualTo(root.getPaddingStart());
        assertThat(xIn(message, root)).isEqualTo(xIn(title, root));
        assertThat(title.getTextSize()).isGreaterThan(message.getTextSize());

        // Cancel then Confirm, in one row under the detail, ending at the bottom-right padding.
        assertThat(yIn(cancel, root)).isEqualTo(yIn(confirm, root));
        assertThat(yIn(cancel, root)).isAtLeast(yIn(message, root) + message.getHeight());
        assertThat(xIn(cancel, root) + cancel.getWidth()).isLessThan(xIn(confirm, root));
        assertThat(xIn(confirm, root) + confirm.getWidth())
                .isEqualTo(root.getWidth() - root.getPaddingEnd());
        assertThat(yIn(confirm, root) + confirm.getHeight())
                .isEqualTo(root.getHeight() - root.getPaddingBottom());
    }

    @Test
    public void exactlyOneConfirmAndOneCancelAreOnScreenAndTheContractStays() {
        A2uiCarUiActivity activity = started(BASELINE);
        activity.selectCase("met_visit");
        activity.onHeard("reserve entry for two at three");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        View decor = dialog.getWindow().getDecorView();

        // What uiautomator and rotary can reach: one of each, the recorder's exact label.
        List<String> visible = visibleTexts(decor);
        assertThat(Collections.frequency(visible, "Confirm")).isEqualTo(1);
        assertThat(Collections.frequency(visible, "Cancel")).isEqualTo(1);
        assertThat(isInside(dialog.findViewById(R.id.a2ui_confirm_ok), decor)).isTrue();
        // The framework's buttons keep the AlertDialog contract but are in no window.
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertThat(positive.getText().toString()).isEqualTo("Confirm");
        assertThat(negative.getText().toString()).isEqualTo("Cancel");
        assertThat(isInside(positive, decor)).isFalse();
        assertThat(isInside(negative, decor)).isFalse();
    }

    @Test
    public void theVisibleConfirmIsDecidedByThePolicyAndCancelDecidesNothing() {
        A2uiCarUiActivity activity = started(BASELINE);
        activity.selectCase("met_visit");
        activity.onHeard("reserve entry for two at three");
        AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
        first.findViewById(R.id.a2ui_confirm_cancel).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(first.isShowing()).isFalse();
        assertThat(activity.mOrderPlaced).isFalse();

        activity.onHeard("reserve entry for two at three");
        AlertDialog second = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(second).isNotSameInstanceAs(first);
        second.findViewById(R.id.a2ui_confirm_ok).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(second.isShowing()).isFalse();
        assertThat(activity.mOrderPlaced).isTrue();
    }

    @Test
    public void aVisibleConfirmPressedAfterTheCarMovesIsRefused() {
        A2uiCarUiActivity activity = started(BASELINE);
        activity.selectCase("met_visit");
        activity.onHeard("reserve entry for two at three");
        AlertDialog asked = ShadowAlertDialog.getLatestAlertDialog();
        drive(activity, DRIVE);
        asked.findViewById(R.id.a2ui_confirm_ok).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.mOrderPlaced).isFalse();
    }

    @Test
    public void aTappedConfirmationUsesTheSameLayoutAndItsConfirmIsDecidedAgain() {
        A2uiCarUiActivity activity = started(BASELINE);
        activity.selectCase("poi_discovery_order");
        CarUiContentListItem order = row(activity, ORDER);
        order.getOnClickListener().onClick(order);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        TextView title = dialog.findViewById(R.id.a2ui_confirm_title);
        assertThat(title.getText().toString())
                .isEqualTo("Order a cold brew and a bagel for pickup?");
        assertThat(Collections.frequency(visibleTexts(dialog.getWindow().getDecorView()),
                "Confirm")).isEqualTo(1);

        dialog.findViewById(R.id.a2ui_confirm_ok).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(dialog.isShowing()).isFalse();
        assertThat(activity.mOrderPlaced).isTrue();
    }

    /** Left of {@code view} within {@code root}, summed through its parents. */
    private static int xIn(View view, View root) {
        int x = 0;
        for (View v = view; v != root; v = (View) v.getParent()) x += v.getLeft();
        return x;
    }

    private static int yIn(View view, View root) {
        int y = 0;
        for (View v = view; v != root; v = (View) v.getParent()) y += v.getTop();
        return y;
    }

    private static boolean isInside(View view, View root) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == root) return true;
        }
        return false;
    }

    /** Text of every TextView whose whole ancestry is visible: what an accessibility dump sees. */
    private static List<String> visibleTexts(View root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View view, List<String> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }
}
