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

package com.android.car.ui.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Reports what an ordinary app can actually reach on this device, to logcat under tag
 * {@code UxrDisplayDiag}. Asserts nothing — it exists to establish the ground truth a per-display
 * test can rely on, because {@code dumpsys display} and {@code DisplayManager.getDisplays()} do not
 * agree: private displays appear in the former and are hidden from the latter.
 */
@RunWith(AndroidJUnit4.class)
public class DisplayVisibilityDiagnosticTest {

    private static final String TAG = "UxrDisplayDiag";

    @Test
    public void reportReachableDisplays() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        DisplayManager dm = app.getSystemService(DisplayManager.class);

        Display[] enumerated = dm.getDisplays();
        Log.i(TAG, "getDisplays() count=" + enumerated.length);
        for (Display d : enumerated) {
            Log.i(TAG, "  enumerated id=" + d.getDisplayId() + " name=" + d.getName());
        }

        // Probe ids that dumpsys reports but enumeration may hide.
        for (int id : new int[] {0, 1, 2, 3, 4}) {
            Display d = dm.getDisplay(id);
            if (d == null) {
                Log.i(TAG, "  getDisplay(" + id + ") = null");
                continue;
            }
            String ctx;
            try {
                Context c = app.createDisplayContext(d);
                ctx = "createDisplayContext OK, resolved="
                        + CarUxRestrictionsUtil.displayIdOrDefault(c);
            } catch (RuntimeException e) {
                ctx = "createDisplayContext threw " + e.getClass().getSimpleName();
            }
            Log.i(TAG, "  getDisplay(" + id + ") = " + d.getName() + " | " + ctx);
        }
    }
}
