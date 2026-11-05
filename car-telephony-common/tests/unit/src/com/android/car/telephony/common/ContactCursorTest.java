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
package com.android.car.telephony.common;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.database.CursorIndexOutOfBoundsException;
import android.database.MatrixCursor;
import android.provider.ContactsContract;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * What {@link Contact#fromCursor} does with a Cursor that is not shaped the way it expects.
 *
 * <p><b>This file is the first JVM test in this module, and that is the point.</b> Until
 * 2026-08-21 {@code car-telephony-common} appeared in no ledger at all — not
 * {@code docs/gate-counts.tsv}, not {@code docs/coverage-baseline.tsv}, not
 * {@code scripts/gate-modules.tsv}. Its only gate presence was 26 instrumented tests checked
 * against a hardcoded number. So 1,904 SLOC of Cursor, contact and telephony parsing had no
 * coverage record and no test-count ledger, and nothing would have noticed if its tests stopped
 * running or its source changed.
 *
 * <p><b>CHARACTERISATION, not a specification.</b> Two of the three tests below pin behaviour that
 * is WRONG — they document a crash rather than assert a contract. That is deliberate, and it is the
 * pattern {@code CarUiContentListItemSecureTest} established here: pinning what the code currently
 * does makes changing it a deliberate act taken against recorded evidence, rather than an
 * easy-looking one-line fix applied to inherited AOSP code in the middle of unrelated work.
 *
 * <p>The defect, at {@code Contact.java:253-257}:
 *
 * <pre>
 *   int mimetypeColumn = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE);
 *   String mimeType = cursor.getString(mimetypeColumn);
 *   switch (mimeType) {
 * </pre>
 *
 * <p>{@code getColumnIndex} returns {@code -1} for a column the provider did not supply, and
 * {@code getString(-1)} throws; and {@code switch} on a null String throws NPE. Neither is guarded.
 * This is reached once PER ROW from {@code InMemoryPhoneBook.onCursorLoaded}, on data that crosses
 * a process boundary from the contacts provider — which is exactly the input a library does not get
 * to assume is well-formed.
 *
 * <p>Robolectric rather than a mock Cursor, because a mock would let the test decide what
 * {@code getColumnIndex} returns and that is the thing under test. {@link MatrixCursor} is a real
 * implementation and answers {@code -1} for a genuinely absent column.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactCursorTest {

    /**
     * Every column {@code loadBasicInfo} reads, in order.
     *
     * <p>Fourteen of them, all read before {@code fromCursor} ever looks at MIMETYPE. Recovered by
     * parsing the source with a WRAP-AWARE regex: a line-based grep found ten and missed four whose
     * {@code getColumnIndex(} call wraps, which cost three rounds of "still column -1". The same
     * mistake the citation checker made on wrapped javadoc, in a different file, the same day.
     *
     * <p>This projection is declared nowhere in the module. A caller has to recover it exactly as
     * this test did.
     */
    private static final String[] BASIC_COLUMNS = {
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.RawContacts.DISPLAY_NAME_ALTERNATIVE,
            "phonebook_label",
            "phonebook_label_alt",
            ContactsContract.RawContacts.SORT_KEY_PRIMARY,
            ContactsContract.RawContacts.SORT_KEY_ALTERNATIVE,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.PHOTO_URI,
            ContactsContract.Data.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PINNED,
    };

    /** Values for {@link #BASIC_COLUMNS}, in the same order. */
    private static final Object[] BASIC_VALUES = {
            1L, 1L, "acct", "Ada Lovelace", "Lovelace, Ada", "A", "A",
            "ada", "lovelace", "lookup", null, null, 0, 0,
    };

    @SafeVarargs
    private static <T> T[] plus(T[] head, T... tail) {
        T[] out = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** A one-row cursor over exactly the columns named. */
    private static MatrixCursor cursorWith(String[] columns, Object... values) {
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        cursor.moveToFirst();
        return cursor;
    }

    @Test
    public void aCursorMissingMimetypeThrows_documentingTheDefect() {
        // The provider omitted MIMETYPE entirely. getColumnIndex answers -1 and getString(-1) is
        // an out-of-bounds read on the row.
        // Everything loadBasicInfo needs, and no MIMETYPE -- so the failure is specifically the
        // missing mimetype column and not an incomplete projection.
        MatrixCursor cursor = cursorWith(BASIC_COLUMNS, BASIC_VALUES);

        // The EXACT type, not RuntimeException. `assertThrows(RuntimeException.class, ...)` also
        // admits NullPointerException, IllegalStateException, IllegalArgumentException and
        // RuntimeException("Stub!") from an unshadowed android.* call -- so it could not tell this
        // defect from the one below, nor from a defensive fix that threw IllegalArgumentException,
        // which is a behaviour change a characterisation test must notice.
        assertThrows(CursorIndexOutOfBoundsException.class,
                () -> Contact.fromCursor(RuntimeEnvironment.getApplication(), cursor));
    }

    @Test
    public void aCursorWithANullMimetypeThrows_documentingTheDefect() {
        // The column exists and the value is null -- switch(null) is an NPE. A separate case from
        // the one above because they fail at different lines and a single guard fixes only one.
        MatrixCursor cursor = cursorWith(
                plus(BASIC_COLUMNS, ContactsContract.Data.MIMETYPE),
                plus(BASIC_VALUES, (Object) null));

        // NPE specifically: the column EXISTS here, so this fails at `switch (mimeType)` rather
        // than at getString(-1). Distinguishing the two is the point -- one guard fixes only one of
        // them, and a shared RuntimeException assertion would hide that.
        assertThrows(NullPointerException.class,
                () -> Contact.fromCursor(RuntimeEnvironment.getApplication(), cursor));
    }

    @Test
    public void aWellFormedRowIsParsed_soTheTwoAboveAreAboutTheMalformedInput() {
        // Anti-vacuity. Without this, an implementation that threw on EVERY cursor would satisfy
        // both tests above and they would prove nothing about malformed input specifically.
        // SEVENTEEN columns, and the count is the finding. Fourteen for loadBasicInfo, MIMETYPE to
        // route, then GIVEN_NAME and FAMILY_NAME for loadNameDetails -- every one read through an
        // unguarded getColumnIndex/getString pair, so ANY missing column throws
        // CursorIndexOutOfBoundsException, not just MIMETYPE. This projection is not declared
        // anywhere in the module; it was recovered by reading the source, which is how a caller
        // would have to discover it too.
        MatrixCursor cursor = cursorWith(
                plus(BASIC_COLUMNS,
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                plus(BASIC_VALUES,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                        "Ada", "Lovelace"));

        Contact contact = Contact.fromCursor(RuntimeEnvironment.getApplication(), cursor);

        assertThat(contact).isNotNull();
        // getGivenName/getFamilyName, NOT getDisplayName. Display name is set by loadBasicInfo, so
        // asserting it proves only that the cursor was readable -- a MIMETYPE that routed to the
        // switch's `default:` arm would log, throw nothing, and still satisfy it, while
        // loadNameDetails never ran. These two are set ONLY by loadNameDetails, so they establish
        // that the routing worked, which is what this control's name claims.
        assertThat(contact.getGivenName()).isEqualTo("Ada");
        assertThat(contact.getFamilyName()).isEqualTo("Lovelace");
    }
}
