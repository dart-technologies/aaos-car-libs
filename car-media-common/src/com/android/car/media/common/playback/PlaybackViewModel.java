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

package com.android.car.media.common.playback;

import static androidx.lifecycle.Transformations.switchMap;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;
import static com.android.car.media.common.playback.PlaybackStateAnnotations.Actions;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.HeartRating;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Rating;
import androidx.media3.common.Timeline;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;

import com.android.car.media.common.CustomPlaybackAction;
import com.android.car.media.common.Media3Adapters;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.R;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;
import com.android.car.media.common.source.MediaSourceColors;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ViewModel for media playback.
 * <p>
 * Observes changes to the provided MediaController to expose playback state and metadata
 * observables.
 * <p>
 * PlaybackViewModel is a "singleton" tied to the application to provide a single source of truth.
 */
public class PlaybackViewModel extends AndroidViewModel {
    private static final String TAG = "PlaybackViewModel";

    private static final String ACTION_SET_RATING =
            "com.android.car.media.common.ACTION_SET_RATING";
    private static final String EXTRA_SET_HEART = "com.android.car.media.common.EXTRA_SET_HEART";

    private static PlaybackViewModel[] sInstances = new PlaybackViewModel[2];

    /** Returns the PlaybackViewModel "singleton" tied to the application for the given mode. */
    public static PlaybackViewModel get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new PlaybackViewModel(application, mode);
        }
        return sInstances[mode];
    }

    /**
     * Possible main actions.
     */
    @IntDef({ACTION_PLAY, ACTION_STOP, ACTION_PAUSE, ACTION_DISABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    /**
     * Main action is disabled. The source can't play media at this time
     */
    public static final int ACTION_DISABLED = 0;
    /**
     * Start playing
     */
    public static final int ACTION_PLAY = 1;
    /**
     * Stop playing
     */
    public static final int ACTION_STOP = 2;
    /**
     * Pause playing
     */
    public static final int ACTION_PAUSE = 3;

    /*
     * The InputFactory that used to live here is gone, and its disappearance is the point of this
     * stage rather than a tidy-up. It existed for exactly one reason: to turn the browser's session
     * token into a second client object, `token -> new MediaControllerCompat(app, token)`. In
     * Media3 `MediaBrowser extends MediaController`, so the connected browser IS the controller —
     * there is no second object to build and therefore no factory to swap. Tests now hand the view
     * model a BrowsingState carrying a mock controller, which is a shorter path to the same seam.
     */

    /** Media3 reports "no metadata" as this value rather than as null. */
    private static final MediaMetadata EMPTY_MEDIA_METADATA = MediaMetadata.EMPTY;

    private final MediaControllerCallback mMediaControllerCallback = new MediaControllerCallback();
    private final Observer<MediaBrowserConnector.BrowsingState> mMediaBrowsingObserver =
            mMediaControllerCallback::onMediaBrowsingStateChanged;

    private final MediaSourceColors.Factory mColorsFactory;
    private final MutableLiveData<MediaSourceColors> mColors = dataOf(null);

    private final MutableLiveData<MediaItemMetadata> mMetadata = dataOf(null);

    // Filters out queue items with no description or title and converts them to MediaItemMetadata
    private final MutableLiveData<List<MediaItemMetadata>> mSanitizedQueue = dataOf(null);

    private final MutableLiveData<Boolean> mHasQueue = dataOf(null);

    private final MutableLiveData<CharSequence> mQueueTitle = dataOf(null);

    private final MutableLiveData<PlaybackController> mPlaybackControls = dataOf(null);

    private final MutableLiveData<PlaybackStateWrapper> mPlaybackStateWrapper = dataOf(null);

    private final LiveData<PlaybackProgress> mProgress =
            switchMap(mPlaybackStateWrapper,
                    state -> state == null ? dataOf(new PlaybackProgress(0L, 0L))
                            : new ProgressLiveData(
                                    PlaybackTiming.from(state.mState),
                                    state.getMaxProgress()));

    private PlaybackViewModel(Application application, int mode) {
        this(application, MediaSourceViewModel.get(application, mode).getBrowsingState());
    }

    @VisibleForTesting
    public PlaybackViewModel(Application application,
            LiveData<MediaBrowserConnector.BrowsingState> browsingState) {
        super(application);
        mColorsFactory = new MediaSourceColors.Factory(application);
        browsingState.observeForever(mMediaBrowsingObserver);
    }

    /**
     * Returns a LiveData that emits the colors for the currently set media source.
     */
    public LiveData<MediaSourceColors> getMediaSourceColors() {
        return mColors;
    }

    /**
     * Returns a LiveData that emits a MediaItemMetadata of the current media item in the session
     * managed by the provided {@link MediaController}.
     */
    public LiveData<MediaItemMetadata> getMetadata() {
        return mMetadata;
    }

    /**
     * Returns a LiveData that emits the current queue as MediaItemMetadatas where items without a
     * title have been filtered out.
     */
    public LiveData<List<MediaItemMetadata>> getQueue() {
        return mSanitizedQueue;
    }

    /**
     * Returns a LiveData that emits whether the MediaController has a non-empty queue
     */
    public LiveData<Boolean> hasQueue() {
        return mHasQueue;
    }

    /**
     * Returns a LiveData that emits the current queue title.
     */
    public LiveData<CharSequence> getQueueTitle() {
        return mQueueTitle;
    }

    /**
     * Returns a LiveData that emits an object for controlling the currently selected
     * MediaController.
     */
    public LiveData<PlaybackController> getPlaybackController() {
        return mPlaybackControls;
    }

    /** Returns a {@PlaybackStateWrapper} live data. */
    public LiveData<PlaybackStateWrapper> getPlaybackStateWrapper() {
        return mPlaybackStateWrapper;
    }

    /**
     * Returns a LiveData that emits the current playback progress, in milliseconds. This is a
     * value between 0 and {@link #getPlaybackStateWrapper#getMaxProgress()} or
     * {@link PlaybackStateCompat#PLAYBACK_POSITION_UNKNOWN} if the current position is unknown.
     * This value will update on its own periodically (less than a second) while active.
     */
    public LiveData<PlaybackProgress> getProgress() {
        return mProgress;
    }

    @VisibleForTesting
    public MediaController getMediaController() {
        return mMediaControllerCallback.mMediaController;
    }

    @VisibleForTesting
    public MediaMetadata getMediaMetadata() {
        return mMediaControllerCallback.mMediaMetadata;
    }

    /** Lets tests drive the view model the way Media3 drives it: through Player callbacks. */
    @VisibleForTesting
    public Player.Listener getPlayerListener() {
        return mMediaControllerCallback;
    }


    /**
     * Bridges Media3's {@link Player.Listener} onto the shape this view model publishes.
     *
     * <h3>What replaced what</h3>
     * <ul>
     *   <li>{@code onMetadataChanged} → {@link #onMediaMetadataChanged}
     *   <li>{@code onQueueChanged} → {@link #onTimelineChanged} (Media3's queue IS the timeline)
     *   <li>{@code onQueueTitleChanged} → {@link #onPlaylistMetadataChanged}
     *   <li>{@code onPlaybackStateChanged} → {@link #onEvents}, because Media3 splits one legacy
     *       state integer across several callbacks (state, playWhenReady, error, available
     *       commands, position) and {@code onEvents} is the one place they are known to be
     *       consistent with each other.
     *   <li>{@code onSessionDestroyed} → the connector's {@code SUSPENDED} state, which it derives
     *       from {@link androidx.media3.session.MediaController.Listener#onDisconnected}. The
     *       connector owns that listener because Media3 requires it at build time, so the death of
     *       a session reaches this class the same way every other connection change does — through
     *       {@link #onMediaBrowsingStateChanged}.
     * </ul>
     */
    private class MediaControllerCallback implements Player.Listener {

        private MediaBrowserConnector.BrowsingState mBrowsingState;
        private MediaController mMediaController;
        private MediaMetadata mMediaMetadata;

        void onMediaBrowsingStateChanged(MediaBrowserConnector.BrowsingState newBrowsingState) {
            if (Objects.equals(mBrowsingState, newBrowsingState)) {
                Log.w(TAG, "onMediaBrowsingStateChanged noop ");
                return;
            }

            // Reset the old controller if any, unregistering the callback when browsing is
            // not suspended (crashed).
            if (mMediaController != null) {
                switch (newBrowsingState.mConnectionStatus) {
                    case DISCONNECTING:
                    case REJECTED:
                    case CONNECTING:
                    case CONNECTED:
                        mMediaController.removeListener(this);
                        // Fall through
                    case SUSPENDED:
                        setMediaController(null);
                }
            }

            mBrowsingState = newBrowsingState;

            if (mBrowsingState.mConnectionStatus == ConnectionStatus.CONNECTED) {
                setMediaController(mBrowsingState.getController());
            }
        }

        private void setMediaController(@Nullable MediaController mediaController) {
            mMediaMetadata = null;
            mMediaController = mediaController;
            mPlaybackControls.setValue(new PlaybackController(mediaController));

            if (mMediaController != null) {
                mMediaController.addListener(this);

                mColors.setValue(mColorsFactory.extractColors(getPackageName(mMediaController)));

                // The apps don't always send updates so make sure we fetch the most recent values.
                onMediaMetadataChanged(mMediaController.getMediaMetadata());
                onTimelineChanged(mMediaController.getCurrentTimeline(),
                        Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
                onPlaylistMetadataChanged(mMediaController.getPlaylistMetadata());
            } else {
                mColors.setValue(null);
                onMediaMetadataChanged(EMPTY_MEDIA_METADATA);
                setQueue(Collections.emptyList());
                mQueueTitle.setValue(null);
            }

            updatePlaybackStatus();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            // Media3 never reports null: an item with nothing set arrives as MediaMetadata.EMPTY.
            // The legacy code went to some trouble to collapse "empty" onto null so the rest of
            // the file only had one "nothing playing" case; that collapse is preserved here rather
            // than pushed onto every reader.
            mMediaMetadata = EMPTY_MEDIA_METADATA.equals(mediaMetadata) ? null : mediaMetadata;
            MediaItemMetadata item = (mMediaMetadata != null)
                    ? Media3Adapters.fromMedia3(currentMediaId(), mMediaMetadata) : null;
            mMetadata.setValue(item);
            updatePlaybackStatus();
        }

        @Nullable
        private String currentMediaId() {
            if (mMediaController == null) return null;
            MediaItem item = mMediaController.getCurrentMediaItem();
            return (item != null) ? item.mediaId : null;
        }

        @Override
        public void onPlaylistMetadataChanged(@NonNull MediaMetadata playlistMetadata) {
            mQueueTitle.setValue(playlistMetadata.title);
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            if (mMediaController == null) {
                setQueue(Collections.emptyList());
                return;
            }
            int count = mMediaController.getMediaItemCount();
            List<MediaItemMetadata> filtered = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MediaItem item = mMediaController.getMediaItemAt(i);
                if (item == null) continue;
                MediaMetadata md = item.mediaMetadata;
                // The legacy filter was "has a description AND that description has a title".
                // Media3 always has a MediaMetadata, so only the title half of the test survives —
                // and it is the half that was doing the work, since a description without a title
                // renders as a blank row either way.
                if (md.displayTitle == null && md.title == null) continue;
                filtered.add(Media3Adapters.fromMedia3QueueItem(item, i));
            }
            setQueue(filtered);
        }

        private void setQueue(@NonNull List<MediaItemMetadata> filtered) {
            mSanitizedQueue.setValue(filtered);
            mHasQueue.setValue(filtered.size() > 1);
        }

        /**
         * Recomputes the published playback state after any batch of player changes.
         *
         * <p>Every legacy {@code onPlaybackStateChanged} carried a whole {@code PlaybackStateCompat}
         * — state, actions, position, error, all consistent with one another. Media3 delivers those
         * as separate callbacks, and {@code onEvents} is the point at which the player is known to
         * be settled, so it is the honest counterpart to a single state delivery.
         */
        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            updatePlaybackStatus();
        }

        private void updatePlaybackStatus() {
            if (mMediaController != null) {
                mPlaybackStateWrapper.setValue(
                        new PlaybackStateWrapper(mMediaController, mMediaMetadata,
                                Media3PlaybackState.toPlaybackState(mMediaController)));
            } else {
                mPlaybackStateWrapper.setValue(null);
            }
        }
    }

    /**
     * The package the session lives in.
     *
     * <p>{@code MediaControllerCompat#getPackageName} had no Media3 equivalent on the controller
     * itself; the connected {@link androidx.media3.session.SessionToken} carries it instead.
     */
    @Nullable
    private static String getPackageName(@NonNull MediaController controller) {
        return controller.getConnectedToken() != null
                ? controller.getConnectedToken().getPackageName() : null;
    }

    /**
     * Convenient extension of {@link PlaybackStateCompat}.
     *
     * <h3>Legacy-SHAPED on purpose</h3>
     *
     * <p>Everything this class exposes still speaks the legacy vocabulary: a
     * {@code @PlaybackStateCompat.State int}, a {@code long} action bitmask, an error code from the
     * same table. That is deliberate and is the boundary of this migration. Removing the deprecated
     * CLIENT (which is what changed here — the fields below are now Media3 types) and reshaping this
     * PUBLIC CONTRACT are separable jobs, and only the first is safe to do while the tests written
     * against the contract are the safety net. See {@code Media3PlaybackState}, which does the
     * state and action conversion and carries the same note.
     *
     * <h3>Two things Media3 cannot answer the way the legacy client did</h3>
     *
     * <ul>
     *   <li><b>Error codes.</b> {@code Media3PlaybackState} reports
     *       {@code ERROR_CODE_UNKNOWN_ERROR} rather than inventing a translation from Media3's own
     *       error space back onto the media-service wire codes in {@code MediaErrorCodes}. The error
     *       MESSAGE survives; the code does not.
     *   <li><b>State extras.</b> {@link #getExtras} used to return the extras attached to the
     *       {@code PlaybackStateCompat} — where a media app puts an error-resolution PendingIntent.
     *       Media3's controller does not surface those; the nearest thing it has is the SESSION
     *       extras, which is what this now returns. An app that only ever put its resolution intent
     *       on the playback state will no longer offer a resolution button.
     * </ul>
     */
    public static final class PlaybackStateWrapper {

        private final MediaController mMediaController;
        @Nullable
        private final MediaMetadata mMetadata;
        private final PlaybackStateCompat mState;

        PlaybackStateWrapper(@NonNull MediaController mediaController,
                @Nullable MediaMetadata metadata, @NonNull PlaybackStateCompat state) {
            mMediaController = mediaController;
            mMetadata = metadata;
            mState = state;
        }

        /** Returns true if there's enough information in the state to show a UI for it. */
        public boolean shouldDisplay() {
            // STATE_NONE means no content to play.
            return mState.getState() != PlaybackStateCompat.STATE_NONE && ((mMetadata != null) || (
                    getMainAction() != ACTION_DISABLED));
        }

        /** Returns the main action. */
        @Action
        public int getMainAction() {
            @Actions long actions = mState.getActions();
            @Action int stopAction = ACTION_DISABLED;
            if ((actions & (PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0) {
                stopAction = ACTION_PAUSE;
            } else if ((actions & PlaybackStateCompat.ACTION_STOP) != 0) {
                stopAction = ACTION_STOP;
            }

            switch (mState.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                case PlaybackStateCompat.STATE_REWINDING:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    return stopAction;
                case PlaybackStateCompat.STATE_STOPPED:
                case PlaybackStateCompat.STATE_PAUSED:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_ERROR:
                    return (actions & PlaybackStateCompat.ACTION_PLAY) != 0 ? ACTION_PLAY
                            : ACTION_DISABLED;
                default:
                    Log.w(TAG, String.format("Unknown PlaybackState: %d", mState.getState()));
                    return ACTION_DISABLED;
            }
        }

        /**
         * Returns the currently supported playback actions
         */
        public long getSupportedActions() {
            return mState.getActions();
        }

        /**
         * Returns the duration of the media item in milliseconds. The current position in this
         * duration can be obtained by calling {@link #getProgress()}.
         */
        public long getMaxProgress() {
            // Read from the metadata, not from Player#getDuration, because the legacy source was
            // METADATA_KEY_DURATION and that is what Media3's durationMs is converted from. Taking
            // the player's duration instead would start reporting a length for sessions that
            // deliberately publish none.
            return (mMetadata == null || mMetadata.durationMs == null) ? 0 : mMetadata.durationMs;
        }

        /** Returns whether the current media source is playing a media item. */
        public boolean isPlaying() {
            return mState.getState() == PlaybackStateCompat.STATE_PLAYING;
        }

        /** Returns whether the media source supports skipping to the next item. */
        public boolean isSkipNextEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0;
        }

        /** Returns whether the media source supports skipping to the previous item. */
        public boolean isSkipPreviousEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0;
        }

        /**
         * Returns whether the media source supports seeking to a new location in the media stream.
         */
        public boolean isSeekToEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SEEK_TO) != 0;
        }

        /** Returns whether the media source requires reserved space for the skip to next action. */
        public boolean isSkipNextReserved() {
            Bundle extras = mMediaController.getSessionExtras();
            return extras != null
                    && (extras.getBoolean(MediaConstants.SLOT_RESERVATION_SKIP_TO_NEXT)
                    || extras.getBoolean(MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_NEXT));
        }

        /**
         * Returns whether the media source requires reserved space for the skip to previous action.
         */
        public boolean iSkipPreviousReserved() {
            Bundle extras = mMediaController.getSessionExtras();
            return extras != null
                    && (extras.getBoolean(MediaConstants.SLOT_RESERVATION_SKIP_TO_PREV)
                    || extras.getBoolean(MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_PREV));
        }

        /** Returns whether the media source is loading (e.g.: buffering, connecting, etc.). */
        public boolean isLoading() {
            int state = mState.getState();
            return state == PlaybackStateCompat.STATE_BUFFERING
                    || state == PlaybackStateCompat.STATE_CONNECTING
                    || state == PlaybackStateCompat.STATE_FAST_FORWARDING
                    || state == PlaybackStateCompat.STATE_REWINDING
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM;
        }

        /** See {@link PlaybackStateCompat#getErrorMessage}. */
        public CharSequence getErrorMessage() {
            return mState.getErrorMessage();
        }

        /** See {@link PlaybackStateCompat#getErrorCode()}. */
        public int getErrorCode() {
            return mState.getErrorCode();
        }

        /**
         * The identifier of the queue item currently playing.
         *
         * <p>Media3 identifies a queue entry by its index in the timeline, so that index is the id
         * — the same one {@code getQueue()} publishes, so the pair still matches. An empty queue
         * reports {@code -1} rather than Media3's index 0, because a queue with nothing in it has
         * no active item and index 0 would name a row that does not exist.
         */
        public long getActiveQueueItemId() {
            return mMediaController.getMediaItemCount() == 0
                    ? -1 : mMediaController.getCurrentMediaItemIndex();
        }

        /** See {@link PlaybackStateCompat#getState}. */
        @PlaybackStateCompat.State
        public int getState() {
            return mState.getState();
        }

        /** The session extras. See the class note for why these are not the state extras. */
        public Bundle getExtras() {
            return mMediaController.getSessionExtras();
        }

        @VisibleForTesting
        public PlaybackStateCompat getStateCompat() {
            return mState;
        }

        /**
         * Returns a sorted list of custom actions available. Call {@link
         * RawCustomPlaybackAction#fetchDrawable(Context)} to get the appropriate icon Drawable.
         */
        public List<RawCustomPlaybackAction> getCustomActions() {
            List<RawCustomPlaybackAction> actions = new ArrayList<>();
            RawCustomPlaybackAction ratingAction = getRatingAction();
            if (ratingAction != null) actions.add(ratingAction);

            String packageName = getPackageName(mMediaController);
            for (CommandButton button : mMediaController.getCustomLayout()) {
                // A CommandButton can stand for a PLAYER command instead of a custom session
                // command; those have no action string and are already offered by the standard
                // controls, so they are not custom actions.
                if (button.sessionCommand == null
                        || button.sessionCommand.commandCode != SessionCommand.COMMAND_CODE_CUSTOM) {
                    continue;
                }
                actions.add(new RawCustomPlaybackAction(button.iconResId, packageName,
                        button.sessionCommand.customAction, button.extras));
            }
            return actions;
        }

        /**
         * The heart button, when the session both accepts ratings and rates with hearts.
         *
         * <p>Media3 has no {@code getRatingType()}: a session declares it can be rated by making
         * the SET_RATING session command available, and declares WHICH rating by the concrete
         * {@link Rating} subclass it publishes in its metadata. So the two questions the legacy code
         * asked of two different objects are asked here of the command set and the metadata.
         */
        @Nullable
        private RawCustomPlaybackAction getRatingAction() {
            if (!mMediaController.isSessionCommandAvailable(
                    SessionCommand.COMMAND_CODE_SESSION_SET_RATING)) {
                return null;
            }

            Rating rating = (mMetadata != null) ? mMetadata.userRating : null;
            if (!(rating instanceof HeartRating)) return null;

            boolean hasHeart = ((HeartRating) rating).isHeart();

            int iconResource = hasHeart ? R.drawable.ic_star_filled : R.drawable.ic_star_empty;
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_SET_HEART, !hasHeart);
            return new RawCustomPlaybackAction(iconResource, null, ACTION_SET_RATING, extras);
        }
    }


    /**
     * Sends transport commands to the connected session.
     *
     * <h3>Media3 has no TransportControls object</h3>
     *
     * <p>{@link MediaController} implements {@link Player}, so the commands are methods on the
     * controller itself. Three of them are not one-for-one renames and are worth naming:
     *
     * <ul>
     *   <li>{@link #playItem} was {@code playFromMediaId}. Media3 expresses it as "set this item,
     *       prepare, play"; its legacy bridge turns exactly that sequence back into
     *       {@code playFromMediaId}, which is why all three calls are made and why they are made in
     *       that order.
     *   <li>{@link #skipToQueueItem} takes an index into the timeline — see
     *       {@code PlaybackStateWrapper#getActiveQueueItemId} for why that is now the queue id.
     *   <li>{@link #seekTo} still republishes the state immediately. The legacy version synthesised
     *       a PlaybackStateCompat to make the progress bar move before the app answered; Media3's
     *       controller already masks the new position locally, so re-reading it achieves the same
     *       thing without fabricating a state.
     * </ul>
     */
    // TODO(arnaudberry) does this wrapping make sense since we're still null checking the wrapper?
    // Should we call action methods on the model class instead ?
    public class PlaybackController {
        private final MediaController mMediaController;

        private PlaybackController(@Nullable MediaController mediaController) {
            mMediaController = mediaController;
        }

        /**
         * Sends a 'play' command to the media source
         */
        public void play() {
            if (mMediaController != null) {
                mMediaController.play();
            }
        }

        /**
         * Sends a 'skip previews' command to the media source
         */
        public void skipToPrevious() {
            if (mMediaController != null) {
                mMediaController.seekToPreviousMediaItem();
            }
        }

        /**
         * Sends a 'skip next' command to the media source
         */
        public void skipToNext() {
            if (mMediaController != null) {
                mMediaController.seekToNextMediaItem();
            }
        }

        /**
         * Sends a 'pause' command to the media source
         */
        public void pause() {
            if (mMediaController != null) {
                mMediaController.pause();
            }
        }

        /**
         * Sends a 'stop' command to the media source
         */
        public void stop() {
            if (mMediaController != null) {
                mMediaController.stop();
            }
        }

        /**
         * Moves to a new location in the media stream
         *
         * @param pos Position to move to, in milliseconds.
         */
        public void seekTo(long pos) {
            if (mMediaController != null) {
                mMediaController.seekTo(pos);
                mMediaControllerCallback.updatePlaybackStatus();
            }
        }

        /**
         * Sends a custom action to the media source
         *
         * @param action identifier of the custom action
         * @param extras additional data to send to the media source.
         */
        public void doCustomAction(String action, Bundle extras) {
            if (mMediaController == null) return;

            if (ACTION_SET_RATING.equals(action)) {
                boolean setHeart = extras != null && extras.getBoolean(EXTRA_SET_HEART, false);
                mMediaController.setRating(new HeartRating(setHeart));
            } else {
                Bundle commandExtras = (extras != null) ? extras : Bundle.EMPTY;
                mMediaController.sendCustomCommand(
                        new SessionCommand(action, commandExtras), commandExtras);
            }
        }

        /**
         * Starts playing a given media item.
         */
        public void playItem(MediaItemMetadata item) {
            if (mMediaController != null) {
                // Do NOT pass the extras back as that's not the official API and isn't supported
                // in media2, so apps should not rely on this.
                mMediaController.setMediaItem(
                        new MediaItem.Builder().setMediaId(item.getId()).build());
                mMediaController.prepare();
                mMediaController.play();
            }
        }

        /**
         * Skips to a particular item in the media queue. This id is {@link
         * MediaItemMetadata#mQueueId} of the items obtained through {@link
         * PlaybackViewModel#getQueue()}.
         */
        public void skipToQueueItem(long queueId) {
            if (mMediaController != null) {
                mMediaController.seekToDefaultPosition((int) queueId);
            }
        }

        /**
         * Prepares the current media source for playback.
         */
        public void prepare() {
            if (mMediaController != null) {
                mMediaController.prepare();
            }
        }
    }

    /**
     * Abstract representation of a custom playback action. A custom playback action represents a
     * visual element that can be used to trigger playback actions not included in the standard
     * {@link PlaybackController} class. Custom actions for the current media source are exposed
     * through {@link PlaybackStateWrapper#getCustomActions}
     * <p>
     * Does not contain a {@link Drawable} representation of the icon. Instances of this object
     * should be converted to a {@link CustomPlaybackAction} via {@link
     * RawCustomPlaybackAction#fetchDrawable(Context)} for display.
     */
    public static class RawCustomPlaybackAction {
        // TODO (keyboardr): This class (and associtated translation code) will be merged with
        // CustomPlaybackAction in a future CL.
        /**
         * Icon to display for this custom action
         */
        public final int mIcon;
        /**
         * If true, use the resources from the this package to resolve the icon. If null use our own
         * resources.
         */
        @Nullable
        public final String mPackageName;
        /**
         * Action identifier used to request this action to the media service
         */
        @NonNull
        public final String mAction;
        /**
         * Any additional information to send along with the action identifier
         */
        @Nullable
        public final Bundle mExtras;

        /**
         * Creates a custom action
         */
        public RawCustomPlaybackAction(int icon, String packageName,
                @NonNull String action,
                @Nullable Bundle extras) {
            mIcon = icon;
            mPackageName = packageName;
            mAction = action;
            mExtras = extras;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RawCustomPlaybackAction that = (RawCustomPlaybackAction) o;

            return mIcon == that.mIcon
                    && Objects.equals(mPackageName, that.mPackageName)
                    && Objects.equals(mAction, that.mAction)
                    && Objects.equals(mExtras, that.mExtras);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIcon, mPackageName, mAction, mExtras);
        }

        /**
         * Converts this {@link RawCustomPlaybackAction} into a {@link CustomPlaybackAction} by
         * fetching the appropriate drawable for the icon.
         *
         * @param context Context into which the icon will be drawn
         * @return the converted CustomPlaybackAction or null if appropriate {@link Resources}
         * cannot be obtained
         */
        @Nullable
        public CustomPlaybackAction fetchDrawable(@NonNull Context context) {
            Drawable icon;
            if (mPackageName == null) {
                icon = context.getDrawable(mIcon);
            } else {
                Resources resources = getResourcesForPackage(context, mPackageName);
                if (resources == null) {
                    return null;
                } else {
                    // the resources may be from another package. we need to update the
                    // configuration
                    // using the context from the activity so we get the drawable from the
                    // correct DPI
                    // bucket.
                    resources.updateConfiguration(context.getResources().getConfiguration(),
                            context.getResources().getDisplayMetrics());
                    icon = resources.getDrawable(mIcon, null);
                }
            }
            return new CustomPlaybackAction(icon, mAction, mExtras);
        }

        private Resources getResourcesForPackage(Context context, String packageName) {
            try {
                return context.getPackageManager().getResourcesForApplication(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Unable to get resources for " + packageName);
                return null;
            }
        }
    }

}
