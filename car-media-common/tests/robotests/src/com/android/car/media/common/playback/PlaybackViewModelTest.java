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
 */

package com.android.car.media.common.playback;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;
import static com.android.car.media.common.MediaTestUtils.newFakeMediaSource;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.getApplication;

import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.FlagSet;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionCommands;

import com.android.car.apps.common.testutils.CaptureObserver;
import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.testutils.TestLifecycleOwner;
import com.android.car.media.common.Media3Adapters;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;
import com.android.car.media.common.source.MediaSource;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Media3 port note — how the seam moved.
 *
 * <p>The legacy version fed the view model by capturing the {@code MediaControllerCompat.Callback}
 * it registered and calling that callback directly. That is still the shape here, but the callback
 * is a {@link Player.Listener} and the view model registers it on the controller Media3 already
 * gave it, so there is no {@code InputFactory} to stub: the {@link BrowsingState} carries the mock
 * controller and the view model takes it from there.
 *
 * <p>The one assertion form that could NOT be carried over is identity on a
 * {@code PlaybackStateCompat}. Media3 has no such object to hand in — the view model DERIVES the
 * legacy-shaped state from the player, which is the whole point of {@code Media3PlaybackState}. So
 * where the old tests asserted "the exact state object I supplied came back out", these assert "the
 * state the controller reports came back out, correctly translated". That is weaker on identity and
 * stronger on meaning: it would fail against an implementation that published a constant.
 */
@RunWith(RobolectricTestRunner.class)
public class PlaybackViewModelTest {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public final InstantTaskExecutorRule mTaskExecutorRule = new InstantTaskExecutorRule();
    @Rule
    public final TestLifecycleOwner mLifecycleOwner = new TestLifecycleOwner();

    private final MediaSource mMediaSource = newFakeMediaSource("test", "test");

    private MediaBrowser mMediaController;

    private PlaybackViewModel mPlaybackViewModel;

    private MutableLiveData<BrowsingState> mBrowsingStateLD;

