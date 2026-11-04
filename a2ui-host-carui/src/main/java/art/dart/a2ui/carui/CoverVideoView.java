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

import android.content.Context;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

/**
 * A VideoView that covers its box instead of fitting inside it, for the full-bleed player: the
 * plain view's aspect-correct fit, scaled up until no edge of the box shows. Centred by its
 * FrameLayout parent, the overflow falls outside the stage. Before the video's size is known it
 * measures exactly as a VideoView does.
 */
final class CoverVideoView extends VideoView {

    CoverVideoView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int[] size = cover(getMeasuredWidth(), getMeasuredHeight(),
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size[0], size[1]);
    }

    /**
     * The fitted size scaled up, aspect kept, until it covers the box; unchanged when it already
     * covers it or either size is unknown.
     */
    @VisibleForTesting
    @NonNull
    static int[] cover(int fitWidth, int fitHeight, int boxWidth, int boxHeight) {
        if (fitWidth <= 0 || fitHeight <= 0 || boxWidth <= 0 || boxHeight <= 0
                || (fitWidth >= boxWidth && fitHeight >= boxHeight)) {
            return new int[]{fitWidth, fitHeight};
        }
        float scale = Math.max((float) boxWidth / fitWidth, (float) boxHeight / fitHeight);
        return new int[]{Math.max(boxWidth, Math.round(fitWidth * scale)),
                Math.max(boxHeight, Math.round(fitHeight * scale))};
    }
}
