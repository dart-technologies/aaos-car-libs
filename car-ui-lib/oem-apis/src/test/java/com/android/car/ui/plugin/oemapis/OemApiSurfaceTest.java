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
package com.android.car.ui.plugin.oemapis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.View;

import com.android.car.ui.plugin.oemapis.recyclerview.LayoutStyleOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The plugin contract's <b>shape</b>, asserted against the published artifact.
 *
 * <p>This exists because the shape has already been wrong once, in a way nothing caught until a
 * process crashed. The fork vendored 24 interfaces copied from the AOSP source tree; a javap diff
 * against {@code car-ui-lib-plugin-apis} showed that copy was published <b>1.0.1 minus nine
 * methods</b> on {@link RecyclerViewOEMV1}, with {@link LayoutStyleOEMV1}'s {@code getSpanSize}
 * weakened from {@code abstract} to {@code default}. Against a stock AAOS image, where the real
 * interface is abstract, the first call was {@code AbstractMethodError} — a process crash that
 * presented for hours as flake, then as a dependency regression, then as emulator degradation.
 *
 * <p>That diff was a one-off measurement made by hand. These tests are what make it standing: the
 * module now depends on the published artifact, so a future bump that removes a member, or weakens
 * one back to {@code default}, fails here rather than on a head unit.
 *
 * <p><b>Why assert `abstract` at all</b>, when a missing method would also fail to resolve: a
 * {@code default} method is the dangerous case precisely because it does <em>not</em> fail. It
 * silently absolves every implementor, so the divergence only becomes visible when someone runs
 * against the real plugin. Absence is loud; a permissive default is quiet.
 */
@RunWith(JUnit4.class)
public class OemApiSurfaceTest {

    /**
     * The nine methods the vendored copy lacked. Named individually rather than counted, so a
     * failure says which one went.
     */
    @Test
    public void recyclerViewOemV1_declaresTheNineMethodsTheVendoredCopyLacked() {
        assertDeclares(RecyclerViewOEMV1.class, "findViewByPosition", int.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedStart", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedEnd", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedMeasurement", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedMeasurementInOther", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedMeasuredWidth", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getDecoratedMeasuredHeight", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "getRecyclerViewChildPosition", View.class);
        assertDeclares(RecyclerViewOEMV1.class, "scrollToPositionWithOffset", int.class, int.class);
    }

    /** All nine must be abstract, for the reason in the class javadoc. */
    @Test
    public void recyclerViewOemV1_thoseNineAreAbstract_notDefault() {
        for (String name : new String[]{
                "findViewByPosition", "getDecoratedStart", "getDecoratedEnd",
                "getDecoratedMeasurement", "getDecoratedMeasurementInOther",
                "getDecoratedMeasuredWidth", "getDecoratedMeasuredHeight",
                "getRecyclerViewChildPosition", "scrollToPositionWithOffset"}) {
            for (Method m : RecyclerViewOEMV1.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    assertFalse(name + " is a default method; an implementor that omits it will "
                            + "compile and then be wrong at runtime", m.isDefault());
                    assertTrue(name + " is not abstract", Modifier.isAbstract(m.getModifiers()));
                }
            }
        }
    }

    /**
     * The one that actually crashed. {@code getSpanSize} is {@code default} in the AOSP source tree
     * — added there by {@code 9307f5b38}, 2021-10-26 — and {@code abstract} in every published
     * artifact from 1.0.1 to 1.5.0 and in the V9 plugin on a stock image.
     */
    @Test
    public void layoutStyleOemV1_getSpanSizeIsAbstract_notDefault() {
        Method m = declared(LayoutStyleOEMV1.class, "getSpanSize", int.class);

        assertFalse("getSpanSize is default in this artifact. That is the AOSP-source shape, not "
                + "the published one, and it is what produced the AbstractMethodError.",
                m.isDefault());
        assertTrue(Modifier.isAbstract(m.getModifiers()));
    }

    /**
     * The version this repo negotiates. {@code PluginVersionProviderImpl} returns null below V2 and
     * a {@code PluginFactoryOEMV2} at or above it; {@code OemApiUtil} advertises the same number.
     * Both must exist in whatever artifact is on the classpath.
     */
    @Test
    public void bothNegotiatedFactoryVersionsArePresent() {
        assertNotNull(PluginFactoryOEMV1.class);
        assertNotNull(PluginFactoryOEMV2.class);
    }

    /**
     * 1.5.0 carries factory versions beyond the two this repo implements. Pinned as a fact, not as
     * a requirement: the swap to the published artifact deliberately did NOT adopt them, and this
     * records that they are sitting there unused so the next person does not have to re-derive it.
     */
    @Test
    public void newerFactoryVersionsAreAvailableButNotImplementedHere() {
        int highestAvailable = 0;
        for (int v = 1; v <= 12; v++) {
            try {
                Class.forName("com.android.car.ui.plugin.oemapis.PluginFactoryOEMV" + v);
                highestAvailable = v;
            } catch (ClassNotFoundException e) {
                break;
            }
        }

        assertEquals("expected car-ui-lib-plugin-apis:1.5.0, which tops out at V7", 7,
                highestAvailable);
    }

    private static void assertDeclares(Class<?> type, String name, Class<?>... params) {
        assertNotNull(declared(type, name, params));
    }

    private static Method declared(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail(type.getSimpleName() + " no longer declares " + name
                    + " — the plugin contract changed shape. Re-run the javap diff in "
                    + "docs/VERIFICATION.md before adjusting this test.");
            throw new AssertionError(e);
        }
    }
}
