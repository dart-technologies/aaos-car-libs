/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.carapp;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.testing.ScreenController;
import androidx.lifecycle.Lifecycle;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoRestrictions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.List;

/** Pins the dual gate on every exported debug receiver owned by Host A. */
@RunWith(RobolectricTestRunner.class)
public class A2uiDebugReceiverSecurityTest {

    @Test
    public void releaseBuildRegistersNoSetListenOrCaseReceiver() {
        Application app = RuntimeEnvironment.getApplication();
        ApplicationInfo info = app.getApplicationInfo();
        int originalFlags = info.flags;
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiShowcaseScreen release = null;
        A2uiShowcaseScreen debug = null;
        try {
            info.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
            release = new A2uiShowcaseScreen(
                    TestCarContext.createCarContext(app), new SilentSource());
            assertThat(debugActions(app)).isEmpty();

            info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
            CarUxRestrictionsUtil.clearInstancesForTesting();
            debug = new A2uiShowcaseScreen(
                    TestCarContext.createCarContext(app), new SilentSource());
            assertThat(debugActions(app)).containsAtLeast(
                    DemoRestrictions.ACTION_SET,
                    A2uiShowcaseScreen.ACTION_LISTEN,
                    A2uiShowcaseScreen.ACTION_CASE);
        } finally {
            if (release != null) {
                ScreenController controller = new ScreenController(release);
                controller.moveToState(Lifecycle.State.CREATED);
                controller.moveToState(Lifecycle.State.DESTROYED);
            }
            if (debug != null) {
                ScreenController controller = new ScreenController(debug);
                controller.moveToState(Lifecycle.State.CREATED);
                controller.moveToState(Lifecycle.State.DESTROYED);
            }
            CarUxRestrictionsUtil.clearInstancesForTesting();
            info.flags = originalFlags;
        }
    }

    private static List<String> debugActions(Application app) {
        List<String> found = new ArrayList<>();
        for (ShadowApplication.Wrapper wrapper : shadowOf(app).getRegisteredReceivers()) {
            IntentFilter filter = wrapper.getIntentFilter();
            if (filter == null) continue;
            for (int i = 0; i < filter.countActions(); i++) {
                String action = filter.getAction(i);
                if (DemoRestrictions.ACTION_SET.equals(action)
                        || A2uiShowcaseScreen.ACTION_LISTEN.equals(action)
                        || A2uiShowcaseScreen.ACTION_CASE.equals(action)) {
                    found.add(action);
                }
            }
        }
        return found;
    }

    private static final class SilentSource implements TranscriptSource {
        @Override public void listen(@NonNull Callback callback) { }
        @Override public void cancel() { }
    }
}
