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

/**
 * The playback error codes a media service reports, as wire values.
 *
 * <h2>Why these are duplicated rather than imported — Media3 migration, file 2</h2>
 *
 * <p>These are numbers a media <b>service</b> puts on the wire. A third-party AAOS app reporting
 * {@code ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED} sends the integer 6 whether the client reading it is
 * {@code MediaControllerCompat} or a Media3 {@code MediaController} talking to the same legacy
 * session. Importing them from {@code PlaybackStateCompat} tied a string-lookup table to a
 * deprecated client library for values that do not belong to the client at all.
 *
 * <p>This repository already does exactly this for {@code CarUxRestrictions} in
 * {@code A2uiRestrictionPolicy}, for the same reason and with the same safeguard: the values are
 * copied, and a test pins every one of them against the platform constant so the copy cannot
 * silently disagree. See {@code MediaErrorCodesTest}.
 *
 * <p><b>What this does NOT settle.</b> Whether a Media3 client surfaces these same integers when it
 * wraps a legacy session, or translates them into {@code PlaybackException} codes, is a question
 * about the CLIENT and is measurable with the browse fixture. That measurement belongs with the
 * flip, not here — what belongs here is that the table stops being a reason to keep the import.
 */
public final class MediaErrorCodes {

    /** No error. The compat name says UNKNOWN_ERROR; the meaning is "nothing is wrong". */
    public static final int ERROR_CODE_UNKNOWN_ERROR = 0;
    public static final int ERROR_CODE_APP_ERROR = 1;
    public static final int ERROR_CODE_NOT_SUPPORTED = 2;
    public static final int ERROR_CODE_AUTHENTICATION_EXPIRED = 3;
    public static final int ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED = 4;
    public static final int ERROR_CODE_CONCURRENT_STREAM_LIMIT = 5;
    public static final int ERROR_CODE_PARENTAL_CONTROL_RESTRICTED = 6;
    public static final int ERROR_CODE_NOT_AVAILABLE_IN_REGION = 7;
    public static final int ERROR_CODE_CONTENT_ALREADY_PLAYING = 8;
    public static final int ERROR_CODE_SKIP_LIMIT_REACHED = 9;
    public static final int ERROR_CODE_ACTION_ABORTED = 10;
    public static final int ERROR_CODE_END_OF_QUEUE = 11;

    /** The playback state meaning "errored", as opposed to an error code on a working state. */
    public static final int STATE_ERROR = 7;

    private MediaErrorCodes() {
    }
}
