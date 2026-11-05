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
package com.android.car.media.common.source;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Browse, on a real device, against a real {@code MediaBrowserServiceCompat}.
 *
 * <p>{@code MediaItemsRepository} calls {@code subscribe}, {@code unsubscribe}, {@code search} and
 * {@code getRoot} and had no test at all; {@code MediaBrowserConnectorTest} mocks the browser, which
 * covers the connector's state machine and nothing about browsing. This is that gap's first
 * coverage, and it is the fixed point a Media3 browse implementation can later be compared against
 * — proven now, against the browser already in production, so that when the implementation changes
 * it is the only thing that changed.
 *
 * <p>Instrumented rather than Robolectric for a structural reason: on API ≥ 26
 * {@code MediaBrowserCompat} delegates to the framework {@code android.media.browse.MediaBrowser}
 * (confirmed by reflection — {@code MediaBrowserImplApi26}), and Robolectric does not emulate that
 * binding. Shadowing the framework class would mean measuring browse behaviour against an emulation
 * of the platform written by the change that needs the answer.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyBrowseFixtureTest {

    private static final long TIMEOUT_SECONDS = 10;

    private Context mContext;
    private MediaBrowserCompat mBrowser;

    @Before
    public void connect() throws Exception {
        mContext = getInstrumentation().getContext();
        ComponentName component = new ComponentName(mContext, FakeLegacyBrowseService.class);

        CountDownLatch settled = new CountDownLatch(1);
        String[] outcome = {"no callback at all"};
        // Created and connected on the main thread: MediaBrowserCompat posts its callbacks to the
        // looper it was constructed on, and the instrumentation thread has none.
        getInstrumentation().runOnMainSync(() -> {
            mBrowser = new MediaBrowserCompat(mContext, component,
                    new MediaBrowserCompat.ConnectionCallback() {
                        @Override
                        public void onConnected() {
                            outcome[0] = "connected";
                            settled.countDown();
                        }

                        @Override
                        public void onConnectionFailed() {
                            outcome[0] = "failed";
                            settled.countDown();
                        }
                    }, null);
            mBrowser.connect();
        });

        settled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Asserted on the OUTCOME rather than on the latch, because the two failures are different
        // facts and the first version of this fixture hit the silent one: a service that never
        // calls setSessionToken sends NEITHER callback, so "failed" and "nothing happened" would
        // otherwise be reported identically. See FakeLegacyBrowseService.
        assertThat(outcome[0]).isEqualTo("connected");
    }

    @After
    public void disconnect() {
        if (mBrowser != null) {
            getInstrumentation().runOnMainSync(() -> mBrowser.disconnect());
        }
    }

    @Test
    public void theBrowserConnectsAndReportsTheServicesRoot() {
        assertThat(mBrowser.isConnected()).isTrue();
        assertThat(mBrowser.getRoot()).isEqualTo(FakeLegacyBrowseService.ROOT_ID);
    }

    @Test
    public void subscribingDeliversTheServicesChildren() throws Exception {
        List<MediaBrowserCompat.MediaItem> received = new ArrayList<>();
        CountDownLatch loaded = new CountDownLatch(1);

        getInstrumentation().runOnMainSync(() -> mBrowser.subscribe(mBrowser.getRoot(),
                new MediaBrowserCompat.SubscriptionCallback() {
                    @Override
                    public void onChildrenLoaded(String parentId,
                            List<MediaBrowserCompat.MediaItem> children) {
                        received.addAll(children);
                        loaded.countDown();
                    }
                }));

        assertThat(loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // Real results across a real binder. A mocked browser could only ever have confirmed that
        // subscribe() was called.
        // inOrder() matters: the Media3 side asserts ordering and the equivalence claims the two
        // clients agree on it, so leaving the legacy side unordered would have let a reversed
        // result satisfy half of a claim about both.
        assertThat(ids(received))
                .containsExactly(FakeLegacyBrowseService.CHILD_A, FakeLegacyBrowseService.CHILD_B)
                .inOrder();
        assertThat(received.get(0).isBrowsable()).isTrue();
        assertThat(received.get(1).isPlayable()).isTrue();
    }

    @Test
    public void searchReturnsWhatTheServiceMatches() throws Exception {
        List<MediaBrowserCompat.MediaItem> hits = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        getInstrumentation().runOnMainSync(() -> mBrowser.search("one", null,
                new MediaBrowserCompat.SearchCallback() {
                    @Override
                    public void onSearchResult(String query, Bundle extras,
                            List<MediaBrowserCompat.MediaItem> items) {
                        hits.addAll(items);
                        done.countDown();
                    }
                }));

        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(ids(hits)).containsExactly(FakeLegacyBrowseService.CHILD_B);
    }

    @Test
    public void aSearchThatMatchesNothingReturnsEmptyRatherThanErroring() throws Exception {
        // A browse client that reads "no results" as an error shows a spinner forever, and it is
        // the case a Media3 equivalence must cover because the two models report emptiness
        // differently.
        List<MediaBrowserCompat.MediaItem> hits = new ArrayList<>();
        boolean[] errored = {false};
        CountDownLatch done = new CountDownLatch(1);

        getInstrumentation().runOnMainSync(() -> mBrowser.search("nothing-matches-this", null,
                new MediaBrowserCompat.SearchCallback() {
                    @Override
                    public void onSearchResult(String query, Bundle extras,
                            List<MediaBrowserCompat.MediaItem> items) {
                        hits.addAll(items);
                        done.countDown();
                    }

                    @Override
                    public void onError(String query, Bundle extras) {
                        errored[0] = true;
                        done.countDown();
                    }
                }));

        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errored[0]).isFalse();
        assertThat(hits).isEmpty();
    }

    private static List<String> ids(List<MediaBrowserCompat.MediaItem> items) {
        List<String> out = new ArrayList<>();
        for (MediaBrowserCompat.MediaItem item : items) {
            out.add(item.getMediaId());
        }
        return out;
    }
}
