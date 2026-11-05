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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.function.Predicate;

/**
 * The bundled media the passenger display may play, by the name the PASSENGER broadcast uses. An
 * exact registry: a broadcast names an entry, never a resource.
 */
final class PassengerMedia {

    /** {@code video}: Big Buck Bunny when bundled, else the generated decoder fixture. */
    static final String VIDEO = "video";
    /** Suffix of a bundled portrait cut of the media, e.g. {@code bbb_passenger_portrait}. */
    static final String PORTRAIT_SUFFIX = "_portrait";

    @NonNull final String mName;
    /** Raw resource name, looked up at play time so a missing file falls back, not fails. */
    @NonNull final String mRawName;
    @StringRes final int mCredit;
    /** Segments the media is cut into, for hosts that follow along (1 when not segmented). */
    final int mSegments;
    /** Whether its sound belongs in the cabin rather than the passenger's own zone. */
    final boolean mCabinAudio;
    /** Stills shown as a slideshow rather than a video file. */
    final boolean mStills;
    /**
     * Whether the file carries its own credit and captions burned in: the host lays no credit of
     * its own over it, so the two never collide.
     */
    final boolean mOwnCredit;

    private PassengerMedia(@NonNull String name, @NonNull String rawName, @StringRes int credit,
            int segments, boolean cabinAudio, boolean stills, boolean ownCredit) {
        mName = name;
        mRawName = rawName;
        mCredit = credit;
        mSegments = segments;
        mCabinAudio = cabinAudio;
        mStills = stills;
        mOwnCredit = ownCredit;
    }

    /**
     * The raw resource to play: on a portrait display the portrait cut when one is bundled, else
     * the file itself; null when neither is bundled (the caller falls back to the decoder fixture
     * and credits nothing). Either way the picture fills the display, centre-cropped.
     */
    @Nullable
    String fileFor(boolean portraitDisplay, @NonNull Predicate<String> bundled) {
        if (mRawName.isEmpty()) return null;
        String portrait = mRawName + PORTRAIT_SUFFIX;
        if (portraitDisplay && bundled.test(portrait)) return portrait;
        return bundled.test(mRawName) ? mRawName : null;
    }

    @Nullable
    static PassengerMedia forName(@Nullable String name) {
        if (VIDEO.equals(name)) {
            return new PassengerMedia(VIDEO, "bbb_passenger", R.string.a2ui_passenger_video_credit,
                    1, false, false, false);
        }
        return null;
    }
}