    /**
     * A controller stubbed just enough to be read.
     *
     * <p>Media3's {@code Player} surface is wide and a bare mock answers null for the value types
     * the state conversion reads, so the few that are dereferenced unconditionally are given real
     * values here. Everything a test actually asserts on is stubbed by that test.
     */
    private static MediaBrowser newController() {
        MediaBrowser controller = mock(MediaBrowser.class);
        when(controller.isConnected()).thenReturn(true);
        when(controller.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
        when(controller.getMediaMetadata()).thenReturn(MediaMetadata.EMPTY);
        when(controller.getPlaylistMetadata()).thenReturn(MediaMetadata.EMPTY);
        when(controller.getCurrentTimeline()).thenReturn(Timeline.EMPTY);
        when(controller.getCustomLayout()).thenReturn(ImmutableList.of());
        // Non-null like the rest: media3 returns SessionCommands.EMPTY for "none", never null,
        // and toLegacyActions(MediaController) reads it to answer ACTION_SET_RATING.
        when(controller.getAvailableSessionCommands()).thenReturn(SessionCommands.EMPTY);
        return controller;
    }

    @Before
    public void setUp() {
        mMediaController = newController();
        mBrowsingStateLD = dataOf(
                new BrowsingState(mMediaSource, mMediaController, ConnectionStatus.CONNECTED));
        mPlaybackViewModel = new PlaybackViewModel(getApplication(), mBrowsingStateLD);
    }

    /** The listener the view model registered on the controller it was given. */
    private Player.Listener listener() {
        return mPlaybackViewModel.getPlayerListener();
    }

    @Test
    public void testGetMetadata() {
        MediaMetadata metadata = new MediaMetadata.Builder().setTitle("a title").build();
        CaptureObserver<MediaItemMetadata> observer = new CaptureObserver<>();
        mPlaybackViewModel.getMetadata().observe(mLifecycleOwner, observer);
        observer.reset();

        assertThat(listener()).isNotNull();
        listener().onMediaMetadataChanged(metadata);

        assertThat(observer.hasBeenNotified()).isTrue();
        MediaItemMetadata observedValue = observer.getObservedValue();
        assertThat(observedValue).isNotNull();
        assertThat(observedValue).isEqualTo(Media3Adapters.fromMedia3(null, metadata));
    }

    /**
     * Media3 reports "nothing playing" as MediaMetadata.EMPTY rather than as null. The view model
     * collapses that onto null so the rest of the code has one "nothing playing" case — the same
     * collapse the legacy version did against an empty MediaMetadata, and worth pinning because
     * dropping it would publish an all-null MediaItemMetadata instead of no metadata at all.
     */
    @Test
    public void testGetMetadata_emptyIsTreatedAsNone() {
        CaptureObserver<MediaItemMetadata> observer = new CaptureObserver<>();
        mPlaybackViewModel.getMetadata().observe(mLifecycleOwner, observer);
        listener().onMediaMetadataChanged(
                new MediaMetadata.Builder().setTitle("a title").build());
        observer.reset();

        listener().onMediaMetadataChanged(MediaMetadata.EMPTY);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNull();
        assertThat(mPlaybackViewModel.getMediaMetadata()).isNull();
    }

    /**
     * The legacy test asserted the wrapper carried back the exact PlaybackStateCompat it was fed.
     * There is no such object to feed any more; what is asserted instead is that the state the
     * CONTROLLER reports reaches the wrapper, through the READY-plus-playWhenReady fan-in that is
     * the single most likely thing to get wrong in this migration.
     */
    @Test
    public void testGetPlaybackState() {
        CaptureObserver<PlaybackViewModel.PlaybackStateWrapper> observer = new CaptureObserver<>();
        mPlaybackViewModel.getPlaybackStateWrapper().observe(mLifecycleOwner, observer);
        observer.reset();

        when(mMediaController.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mMediaController.getPlayWhenReady()).thenReturn(true);
        listener().onEvents(mMediaController, events(Player.EVENT_PLAYBACK_STATE_CHANGED));

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue().getStateCompat().getState())
                .isEqualTo(PlaybackStateCompat.STATE_PLAYING);
        assertThat(observer.getObservedValue().isPlaying()).isTrue();

        // ...and READY on its own is NOT playing. Without this second half the assertion above
        // would also pass against an implementation that ignored playWhenReady.
        observer.reset();
        when(mMediaController.getPlayWhenReady()).thenReturn(false);
        listener().onEvents(mMediaController, events(Player.EVENT_PLAY_WHEN_READY_CHANGED));

        assertThat(observer.getObservedValue().getStateCompat().getState())
                .isEqualTo(PlaybackStateCompat.STATE_PAUSED);
    }

    @Test
    public void testGetSanitizedQueue() {
        String title = "title";
        int queueId = 0;
        setQueue(mMediaController, queueItem(title));
        CaptureObserver<List<MediaItemMetadata>> observer = new CaptureObserver<>();
        mPlaybackViewModel.getQueue().observe(mLifecycleOwner, observer);
        observer.reset();

        listener().onTimelineChanged(
                Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);

        assertThat(observer.hasBeenNotified()).isTrue();
        List<MediaItemMetadata> observedValue = observer.getObservedValue();
        assertThat(observedValue).isNotNull();
        assertThat(observedValue).hasSize(1);
        MediaItemMetadata observedItem = observedValue.get(0);
        assertThat(observedItem).isNotNull();
        assertThat(observedItem.getTitle()).isEqualTo(title);
        assertThat(observedItem.getQueueId()).isEqualTo(queueId);
    }

    /** Items with no title at all are dropped, as they were on the legacy path. */
    @Test
    public void testGetSanitizedQueue_dropsUntitledItems() {
        setQueue(mMediaController, queueItem("kept"),
                new MediaItem.Builder().setMediaId("untitled").build());
        CaptureObserver<List<MediaItemMetadata>> observer = new CaptureObserver<>();
        mPlaybackViewModel.getQueue().observe(mLifecycleOwner, observer);
        observer.reset();

        listener().onTimelineChanged(
                Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);

        assertThat(observer.getObservedValue()).hasSize(1);
        assertThat(observer.getObservedValue().get(0).getTitle()).isEqualTo("kept");
    }

