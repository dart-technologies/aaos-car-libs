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

package com.android.car.uxr;

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;
import android.util.Log;

import com.android.car.ui.recyclerview.ContentLimiting;
import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.car.uxr.CarUxRestrictionsAppConfig.ListConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the delegation in {@link UxrContentLimiterImpl}.
 *
 * <p>This module had zero tests across 538 lines. The 25 unit tests this fork wrote for
 * {@code CarUxRestrictionsUtil} cover the <em>producer</em> of restriction state in
 * {@code car-ui-lib}; this module is the <em>consumer</em>, and the wiring between them — register
 * on start, unregister on stop, translate a restriction into an item cap — was untested on both
 * sides of the boundary.
 *
 * <p>It is testable only because a seam was added to {@link UxrContentLimiterImpl}'s constructor
 * first. See the javadoc there.
 */
@RunWith(JUnit4.class)
public class UxrContentLimiterImplTest {

    private static final int ADAPTER_ID = 42;
    private static final int DEFAULT_CAP_FROM_CAR = 10;
    private static final int OVERRIDE_CAP = 3;
    private static final int OVERRIDE_MESSAGE_RES_ID = 0x7f010001;

    private CarUxRestrictionsUtil mUtil;
    private ContentLimiting mAdapter;
    private MockedStatic<Log> mLog;

    @Before
    public void setUp() {
        // The module's unitTests block deliberately sets returnDefaultValues = false, so an
        // unstubbed android.jar call throws instead of silently returning 0/false. android.util.Log
        // is the only such call on these paths and it is pure diagnostics, so it is stubbed HERE,
        // narrowly and visibly, rather than by relaxing the module-wide setting. The point of the
        // strict setting is that every stub is a decision somebody wrote down; this is that.
        mLog = Mockito.mockStatic(Log.class);
        mLog.when(() -> Log.isLoggable(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);

        mUtil = mock(CarUxRestrictionsUtil.class);
        mAdapter = mock(ContentLimiting.class);
        when(mAdapter.getConfigurationId()).thenReturn(ADAPTER_ID);
    }

    @After
    public void tearDown() {
        mLog.close();
    }

    private static CarUxRestrictions restrictions(int activeFlags, int maxItems) {
        CarUxRestrictions r = mock(CarUxRestrictions.class);
        when(r.getActiveRestrictions()).thenReturn(activeFlags);
        when(r.getMaxCumulativeContentItems()).thenReturn(maxItems);
        return r;
    }

    private static CarUxRestrictionsAppConfig configWith(ListConfig... configs) {
        Map<Integer, ListConfig> mapping = new HashMap<>();
        for (ListConfig c : configs) {
            mapping.put(c.getId(), c);
        }
        return new CarUxRestrictionsAppConfig(mapping);
    }

    private static CarUxRestrictionsAppConfig emptyConfig() {
        return new CarUxRestrictionsAppConfig(Collections.emptyMap());
    }

    private UxrContentLimiterImpl limiterWith(CarUxRestrictionsAppConfig config) {
        return new UxrContentLimiterImpl(mUtil, config);
    }

    // -------------------------------------------------------------------------------------
    // The delegation: start/stop must reach the util, with the SAME listener instance
    // -------------------------------------------------------------------------------------

    @Test
    public void start_registersWithTheUtil() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());

        limiter.start();

