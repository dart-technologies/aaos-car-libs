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
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.Set;

/**
 * The passenger display's surface. Every decision uses THIS display's own reading, through the
 * same provenance rule and component policy as the driver's ({@code A2uiCarUiActivity.allowedFor}),
 * so video plays here only where this display allows VIDEO, whatever the driver's display says.
 *
 * <p>Started on the passenger display by the system (or {@code am start --display <id>}); an app
 * cannot place a window on a private display it is not already present on.
 */
// metalava api-lint: ForbiddenSuperClass and ProtectedMember, suppressed as on A2uiCarUiActivity.
@SuppressWarnings("ForbiddenSuperClass")
public class A2uiPassengerActivity extends Activity implements PassengerDisplay.Surface {

    private static final String TAG = "A2uiPassenger";
    /** String extra on the launch intent, debuggable builds only: a media name to play at once. */
    static final String EXTRA_MEDIA = DebugBroadcasts.EXTRA_MEDIA;
    private static final long TICK_MS = 500;

    private CarUxRestrictionsUtil mRestrictions;
    private FrameLayout mStage;
    private TextView mMessage;
    private TextView mCredit;
    private TextView mZone;
    @Nullable private VideoView mVideo;
    @Nullable private PassengerMedia mMedia;
    /** This display's last genuine reading, captured before a demo zone reading replaces it. */
    @Nullable private CarUxRestrictions mGenuine;
    private boolean mDemoZone;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            ignored -> mHandler.post(this::enforce);

    @Override
    @SuppressWarnings("ProtectedMember")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.a2ui_passenger_activity);
        mStage = findViewById(R.id.passenger_stage);
        mMessage = findViewById(R.id.passenger_message);
        mCredit = findViewById(R.id.passenger_credit);
        mZone = findViewById(R.id.passenger_zone);
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
        PassengerDisplay.attach(this);
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
        PassengerDisplay.detach(this);
        mRestrictions.unregister(mListener);
        super.onStop();
    }

    @Override
    public void play(@NonNull PassengerMedia media) {
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

    @Override
    public void stopMedia() {
        teardownVideo();
        clearDemoZone();
        showMessage(R.string.a2ui_passenger_idle);
    }

    /** Re-decides on every change of this display's reading; video goes the moment it may not. */
    private void enforce() {
        if (mVideo != null && !allowedHere().contains(ComponentType.VIDEO)) {
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
     * AVD declares one occupant zone). Debug builds may stand in a labelled DEMO zone reading for
     * it, and never for the default (driver) display.
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
                + " applying a labelled DEMO zone reading (BASELINE) to this display only");
        mRestrictions.setDemoUxRestrictions(new CarUxRestrictions.Builder(false,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());
        mDemoZone = true;
        mZone.setVisibility(View.VISIBLE);
    }

    private void clearDemoZone() {
        if (!mDemoZone) return;
        mDemoZone = false;
        mZone.setVisibility(View.GONE);
        if (mGenuine != null) {
            mRestrictions.setUxRestrictions(mGenuine);
            mGenuine = null;
        }
    }

    private void startVideo(@NonNull PassengerMedia media) {
        teardownVideo();
        int named = getResources().getIdentifier(media.mRawName, "raw", getPackageName());
        int raw = named != 0 ? named : R.raw.demo_placeholder_video;
        VideoView video = new VideoView(this);
        mStage.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // Its own zone's sound: no focus taken from the cabin's audio.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + raw));
        video.setOnPreparedListener(player -> {
            if (mVideo != video) return;
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
        mMessage.setVisibility(View.GONE);
        // Credit only what is actually playing: the fallback fixture is not the named film.
        mCredit.setText(named != 0 ? getString(media.mCredit) : null);
        mCredit.setVisibility(named != 0 ? View.VISIBLE : View.GONE);
        PassengerDisplay.report(media.mName, 0);
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, TICK_MS);
        Log.i(TAG, "playing " + media.mName + " on display " + mRestrictions.getDisplayId());
    }

    /** Reports which segment is on screen, for hosts that follow along. */
    private void tick() {
        VideoView video = mVideo;
        PassengerMedia media = mMedia;
        if (video == null || media == null) return;
        int duration = video.getDuration();
        int segment = 0;
        if (duration > 0 && media.mSegments > 1) {
            segment = Math.min(media.mSegments - 1,
                    (int) ((long) video.getCurrentPosition() * media.mSegments / duration));
        }
        PassengerDisplay.report(media.mName, segment);
        mHandler.postDelayed(mTick, TICK_MS);
    }

    private void teardownVideo() {
        mHandler.removeCallbacks(mTick);
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
        mMessage.setVisibility(View.VISIBLE);
    }

    @VisibleForTesting
    @Nullable
    VideoView videoForTesting() {
        return mVideo;
    }

    @VisibleForTesting
    @NonNull
    TextView messageForTesting() {
        return mMessage;
    }
}
