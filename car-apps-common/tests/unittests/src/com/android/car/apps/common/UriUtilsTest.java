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
package com.android.car.apps.common;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link UriUtils}' subaction-dialog round trip, and the guard that tested the wrong thing.
 *
 * <p>All 23 of {@code UriUtils}' entry points were unnamed by any test, which is how this survived:
 *
 * <pre>
 *   if (!TextUtils.isEmpty(dialogTitle)) {                              // correct
 *       builder.appendQueryParameter(DETAIL_DIALOG_URI_DIALOG_TITLE, dialogTitle);
 *   }
 *   if (!TextUtils.isEmpty(DETAIL_DIALOG_URI_DIALOG_DESCRIPTION)) {     // the CONSTANT
 *       builder.appendQueryParameter(DETAIL_DIALOG_URI_DIALOG_DESCRIPTION, dialogDescription);
 *   }
 * </pre>
 *
 * <p>The second guard tested the query-parameter NAME — a non-empty literal — so it always passed
 * and a null description was appended as the four-character string {@code "null"}. The failure
 * lands on the read side: {@link UriUtils#getSubactionDialogDescription} then returns {@code "null"}
 * where its javadoc promises {@code null}, and a caller checking absence with {@code == null} or
 * {@code TextUtils.isEmpty} gets a value that is neither.
 *
 * <p>The line above it handles {@code dialogTitle} correctly, which is what makes this a slip
 * rather than a convention — and what makes the title case below a genuine control rather than
 * decoration.
 */
@RunWith(RobolectricTestRunner.class)
public class UriUtilsTest {

    private static final Uri BASE = Uri.parse("content://com.example.provider/item/1");

    @Test
    public void aNullDescriptionIsAbsent_notTheStringNull() {
        Uri uri = UriUtils.getSubactionDialogUri(BASE, "Title", null, null, -1);

        assertThat(UriUtils.getSubactionDialogDescription(uri)).isNull();
    }

    @Test
    public void anEmptyDescriptionIsAbsentToo() {
        // The other input the guard is supposed to reject. Distinct from null because
        // TextUtils.isEmpty covers both and a fix that only null-checked would pass the test above
        // and fail this one.
        Uri uri = UriUtils.getSubactionDialogUri(BASE, "Title", "", null, -1);

        assertThat(UriUtils.getSubactionDialogDescription(uri)).isNull();
    }

    @Test
    public void aRealDescriptionSurvivesTheRoundTrip() {
        // Anti-vacuity. Without this, a guard that rejected EVERY description would satisfy both
        // tests above and the parameter would simply never be written.
        Uri uri = UriUtils.getSubactionDialogUri(BASE, "Title", "Some description", null, -1);

        assertThat(UriUtils.getSubactionDialogDescription(uri)).isEqualTo("Some description");
    }

    @Test
    public void theTitleWasAlwaysHandledCorrectly_soTheContrastIsReal() {
        // The control that makes the defect legible: the neighbouring guard has always tested its
        // parameter, so the two lines behaved differently for identical input.
        Uri withTitle = UriUtils.getSubactionDialogUri(BASE, "Title", null, null, -1);
        Uri withoutTitle = UriUtils.getSubactionDialogUri(BASE, null, null, null, -1);

        assertThat(UriUtils.getSubactionDialogTitle(withTitle)).isEqualTo("Title");
        assertThat(UriUtils.getSubactionDialogTitle(withoutTitle)).isNull();
    }
}
