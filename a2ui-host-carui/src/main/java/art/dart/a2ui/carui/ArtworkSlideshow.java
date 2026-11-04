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

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * A full-bleed, auto-advancing image slideshow over one stage view. It decides nothing: the host
 * opens it only for a component that survived the prune, and closes it on the reading that does
 * not. Crossfades only while animations are on; the advance timer runs either way.
 */
final class ArtworkSlideshow {

    /** One image and its captions. Captions are already clamped by the host. */
    static final class Slide {
        @DrawableRes final int mDrawable;
        @NonNull final CharSequence mTitle;
        @Nullable final CharSequence mCaption;
        @Nullable final CharSequence mCredit;

        Slide(@DrawableRes int drawable, @NonNull CharSequence title,
                @Nullable CharSequence caption, @Nullable CharSequence credit) {
            mDrawable = drawable;
            mTitle = title;
            mCaption = caption;
            mCredit = credit;
        }
    }

    static final long ADVANCE_MS = 3000;
    private static final long FADE_MS = 450;

    private final View mStage;
    private final ImageView mBackdrop;
    private final ImageView mImage;
    private final TextView mTitle;
    private final TextView mCaption;
    private final TextView mCredit;
    private final TextView mCounter;
    private final BooleanSupplier mAnimate;
    private final Runnable mAdvance = this::advance;
    /** Not View.postDelayed: a detached stage would hold the timer until it is attached. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private List<Slide> mSlides = Collections.emptyList();
    private int mIndex;

    ArtworkSlideshow(@NonNull View stage, @NonNull ImageView backdrop, @NonNull ImageView image,
            @NonNull TextView title, @NonNull TextView caption, @NonNull TextView credit,
            @NonNull TextView counter, @NonNull BooleanSupplier animate) {
        mStage = stage;
        mBackdrop = backdrop;
        mImage = image;
        mTitle = title;
        mCaption = caption;
        mCredit = credit;
        mCounter = counter;
        mAnimate = animate;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                mBackdrop.setRenderEffect(
                        RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP));
            } catch (RuntimeException unsupported) {
                // A plain dimmed backdrop is fine.
            }
        }
    }

    /** Shows the first slide and starts advancing. An empty list shows nothing. */
    boolean show(@NonNull List<Slide> slides) {
        stop();
        if (slides.isEmpty()) return false;
        mSlides = new ArrayList<>(slides);
        mIndex = 0;
        bind(false);
        mStage.setVisibility(View.VISIBLE);
        mHandler.postDelayed(mAdvance, ADVANCE_MS);
        return true;
    }

    /** Stops advancing and takes the pixels away. Idempotent. */
    void stop() {
        mHandler.removeCallbacks(mAdvance);
        mImage.animate().cancel();
        mImage.setAlpha(1f);
        mImage.setImageDrawable(null);
        mBackdrop.setImageDrawable(null);
        mStage.setVisibility(View.GONE);
        mSlides = Collections.emptyList();
        mIndex = 0;
    }

    boolean isShowing() {
        return mStage.getVisibility() == View.VISIBLE && !mSlides.isEmpty();
    }

    int index() {
        return mIndex;
    }

    int size() {
        return mSlides.size();
    }

    @VisibleForTesting
    void advance() {
        if (mSlides.isEmpty()) return;
        mIndex = (mIndex + 1) % mSlides.size();
        bind(mAnimate.getAsBoolean());
        mHandler.removeCallbacks(mAdvance);
        mHandler.postDelayed(mAdvance, ADVANCE_MS);
    }

    private void bind(boolean fade) {
        Slide slide = mSlides.get(mIndex);
        mTitle.setText(slide.mTitle);
        mCaption.setText(slide.mCaption);
        mCaption.setVisibility(slide.mCaption == null ? View.GONE : View.VISIBLE);
        mCredit.setText(slide.mCredit);
        mCredit.setVisibility(slide.mCredit == null ? View.GONE : View.VISIBLE);
        mCounter.setText(String.format(Locale.US, "%d / %d", mIndex + 1, mSlides.size()));
        mBackdrop.setImageResource(slide.mDrawable);
        mImage.animate().cancel();
        if (!fade) {
            mImage.setAlpha(1f);
            mImage.setImageResource(slide.mDrawable);
            return;
        }
        mImage.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            mImage.setImageResource(slide.mDrawable);
            mImage.animate().alpha(1f).setDuration(FADE_MS).start();
        }).start();
    }
}
