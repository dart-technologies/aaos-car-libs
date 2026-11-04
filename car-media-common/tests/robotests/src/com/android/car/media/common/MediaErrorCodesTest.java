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

import android.support.v4.media.session.PlaybackStateCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins every copied error code against the platform constant it duplicates.
 *
 * <p>Same safeguard {@code A2uiRestrictionPolicyTest} applies to the {@code CarUxRestrictions}
 * copies: duplicating a wire value is only safe if something fails when the copy drifts. Without
 * this, {@code MediaErrorCodes} would be a table of integers that reviews as correct and maps the
 * wrong strings to the wrong failures.
 */
@RunWith(RobolectricTestRunner.class)
public class MediaErrorCodesTest {

    @Test
    public void everyCopiedCodeMatchesThePlatformConstant() {
        assertThat(MediaErrorCodes.ERROR_CODE_UNKNOWN_ERROR)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR);
        assertThat(MediaErrorCodes.ERROR_CODE_APP_ERROR)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_APP_ERROR);
        assertThat(MediaErrorCodes.ERROR_CODE_NOT_SUPPORTED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED);
        assertThat(MediaErrorCodes.ERROR_CODE_AUTHENTICATION_EXPIRED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED);
        assertThat(MediaErrorCodes.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED);
        assertThat(MediaErrorCodes.ERROR_CODE_CONCURRENT_STREAM_LIMIT)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_CONCURRENT_STREAM_LIMIT);
        assertThat(MediaErrorCodes.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED);
        assertThat(MediaErrorCodes.ERROR_CODE_NOT_AVAILABLE_IN_REGION)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION);
        assertThat(MediaErrorCodes.ERROR_CODE_CONTENT_ALREADY_PLAYING)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_CONTENT_ALREADY_PLAYING);
        assertThat(MediaErrorCodes.ERROR_CODE_SKIP_LIMIT_REACHED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_SKIP_LIMIT_REACHED);
        assertThat(MediaErrorCodes.ERROR_CODE_ACTION_ABORTED)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_ACTION_ABORTED);
        assertThat(MediaErrorCodes.ERROR_CODE_END_OF_QUEUE)
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE);
        assertThat(MediaErrorCodes.STATE_ERROR).isEqualTo(PlaybackStateCompat.STATE_ERROR);
    }

    /**
     * Anti-vacuity: the assertions above are hand-written, so a constant added to
     * {@code MediaErrorCodes} and forgotten here would be unpinned and invisible. This asserts the
     * SET is fully covered rather than trusting that each line was remembered — the shape this
     * repository has been bitten by five times.
     */
    @Test
    public void everyConstantInTheClassIsPinned() throws Exception {
        List<String> unpinned = new ArrayList<>();
        String testSource = pinnedNames();
        for (Field f : MediaErrorCodes.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            if (!testSource.contains(f.getName())) {
                unpinned.add(f.getName());
            }
        }
        assertThat(unpinned).isEmpty();
        // And the set is non-empty, so this cannot pass by there being nothing to check.
        assertThat(MediaErrorCodes.class.getDeclaredFields().length).isAtLeast(13);
    }

    /** The names this test asserts on, as data, so the check above can read them. */
    private static String pinnedNames() {
        return "ERROR_CODE_UNKNOWN_ERROR ERROR_CODE_APP_ERROR ERROR_CODE_NOT_SUPPORTED "
                + "ERROR_CODE_AUTHENTICATION_EXPIRED ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED "
                + "ERROR_CODE_CONCURRENT_STREAM_LIMIT ERROR_CODE_PARENTAL_CONTROL_RESTRICTED "
                + "ERROR_CODE_NOT_AVAILABLE_IN_REGION ERROR_CODE_CONTENT_ALREADY_PLAYING "
                + "ERROR_CODE_SKIP_LIMIT_REACHED ERROR_CODE_ACTION_ABORTED "
                + "ERROR_CODE_END_OF_QUEUE STATE_ERROR";
    }
}
