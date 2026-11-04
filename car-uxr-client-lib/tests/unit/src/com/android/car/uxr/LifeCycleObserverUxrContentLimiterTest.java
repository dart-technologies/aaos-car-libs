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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.lifecycle.LifecycleOwner;

import com.android.car.ui.recyclerview.ContentLimiting;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that the lifecycle wrapper actually forwards.
 *
 * <p>Three one-line delegations, which is exactly the kind of code that goes untested and then
 * fails to register a listener at all. The failure mode here is silent: a wrapper that forwards
 * {@code onStart} but not {@code onStop} leaks a registration, and a wrapper that forwards neither
 * simply never limits content — with no exception at any point.
 */
@RunWith(JUnit4.class)
public class LifeCycleObserverUxrContentLimiterTest {

    private UxrContentLimiterImpl mDelegate;
    private LifeCycleObserverUxrContentLimiter mObserver;
    private LifecycleOwner mOwner;

    @Before
    public void setUp() {
        mDelegate = mock(UxrContentLimiterImpl.class);
        mOwner = mock(LifecycleOwner.class);
        mObserver = new LifeCycleObserverUxrContentLimiter(mDelegate);
    }

    @Test
    public void onStart_startsTheDelegate() {
        mObserver.onStart(mOwner);

        verify(mDelegate).start();
    }

    @Test
    public void onStop_stopsTheDelegate() {
        mObserver.onStop(mOwner);

        verify(mDelegate).stop();
    }

    @Test
    public void setAdapter_forwardsToTheDelegate() {
        ContentLimiting adapter = mock(ContentLimiting.class);

        mObserver.setAdapter(adapter);

        verify(mDelegate).setAdapter(adapter);
    }
}
