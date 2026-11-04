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
package art.dart.a2ui.carui;

import android.app.Activity;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The passenger display's surface. Every decision uses THIS display's own reading, through the
 * same provenance rule and component policy as the driver's ({@code A2uiCarUiActivity.allowedFor}),
 * so video plays here only where this display allows VIDEO, whatever the driver's display says.
 *
 * <p>Started on the passenger display by the system (or {@code am start --display <id>}); an app
 * cannot place a window on a private display it is not already present on.
 *
 * <p>Video fills the display, centre-cropped and never letterboxed, preferring a bundled portrait
 * cut on a portrait display ({@link PassengerMedia#fileFor}); its credit sits over the picture.
 */
// metalava api-lint: ForbiddenSuperClass and ProtectedMember, suppressed as on A2uiCarUiActivity.
@SuppressWarnings("ForbiddenSuperClass")
public class A2uiPassengerActivity extends Activity {

    private static final String TAG = "A2uiPassenger";
    /** String extra on the launch intent, debuggable builds only: a media name to play at once. */
    static final String EXTRA_MEDIA = DebugBroadcasts.EXTRA_MEDIA;
    private static final long TICK_MS = 500;

    private CarUxRestrictionsUtil mRestrictions;
    private FrameLayout mStage;
    /** The idle or held card: an accent rule over {@link #mMessage}. */
    private View mIdle;
    private TextView mMessage;
    private TextView mCredit;
    @Nullable private FillVideoView mVideo;
    @Nullable private PassengerMedia mMedia;
    private ArtworkSlideshow mSlideshow;
    /** This display's last genuine reading, captured before a demo zone reading replaces it. */
    @Nullable private CarUxRestrictions mGenuine;
    private boolean mDemoZone;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            ignored -> mHandler.post(this::enforce);
    /** What PassengerDisplay talks to; kept off this public class's API. */
    private final PassengerDisplay.Surface mSurface = new PassengerDisplay.Surface() {
        @Override
        public void play(@NonNull PassengerMedia media) {
            playMedia(media);
        }

        @Override
        public void stopMedia() {
            stopPassengerMedia();
        }
    };

    @Override
    @SuppressWarnings("ProtectedMember")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.a2ui_passenger_activity);
        // The screen's name for accessibility only: the theme draws no title bar.
        setTitle(R.string.a2ui_passenger_label);
        mStage = findViewById(R.id.passenger_stage);
        mIdle = findViewById(R.id.passenger_idle);
        mMessage = findViewById(R.id.passenger_message);
        mCredit = findViewById(R.id.passenger_credit);
        mSlideshow = new ArtworkSlideshow(findViewById(R.id.passenger_gallery),
                findViewById(R.id.passenger_gallery_backdrop),
                findViewById(R.id.passenger_gallery_image),
                findViewById(R.id.passenger_gallery_title),
                findViewById(R.id.passenger_gallery_caption),
                findViewById(R.id.passenger_gallery_credit),
                findViewById(R.id.passenger_gallery_counter), () -> true);
        mRestrictions = CarUxRestrictionsUtil.getInstance(this);
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        Log.i(TAG, "passenger surface on display " + mRestrictions.getDisplayId() + " source="
                + snapshot.getSource() + " active=0x"
                + Integer.toHexString(snapshot.getRestrictions().getActiveRestrictions()));
    }

    @Override
    @SuppressWarnings("ProtectedMember")
    protected void onStart() {
        super.onStart();
        mRestrictions.register(mListener);
        PassengerDisplay.attach(mSurface);
        String launched = getIntent().getStringExtra(EXTRA_MEDIA);
        if (launched != null
                && A2uiCarUiActivity.debugBusAllowed(getApplicationInfo(), Build.TYPE)) {
            PassengerDisplay.request(launched);
        }
    }

    @Override
    @SuppressWarnings("ProtectedMember")
    protected void onStop() {
        teardownVideo();
        clearDemoZone();
        PassengerDisplay.detach(mSurface);
        mRestrictions.unregister(mListener);
        super.onStop();
    }

    void playMedia(@NonNull PassengerMedia media) {
        Set<ComponentType> allowed = allowedHere();
        if (!allowed.contains(ComponentType.VIDEO) && mayUseDemoZone()) {
            applyDemoZone();
            allowed = allowedHere();
        }
        if (!allowed.contains(ComponentType.VIDEO)) {
            Log.i(TAG, "video held: display " + mRestrictions.getDisplayId()
                    + " does not allow VIDEO");
            teardownVideo();
            showMessage(R.string.a2ui_passenger_held);
            return;
        }
        startVideo(media);
    }

    void stopPassengerMedia() {
        teardownVideo();
        clearDemoZone();
        showMessage(R.string.a2ui_passenger_idle);
    }

    /** Re-decides on every change of this display's reading; video goes the moment it may not. */
    private void enforce() {
        if ((mVideo != null || mSlideshow.isShowing())
                && !allowedHere().contains(ComponentType.VIDEO)) {
            Log.i(TAG, "video withdrawn: display " + mRestrictions.getDisplayId()
                    + " no longer allows VIDEO");
            teardownVideo();
            showMessage(R.string.a2ui_passenger_held);
        }
    }

    @NonNull
    private Set<ComponentType> allowedHere() {
        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot =
                mRestrictions.getRestrictionsSnapshot();
        return A2uiCarUiActivity.allowedFor(snapshot.getSource(), snapshot.getRestrictions());
    }

    /**
     * A display with no passenger-zone configuration reads fully restricted from car service (this
     * AVD declares one occupant zone). Debug builds may stand in a DEMO zone reading for it, and
     * never for the default (driver) display. The DEMO provenance is in the reading's source and
     * the log, not on the passenger's screen.
     */
    private boolean mayUseDemoZone() {
        return A2uiCarUiActivity.debugBusAllowed(getApplicationInfo(), Build.TYPE)
                && mRestrictions.getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    private void applyDemoZone() {
        if (mGenuine == null && mRestrictions.getRestrictionsSource()
                == CarUxRestrictionsUtil.RestrictionsSource.DELIVERED) {
            mGenuine = mRestrictions.getCurrentRestrictions();
        }
        Log.i(TAG, "display " + mRestrictions.getDisplayId() + " has no passenger-zone reading;"
                + " applying a DEMO zone reading (BASELINE) to this display only");
        mRestrictions.setDemoUxRestrictions(new CarUxRestrictions.Builder(false,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());
        mDemoZone = true;
    }

    private void clearDemoZone() {
        if (!mDemoZone) return;
        mDemoZone = false;
        Log.i(TAG, "display " + mRestrictions.getDisplayId() + ": DEMO zone reading cleared");
        if (mGenuine != null) {
            mRestrictions.setUxRestrictions(mGenuine);
            mGenuine = null;
        }
    }

    private void startVideo(@NonNull PassengerMedia media) {
        teardownVideo();
        if (media.mStills) {
            // Auto-advancing stills are moving imagery: decided as video, above.
            mSlideshow.show(withCredit(MetArtworks.slides(), getString(media.mCredit)));
            mMedia = media;
            mIdle.setVisibility(View.GONE);
            PassengerDisplay.report(media.mName, 0);
            mHandler.postDelayed(mTick, TICK_MS);
            Log.i(TAG, "showing " + media.mName + " on display " + mRestrictions.getDisplayId());
            return;
        }
        String file = media.fileFor(isPortrait(), name -> rawId(name) != 0);
        int raw = file != null ? rawId(file) : R.raw.demo_placeholder_video;
        FillVideoView video = new FillVideoView(this);
        // Centred and cropped by the stage: the picture covers the display edge to edge.
        mStage.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // Its own zone's sound: no focus taken from the cabin's audio.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + raw));
        video.setOnPreparedListener(player -> {
            if (mVideo != video) return;
            video.setVideoSize(player.getVideoWidth(), player.getVideoHeight());
            player.setLooping(true);
            if (!media.mCabinAudio) player.setVolume(0f, 0f);
            video.start();
        });
        video.setOnErrorListener((player, what, extra) -> {
            Log.w(TAG, "passenger video failed: " + what + "/" + extra);
            return true;
        });
        mVideo = video;
        mMedia = media;
        mIdle.setVisibility(View.GONE);
        // Credit only what is actually playing: the fallback fixture is not the named film.
        mCredit.setText(file != null ? getString(media.mCredit) : null);
        mCredit.setVisibility(file != null ? View.VISIBLE : View.GONE);
        PassengerDisplay.report(media.mName, 0);
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, TICK_MS);
        Log.i(TAG, "playing " + media.mName + " (" + (file != null ? file : "decoder fixture")
                + ") on display " + mRestrictions.getDisplayId());
    }

    private int rawId(@NonNull String name) {
        return getResources().getIdentifier(name, "raw", getPackageName());
    }

    /** This activity's display, from its own resources: a portrait screen prefers portrait cuts. */
    private boolean isPortrait() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return metrics.heightPixels > metrics.widthPixels;
    }

    /** The slides with this media's own credit on each, so stills and film credit alike. */
    @NonNull
    private static List<ArtworkSlideshow.Slide> withCredit(
            @NonNull List<ArtworkSlideshow.Slide> slides, @NonNull CharSequence credit) {
        List<ArtworkSlideshow.Slide> out = new ArrayList<>();
        for (ArtworkSlideshow.Slide slide : slides) {
            out.add(new ArtworkSlideshow.Slide(slide.mDrawable, slide.mTitle, slide.mCaption,
                    credit));
        }
        return out;
    }

    /** Reports which segment is on screen, for hosts that follow along. */
    private void tick() {
        VideoView video = mVideo;
        PassengerMedia media = mMedia;
        if (media == null) return;
        int segment = 0;
        if (media.mStills) {
            segment = mSlideshow.index();
        } else if (video == null) {
            return;
        } else if (PassengerMedia.ARTWORK.equals(media.mName)) {
            // Per-work start times from FILM.json, else the 6 s cadence.
            segment = MetArtworks.segmentAt(MetArtworks.filmStarts(this),
                    video.getCurrentPosition());
        } else if (video.getDuration() > 0 && media.mSegments > 1) {
            segment = Math.min(media.mSegments - 1, (int) ((long) video.getCurrentPosition()
                    * media.mSegments / video.getDuration()));
        }
        PassengerDisplay.report(media.mName, segment);
        mHandler.postDelayed(mTick, TICK_MS);
    }

    private void teardownVideo() {
        mHandler.removeCallbacks(mTick);
        mSlideshow.stop();
        VideoView video = mVideo;
        mVideo = null;
        mMedia = null;
        if (video != null) {
            video.stopPlayback();
            mStage.removeView(video);
        }
        mCredit.setVisibility(View.GONE);
        PassengerDisplay.report(null, -1);
    }

    private void showMessage(int text) {
        mMessage.setText(text);
        mIdle.setVisibility(View.VISIBLE);
    }

    @VisibleForTesting
    @Nullable
    FillVideoView videoForTesting() {
        return mVideo;
    }

    @VisibleForTesting
    @NonNull
    TextView creditForTesting() {
        return mCredit;
    }

    @VisibleForTesting
    @NonNull
    View idleForTesting() {
        return mIdle;
    }

    /**
     * A VideoView that covers its parent instead of fitting inside it: measured to the larger of
     * the two scales and centred by its FrameLayout parent, which clips the overflow. Until the
     * video's size is known it measures as a plain VideoView.
     */
    @VisibleForTesting
    static final class FillVideoView extends VideoView {
        private int mVideoWidth;
        private int mVideoHeight;

        FillVideoView(@NonNull Context context) {
            super(context);
        }

        void setVideoSize(int width, int height) {
            if (width == mVideoWidth && height == mVideoHeight) return;
            mVideoWidth = width;
            mVideoHeight = height;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (mVideoWidth <= 0 || mVideoHeight <= 0 || width <= 0 || height <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            float scale = Math.max((float) width / mVideoWidth, (float) height / mVideoHeight);
            setMeasuredDimension(Math.max(width, Math.round(mVideoWidth * scale)),
                    Math.max(height, Math.round(mVideoHeight * scale)));
        }
    }

    @VisibleForTesting
    @NonNull
    ArtworkSlideshow slideshowForTesting() {
        return mSlideshow;
    }

    @VisibleForTesting
    @NonNull
    TextView messageForTesting() {
        return mMessage;
    }
}
