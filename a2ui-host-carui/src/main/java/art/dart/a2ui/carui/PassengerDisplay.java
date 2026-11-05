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

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-local link between the driver activity's PASSENGER broadcast and the passenger display's
 * activity. It carries only a media name; the passenger activity decides, with its own display's
 * reading, whether it may play. A request made before that activity exists waits for it.
 */
final class PassengerDisplay {

    /** The passenger display's activity. */
    interface Surface {
        void play(@NonNull PassengerMedia media);

        void stopMedia();

        /** The live playback position of the video it plays, in ms; -1 when none is playing. */
        int positionMs();
    }

    /** Told when the passenger surface starts, stops or moves through its media. */
    interface Listener {
        void onPassengerChanged();
    }

    /** Media names the PASSENGER broadcast accepts besides the registered media. */
    static final String STOP = "stop";

    @Nullable private static WeakReference<Surface> sSurface;
    @Nullable private static PassengerMedia sRequested;
    private static final List<Listener> sListeners = new ArrayList<>();
    /** What the surface reports it is showing: a media name, or null when idle or held. */
    @Nullable private static String sPlaying;
    private static int sSegment = -1;

    private PassengerDisplay() {
    }

    /**
     * Applies a PASSENGER request: a registered media name plays, {@link #STOP} stops, anything
     * else is ignored. Returns true when a surface took it; false means it waits for one.
     */
    @MainThread
    static boolean request(@Nullable String mediaName) {
        Surface surface = surface();
        if (STOP.equals(mediaName)) {
            sRequested = null;
            if (surface != null) surface.stopMedia();
            return surface != null;
        }
        PassengerMedia media = PassengerMedia.forName(mediaName);
        if (media == null) return false;
        sRequested = media;
        if (surface == null) return false;
        surface.play(media);
        return true;
    }

    @MainThread
    static void attach(@NonNull Surface surface) {
        sSurface = new WeakReference<>(surface);
        PassengerMedia pending = sRequested;
        if (pending != null) surface.play(pending);
        notifyListeners();
    }

    @MainThread
    static void detach(@NonNull Surface surface) {
        if (surface() == surface) sSurface = null;
        report(null, -1);
    }

    @Nullable
    static Surface surface() {
        WeakReference<Surface> ref = sSurface;
        return ref == null ? null : ref.get();
    }

    /** The media last requested and not stopped, or null. */
    @Nullable
    static PassengerMedia requested() {
        return sRequested;
    }

    /** Called by the surface: what it is playing (null when idle) and which segment, or -1. */
    @MainThread
    static void report(@Nullable String playing, int segment) {
        boolean changed = !java.util.Objects.equals(playing, sPlaying) || segment != sSegment;
        sPlaying = playing;
        sSegment = segment;
        if (changed) notifyListeners();
    }

    @Nullable
    static String playing() {
        return sPlaying;
    }

    static int segment() {
        return sSegment;
    }

    /**
     * The passenger's live playback position, in ms, of the media it reports playing; -1 when it
     * plays none. Read at the moment of asking, so a host that joins in starts where it is now.
     */
    @MainThread
    static int positionMs() {
        Surface surface = surface();
        return surface == null || sPlaying == null ? -1 : surface.positionMs();
    }

    /**
     * Where another display's copy of {@code rawName} starts so the two play in step: the
     * passenger's position when it is playing that same clip (its portrait cut is a crop of it,
     * frame for frame), wrapped into one loop of {@code durationMs} as both loop; otherwise 0, from
     * the top.
     */
    static int startPositionMs(@Nullable String playing, int positionMs, @NonNull String rawName,
            int durationMs) {
        PassengerMedia media = PassengerMedia.forName(playing);
        if (media == null || media.mStills || !rawName.equals(media.mRawName) || positionMs <= 0) {
            return 0;
        }
        return durationMs > 0 ? positionMs % durationMs : positionMs;
    }

    @MainThread
    static void addListener(@NonNull Listener listener) {
        if (!sListeners.contains(listener)) sListeners.add(listener);
    }

    @MainThread
    static void removeListener(@NonNull Listener listener) {
        sListeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : new ArrayList<>(sListeners)) listener.onPassengerChanged();
    }

    @VisibleForTesting
    static void resetForTesting() {
        sSurface = null;
        sRequested = null;
        sPlaying = null;
        sSegment = -1;
        sListeners.clear();
    }
}
