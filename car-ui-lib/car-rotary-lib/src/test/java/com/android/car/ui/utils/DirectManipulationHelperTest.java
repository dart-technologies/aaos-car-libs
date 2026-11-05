/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link DirectManipulationHelper}.
 *
 * <p>Dart fork note: moved from instrumented tests, where W^X rejected dexmaker's runtime dex and
 * every case failed. The class is pure logic, so Robolectric with ordinary Mockito suffices.
 */
@RunWith(RobolectricTestRunner.class)
public class DirectManipulationHelperTest {

    private final Context mContext = mock(Context.class);
    private final AccessibilityManager mAccessibilityManager = mock(AccessibilityManager.class);
    private final View mView = mock(View.class);

    private void givenAccessibilityEnabled(boolean enabled) {
        when(mView.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(Context.ACCESSIBILITY_SERVICE))
                .thenReturn(mAccessibilityManager);
        when(mAccessibilityManager.isEnabled()).thenReturn(enabled);
    }

    @Test
    public void testEnableDirectManipulationMode_fail() {
        givenAccessibilityEnabled(false);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, true);

        assertThat(sent).isFalse();
        verify(mAccessibilityManager, never()).sendAccessibilityEvent(any());
    }

    @Test
    public void testEnableDirectManipulationMode_enable() {
        givenAccessibilityEnabled(true);
        ArgumentCaptor<AccessibilityEvent> captor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, true);

        assertThat(sent).isTrue();
        verify(mAccessibilityManager).sendAccessibilityEvent(captor.capture());
        assertThat(captor.getValue().getClassName())
                .isEqualTo(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(captor.getValue().getEventType())
                .isEqualTo(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    }

    @Test
    public void testEnableDirectManipulationMode_disable() {
        givenAccessibilityEnabled(true);
        ArgumentCaptor<AccessibilityEvent> captor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, false);

        assertThat(sent).isTrue();
        verify(mAccessibilityManager).sendAccessibilityEvent(captor.capture());
        assertThat(captor.getValue().getClassName())
                .isEqualTo(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(captor.getValue().getEventType())
                .isEqualTo(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
    }

    @Test
    public void testIsDirectManipulation() {
        AccessibilityEvent event = AccessibilityEvent.obtain();

        assertThat(DirectManipulationHelper.isDirectManipulation(event)).isFalse();

        event.setClassName(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(DirectManipulationHelper.isDirectManipulation(event)).isTrue();
    }

    @Test
    public void testSupportRotateDirectly() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();

        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isFalse();

        node.setContentDescription("normal content description");
        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isFalse();

        node.setContentDescription(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isTrue();
    }

    @Test
    public void testSetSupportsRotateDirectly() {
        // A real View, not the mock: this asserts the helper's effect ON a view, so a mock would
        // record the call and prove nothing. The instrumented version needed a whole Activity and
        // a layout for this; a bare View is sufficient and is what the helper actually touches.
        View view = new View(ApplicationProvider.getApplicationContext());
        assertThat(view.getContentDescription()).isNull();

        DirectManipulationHelper.setSupportsRotateDirectly(view, true);
        assertThat(view.getContentDescription())
                .isEqualTo(DirectManipulationHelper.DIRECT_MANIPULATION);

        DirectManipulationHelper.setSupportsRotateDirectly(view, false);
        assertThat(view.getContentDescription()).isNull();
    }
}