    /**
     * The legacy counterpart was {@code onQueueChanged(null)}. Media3 has no null queue — a session
     * that stops having one reports zero items — but the case the legacy test was really covering
     * is the controller going away, which is what this drives: a SUSPENDED connection, after which
     * there is no queue at all.
     */
    @Test
    public void testGetHasQueue_null() {
        setQueue(mMediaController, queueItem("title1"), queueItem("title2"));
        CaptureObserver<Boolean> observer = new CaptureObserver<>();
        mPlaybackViewModel.hasQueue().observe(mLifecycleOwner, observer);
        listener().onTimelineChanged(
                Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
        assertThat(observer.getObservedValue()).isTrue();
        observer.reset();

        mBrowsingStateLD.setValue(
                new BrowsingState(mMediaSource, mMediaController, ConnectionStatus.SUSPENDED));

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isEqualTo(false);
    }

    @Test
    public void testGetHasQueue_empty() {
        setQueue(mMediaController);
        CaptureObserver<Boolean> observer = new CaptureObserver<>();
        mPlaybackViewModel.hasQueue().observe(mLifecycleOwner, observer);
        observer.reset();

        listener().onTimelineChanged(
                Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isEqualTo(false);
    }

    @Test
    public void testGetHasQueue_true() {
        setQueue(mMediaController, queueItem("title1"), queueItem("title2"));
        CaptureObserver<Boolean> observer = new CaptureObserver<>();
        mPlaybackViewModel.hasQueue().observe(mLifecycleOwner, observer);
        observer.reset();

        listener().onTimelineChanged(
                Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isEqualTo(true);
    }

    /**
     * Values published while a source is switching must belong to the controller they came from.
     *
     * <p>Same assertion as the legacy test, restated in Media3's terms: the metadata identity check
     * is against the Media3 MediaMetadata, and the state check is against the state each controller
     * reports rather than against a supplied state object.
     */
    @Test
    public void testChangeMediaSource_consistentController() {
        MediaMetadata metadata = new MediaMetadata.Builder().setTitle("first").build();
        when(mMediaController.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mMediaController.getPlayWhenReady()).thenReturn(true);
        listener().onMediaMetadataChanged(metadata);

        MediaBrowser newController = newController();
        when(newController.getPlaybackState()).thenReturn(Player.STATE_BUFFERING);
        MediaMetadata newMetadata = new MediaMetadata.Builder().setTitle("second").build();

        // Ensure that all values are coming from the correct MediaController.
        mPlaybackViewModel.getMetadata().observe(mLifecycleOwner, mediaItemMetadata -> {
            if (mPlaybackViewModel.getMediaMetadata() == newMetadata) {
                assertThat(mPlaybackViewModel.getMediaController()).isSameInstanceAs(newController);
            }
            if (mPlaybackViewModel.getMediaMetadata() == metadata) {
                assertThat(mPlaybackViewModel.getMediaController())
                        .isSameInstanceAs(mMediaController);
            }
        });

        mPlaybackViewModel.getPlaybackStateWrapper().observe(mLifecycleOwner, state -> {
            if (state == null) return;

            if (state.getStateCompat().getState() == PlaybackStateCompat.STATE_BUFFERING) {
                assertThat(mPlaybackViewModel.getMediaController()).isSameInstanceAs(newController);
            }
            if (state.getStateCompat().getState() == PlaybackStateCompat.STATE_PLAYING) {
                assertThat(mPlaybackViewModel.getMediaController())
                        .isSameInstanceAs(mMediaController);
            }
        });

        mBrowsingStateLD.setValue(
                new BrowsingState(mMediaSource, newController, ConnectionStatus.CONNECTED));

        assertThat(mPlaybackViewModel.getMediaController()).isSameInstanceAs(newController);
        mPlaybackViewModel.getPlayerListener().onMediaMetadataChanged(newMetadata);
        assertThat(mPlaybackViewModel.getMediaMetadata()).isSameInstanceAs(newMetadata);
    }

    private static Player.Events events(int... flags) {
        FlagSet.Builder builder = new FlagSet.Builder();
        for (int flag : flags) {
            builder.add(flag);
        }
        return new Player.Events(builder.build());
    }

    private static void setQueue(@NonNull MediaBrowser controller, MediaItem... items) {
        when(controller.getMediaItemCount()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            when(controller.getMediaItemAt(i)).thenReturn(items[i]);
        }
    }

    @NonNull
    private static MediaItem queueItem(String title) {
        return new MediaItem.Builder()
                .setMediaId(title)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build())
                .build();
    }
}