        verify(mUtil).register(limiter.getListenerForTesting());
    }

    @Test
    public void stop_unregistersFromTheUtil() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());

        limiter.stop();

        verify(mUtil).unregister(limiter.getListenerForTesting());
    }

    /**
     * The listener must be a stable instance across start/stop.
     *
     * <p>{@code CarUxRestrictionsUtil} holds observers in a {@code WeakHashMap}-backed set and
     * unregisters by identity. A class that built a fresh listener per call would register one
     * object and unregister a different one — leaking the registration silently, with no error at
     * either call site. That failure is invisible to every other test here, so it gets its own.
     */
    @Test
    public void listenerIsStableAcrossCalls_soUnregisterCanMatchRegister() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());

        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener first =
                limiter.getListenerForTesting();
        limiter.start();
        limiter.stop();
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener afterCycle =
                limiter.getListenerForTesting();

        assertNotNull(first);
        // Reference equality is the contract, not equals(): the util unregisters by identity.
        if (first != afterCycle) {
            throw new AssertionError("listener instance changed across start/stop; "
                    + "unregister() would not match register()");
        }
    }

    // -------------------------------------------------------------------------------------
    // The translation: restriction flags -> item cap
    // -------------------------------------------------------------------------------------

    @Test
    public void noLimitContentFlag_setsUnlimited() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_NO_VIDEO, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(ContentLimiting.UNLIMITED);
    }

    /**
     * An unrelated restriction being active must not limit content.
     *
     * <p>This is the counterpart of the {@code isRestricted()} composite-mask bug fixed in
     * {@code CarUxRestrictionsUtil}: {@code (active & LIMIT_CONTENT) == 0} is the correct test for
     * a single flag, and mutating it to {@code != 0} — the shape of the original defect — would
     * make any restriction whatsoever limit lists. Pinned so that mutation is caught here too.
     */
    @Test
    public void unrelatedRestrictionAlone_doesNotLimit() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_NO_VIDEO | 0x100, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(ContentLimiting.UNLIMITED);
    }

    @Test
    public void limitContentWithoutOverride_usesTheCarsCap() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_LIMIT_CONTENT, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(DEFAULT_CAP_FROM_CAR);
    }

    @Test
    public void limitContentWithOverride_usesTheOverride() {
        UxrContentLimiterImpl limiter = limiterWith(configWith(
                ListConfig.builder(ADAPTER_ID).setContentLimit(OVERRIDE_CAP).build()));
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_LIMIT_CONTENT, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(OVERRIDE_CAP);
    }

    /**
     * A config registered for a <em>different</em> adapter must not apply to this one.
     *
     * <p>Without this, a test suite could pass with the id lookup removed entirely.
     */
    @Test
    public void overrideForAnotherAdapter_doesNotApply() {
        UxrContentLimiterImpl limiter = limiterWith(configWith(
                ListConfig.builder(ADAPTER_ID + 1).setContentLimit(OVERRIDE_CAP).build()));
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_LIMIT_CONTENT, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(DEFAULT_CAP_FROM_CAR);
    }

    /**
     * A matched config with no content limit set falls back to the car's cap.
     *
     * <p>This is the slot that matters: {@code ListConfig.mContentLimit} is a nullable
     * {@link Integer}, not an {@code int}, so "no override specified" is representable and distinct
     * from "override of 0". Upstream got this right here — the same codebase that declares
     * {@code CarUiContentListItem.mIsSecure} as a bare {@code boolean}. Both patterns are present;
     * this test pins the good one so it cannot regress into the other.
     */
    @Test
    public void matchedConfigWithNoLimit_fallsBackToTheCarsCap() {
        UxrContentLimiterImpl limiter = limiterWith(configWith(
                ListConfig.builder(ADAPTER_ID)
                        .setScrollingLimitedMessageResId(OVERRIDE_MESSAGE_RES_ID)
                        .build()));
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_LIMIT_CONTENT, DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(DEFAULT_CAP_FROM_CAR);
    }

    /**
     * {@code FULLY_RESTRICTED} is a composite that <em>contains</em> {@code LIMIT_CONTENT}.
     *
     * <p>It is also the value {@code CarUxRestrictionsUtil} now reports when car service is absent
     * or has died. So this test asserts that the fork's fail-closed default actually reaches the
     * adapter as a real cap rather than being read as "no limit" — the consumer-side half of the
     * fail-open fix, which nothing else covers.
     */
    @Test
    public void fullyRestricted_limitsContent_becauseItContainsLimitContent() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());
        limiter.setAdapter(mAdapter);

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED,
                        DEFAULT_CAP_FROM_CAR));

        verify(mAdapter).setMaxItems(DEFAULT_CAP_FROM_CAR);
    }

    // -------------------------------------------------------------------------------------
    // setAdapter wiring
    // -------------------------------------------------------------------------------------

    @Test
    public void setAdapter_appliesOverriddenMessageResId() {
        UxrContentLimiterImpl limiter = limiterWith(configWith(
                ListConfig.builder(ADAPTER_ID)
                        .setScrollingLimitedMessageResId(OVERRIDE_MESSAGE_RES_ID)
                        .build()));

        limiter.setAdapter(mAdapter);

        verify(mAdapter).setScrollingLimitedMessageResId(OVERRIDE_MESSAGE_RES_ID);
    }

    @Test
    public void setAdapter_withNoMatchingConfig_leavesMessageResIdAlone() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());

        limiter.setAdapter(mAdapter);

        verify(mAdapter, never()).setScrollingLimitedMessageResId(anyInt());
    }

    @Test
    public void setAdapter_withMatchingConfigButNoMessage_leavesMessageResIdAlone() {
        UxrContentLimiterImpl limiter = limiterWith(configWith(
                ListConfig.builder(ADAPTER_ID).setContentLimit(OVERRIDE_CAP).build()));

        limiter.setAdapter(mAdapter);

        verify(mAdapter, never()).setScrollingLimitedMessageResId(anyInt());
    }

    // -------------------------------------------------------------------------------------
    // The no-adapter path
    // -------------------------------------------------------------------------------------

    /**
     * Restrictions arriving before an adapter is registered must be dropped without throwing.
     *
     * <p>This is a real ordering: {@code CarUxRestrictionsUtil.register()} seeds the listener
     * synchronously with the current value, and {@code start()} may run before
     * {@code setAdapter()}. Upstream handles it; nothing asserted it.
     */
    @Test
    public void restrictionsBeforeAdapterRegistered_areDroppedWithoutThrowing() {
        UxrContentLimiterImpl limiter = limiterWith(emptyConfig());

        limiter.getListenerForTesting().onRestrictionsChanged(
                restrictions(UX_RESTRICTIONS_LIMIT_CONTENT, DEFAULT_CAP_FROM_CAR));

        verifyNoMoreInteractions(mAdapter);
    }
}
