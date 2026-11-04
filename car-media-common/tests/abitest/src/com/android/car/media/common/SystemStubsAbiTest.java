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

package com.android.car.media.common;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Verifies the hand-written {@code :car-system-stubs} against the REAL platform classes on a device.
 *
 * <p><b>Why this exists.</b> {@code android.car.media.CarMediaManager} and the {@code @SystemApi}
 * constant {@code Car.CAR_MEDIA_SERVICE} are absent from the public {@code android.car.jar} at every
 * SDK level, so this repo declares minimal stubs for them and consumes those {@code compileOnly}.
 * Signatures were copied from AOSP rather than invented — but nothing had ever checked that claim
 * against a device.
 *
 * <p>A compiling build proves only that the stub is self-consistent. If a signature drifts from the
 * platform, {@code car-media-common} compiles perfectly and then throws {@link NoSuchMethodError} at
 * runtime, on a code path a user reaches by opening media. That is the same failure shape this
 * project has hit repeatedly: a partial success — compilation — reported as a complete one, with the
 * qualifying information sitting somewhere nobody correlated.
 *
 * <p><b>Everything here is reflective on purpose.</b> There is no compile-time reference to
 * {@code CarMediaManager}, so this test resolves the class the device actually has. The stub is
 * {@code compileOnly} for this module and therefore absent from the test APK; if that ever changed,
 * {@link #stubsAreNotPackagedIntoTheApk()} fails and every other assertion here becomes
 * self-referential and worthless.
 *
 * <p>Constants are the sharpest case: {@code String} and {@code int} constants are inlined by javac,
 * so a wrong value is baked permanently into consumer bytecode and fails <i>silently</i> rather than
 * loudly.
 */
@RunWith(AndroidJUnit4.class)
public class SystemStubsAbiTest {

    private static final String CAR_MEDIA_MANAGER = "android.car.media.CarMediaManager";
    private static final String CAR = "android.car.Car";

    private static Class<?> platform(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    /**
     * Guards every other assertion in this file. If the stubs were ever packaged into this APK,
     * reflection would resolve OUR classes and the whole suite would be checking the stub against
     * itself.
     */
    @Test
    public void stubsAreNotPackagedIntoTheApk() throws Exception {
        Class<?> manager = platform(CAR_MEDIA_MANAGER);

        // A stub class would have been loaded from the app's own dex. The platform's comes from the
        // boot classloader, whose parent chain has no app class loader.
        ClassLoader loader = manager.getClassLoader();
        String describedBy = loader == null ? "boot" : loader.toString();
        assertThat(describedBy).doesNotContain("dalvik.system.PathClassLoader[DexPathList[[zip file");
    }

    // ------------------------------------------------------------------ CarMediaManager

    @Test
    public void carMediaManager_existsOnDevice() throws Exception {
        assertThat(platform(CAR_MEDIA_MANAGER)).isNotNull();
    }

    @Test
    public void mediaSourceModeConstants_matchTheStubValues() throws Exception {
        Class<?> manager = platform(CAR_MEDIA_MANAGER);

        Field playback = manager.getField("MEDIA_SOURCE_MODE_PLAYBACK");
        Field browse = manager.getField("MEDIA_SOURCE_MODE_BROWSE");

        // Inlined at compile time by every consumer, so a mismatch is silent and permanent.
        assertThat(playback.getInt(null)).isEqualTo(0);
        assertThat(browse.getInt(null)).isEqualTo(1);
    }

    @Test
    public void mediaSourceChangedListener_hasTheDeclaredCallback() throws Exception {
        Class<?> listener = platform(CAR_MEDIA_MANAGER + "$MediaSourceChangedListener");

        assertThat(listener.isInterface()).isTrue();
        Method callback = listener.getMethod("onMediaSourceChanged", ComponentName.class);
        assertThat(callback.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void getMediaSource_signatureMatches() throws Exception {
        Method m = platform(CAR_MEDIA_MANAGER).getMethod("getMediaSource", int.class);

        assertThat(m.getReturnType()).isEqualTo(ComponentName.class);
    }

    @Test
    public void setMediaSource_signatureMatches() throws Exception {
        Method m = platform(CAR_MEDIA_MANAGER)
                .getMethod("setMediaSource", ComponentName.class, int.class);

        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void addMediaSourceListener_signatureMatches() throws Exception {
        Class<?> listener = platform(CAR_MEDIA_MANAGER + "$MediaSourceChangedListener");
        Method m = platform(CAR_MEDIA_MANAGER)
                .getMethod("addMediaSourceListener", listener, int.class);

        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void removeMediaSourceListener_signatureMatches() throws Exception {
        Class<?> listener = platform(CAR_MEDIA_MANAGER + "$MediaSourceChangedListener");
        Method m = platform(CAR_MEDIA_MANAGER)
                .getMethod("removeMediaSourceListener", listener, int.class);

        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    public void getLastMediaSources_signatureMatches() throws Exception {
        Method m = platform(CAR_MEDIA_MANAGER).getMethod("getLastMediaSources", int.class);

        assertThat(m.getReturnType()).isEqualTo(List.class);
    }

    @Test
    public void independentPlaybackConfig_signaturesMatch() throws Exception {
        Class<?> manager = platform(CAR_MEDIA_MANAGER);

        assertThat(manager.getMethod("isIndependentPlaybackConfig").getReturnType())
                .isEqualTo(boolean.class);
        assertThat(manager.getMethod("setIndependentPlaybackConfig", boolean.class).getReturnType())
                .isEqualTo(void.class);
    }

    // ------------------------------------------------------------------ Car

    /**
     * The single {@code @SystemApi} constant that forced this module to drop
     * {@code useLibrary 'android.car'} entirely. A wrong value here means
     * {@code getCarManager(...)} silently returns null on device.
     */
    @Test
    public void carMediaServiceConstant_matchesTheStubValue() throws Exception {
        Field f = platform(CAR).getField("CAR_MEDIA_SERVICE");

        assertThat(f.get(null)).isEqualTo("car_media");
    }

    @Test
    public void theOtherCarConstantsTheStubDeclares_stillMatch() throws Exception {
        Class<?> car = platform(CAR);

        assertThat(car.getField("PACKAGE_SERVICE").get(null)).isEqualTo("package");
        assertThat(car.getField("CAR_INTENT_ACTION_MEDIA_TEMPLATE").get(null))
                .isEqualTo("android.car.intent.action.MEDIA_TEMPLATE");
    }

    @Test
    public void carMethodsTheStubDeclares_stillExist() throws Exception {
        Class<?> car = platform(CAR);

        assertThat(car.getMethod("createCar", android.content.Context.class).getReturnType())
                .isEqualTo(car);
        assertThat(car.getMethod("disconnect").getReturnType()).isEqualTo(void.class);
        assertThat(car.getMethod("getCarManager", String.class).getReturnType())
                .isEqualTo(Object.class);
    }

    // ------------------------------------------------------------------ CarMediaIntents

    @Test
    public void carMediaIntentsConstants_stillMatch() throws Exception {
        Class<?> intents = platform("android.car.media.CarMediaIntents");

        assertThat(intents.getField("ACTION_MEDIA_TEMPLATE").get(null))
                .isEqualTo("android.car.intent.action.MEDIA_TEMPLATE");
        assertThat(intents.getField("EXTRA_MEDIA_COMPONENT").get(null))
                .isEqualTo("android.car.intent.extra.MEDIA_COMPONENT");
    }
}
