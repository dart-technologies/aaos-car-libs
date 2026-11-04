/*
 * Copyright 2018 The Android Open Source Project
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
 *
 * Modified by Dart Technologies, 2026; see FORK.md.
 */

package com.android.car.media.common.source;

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static com.android.car.apps.common.util.CarAppsDebugUtils.idHash;
import static com.android.car.media.common.MediaTestUtils.newFakeMediaSource;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.getApplication;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.car.Car;
import android.car.media.CarMediaManager;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.session.MediaBrowser;

import com.android.car.apps.common.testutils.CaptureObserver;
import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.testutils.TestLifecycleOwner;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowPackageManager;

import com.google.common.util.concurrent.ListenableFuture;


@RunWith(RobolectricTestRunner.class)
public class MediaSourceViewModelTest {

    private static final String TAG = "MediaSourceVMTest";

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public final InstantTaskExecutorRule mTaskExecutorRule = new InstantTaskExecutorRule();
    @Rule
    public final TestLifecycleOwner mLifecycleOwner = new TestLifecycleOwner();

    @Mock
    public MediaBrowser mMediaBrowser;

    @Mock
    public Car mCar;
    @Mock
    public CarMediaManager mCarMediaManager;

    private MediaSourceViewModel mViewModel;

    private MediaSource mRequestedSource;
    private MediaSource mMediaSource;

    @Before
    public void setUp() {
        mRequestedSource = null;
        mMediaSource = null;
        // Media3 resolves a SessionToken through the PackageManager and refuses a component that
        // does not declare a media browse service. The legacy MediaBrowserCompat constructor did no
        // such lookup, so this registration is new — and it is what keeps this test exercising the
        // REAL createMediaBrowser rather than silently falling into the connector's failure branch.
        registerBrowseService(TEST_PACKAGE, TEST_CLASS);
    }

    private static final String TEST_PACKAGE = "test";
    private static final String TEST_CLASS = "test";

    private static void registerBrowseService(String pkg, String cls) {
        ComponentName cn = new ComponentName(pkg, cls);
        ShadowPackageManager spm = shadowOf(getApplication().getPackageManager());
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = pkg;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = pkg;
        packageInfo.applicationInfo.name = pkg;
        spm.installPackage(packageInfo);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = pkg;
        serviceInfo.name = cls;
        serviceInfo.applicationInfo = packageInfo.applicationInfo;
        spm.addOrUpdateService(serviceInfo);
        spm.addServiceIfNotPresent(cn);
        spm.addIntentFilterForService(cn,
                new IntentFilter("android.media.browse.MediaBrowserService"));
    }

    private void initializeViewModel() {
        mViewModel = new MediaSourceViewModel(getApplication(), MEDIA_SOURCE_MODE_PLAYBACK,
                new MediaSourceViewModel.InputFactory() {
            @Override
            public MediaBrowserConnector createMediaBrowserConnector(
                    @NonNull Application application,
                    @NonNull MediaBrowserConnector.Callback connectedBrowserCallback) {
                return new MediaBrowserConnector(application, connectedBrowserCallback) {
                    @Override
                    @NonNull
                    protected ListenableFuture<MediaBrowser> createMediaBrowser(
                            @NonNull MediaSource mediaSource,
                            @NonNull MediaBrowser.Listener listener) {
                        mRequestedSource = mediaSource;
                        ListenableFuture<MediaBrowser> bro =
                                super.createMediaBrowser(mediaSource, listener);
                        Log.i(TAG, "createMediaBrowser: " + idHash(bro) + " for: " + mediaSource);
                        return bro;
                    }
                };
            }

            @Override
            public Car getCarApi() {
                return mCar;
            }

            @Override
            public CarMediaManager getCarMediaManager(Car carApi) {
                return mCarMediaManager;
            }

            @Override
            public MediaSource getMediaSource(ComponentName componentName) {
                return mMediaSource;
            }
        });
    }

    @Test
    public void testGetSelectedMediaSource_none() {
        initializeViewModel();
        CaptureObserver<MediaSource> observer = new CaptureObserver<>();

        mViewModel.getPrimaryMediaSource().observe(mLifecycleOwner, observer);

        assertThat(observer.getObservedValue()).isNull();
    }

    /**
     * The registration in {@link #setUp} is doing work, and this is what says so.
     *
     * <p>Without it Media3's SessionToken constructor throws, the connector catches it and reports
     * SUSPENDED, and every other assertion in this class still passes — {@code mRequestedSource} is
     * recorded before the real call is made. That is exactly the shape of a test that has quietly
     * stopped exercising the code it names, so the outcome of the real {@code createMediaBrowser}
     * is asserted directly.
     */
    @Test
    public void testRealBrowserCreationResolvesTheSessionToken() {
        CaptureObserver<BrowsingState> observer = new CaptureObserver<>();
        mMediaSource = newFakeMediaSource(TEST_PACKAGE, TEST_CLASS);
        initializeViewModel();

        mViewModel.getBrowsingState().observe(mLifecycleOwner, observer);

        assertThat(mRequestedSource).isEqualTo(mMediaSource);
        BrowsingState browsingState = observer.getObservedValue();
        assertThat(browsingState).isNotNull();
        assertThat(browsingState.mConnectionStatus).isEqualTo(ConnectionStatus.CONNECTING);
    }

    @Test
    public void testGetMediaController_connectedBrowser() {
        CaptureObserver<BrowsingState> observer = new CaptureObserver<>();
        mMediaSource = newFakeMediaSource(TEST_PACKAGE, TEST_CLASS);
        when(mMediaBrowser.isConnected()).thenReturn(true);
        initializeViewModel();

        mViewModel.getBrowserCallback().onBrowserConnectionChanged(
                new BrowsingState(mMediaSource, mMediaBrowser, ConnectionStatus.CONNECTED));
        mViewModel.getBrowsingState().observe(mLifecycleOwner, observer);

        BrowsingState browsingState = observer.getObservedValue();
        assertThat(browsingState.mBrowser).isSameInstanceAs(mMediaBrowser);
        assertThat(browsingState.mConnectionStatus).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(mRequestedSource).isEqualTo(mMediaSource);
    }

    @Test
    public void testGetMediaController_noActiveSession_notConnected() {
        CaptureObserver<BrowsingState> observer = new CaptureObserver<>();
        mMediaSource = newFakeMediaSource(TEST_PACKAGE, TEST_CLASS);
        when(mMediaBrowser.isConnected()).thenReturn(false);
        initializeViewModel();

        mViewModel.getBrowserCallback().onBrowserConnectionChanged(
                new BrowsingState(mMediaSource, mMediaBrowser, ConnectionStatus.REJECTED));
        mViewModel.getBrowsingState().observe(mLifecycleOwner, observer);

        BrowsingState browsingState = observer.getObservedValue();
        assertThat(browsingState.mBrowser).isSameInstanceAs(mMediaBrowser);
        assertThat(browsingState.mConnectionStatus).isEqualTo(ConnectionStatus.REJECTED);
        assertThat(mRequestedSource).isEqualTo(mMediaSource);
    }
}
