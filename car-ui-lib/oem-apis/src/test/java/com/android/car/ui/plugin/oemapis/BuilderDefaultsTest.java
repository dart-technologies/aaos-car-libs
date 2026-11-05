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

package com.android.car.ui.plugin.oemapis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.car.ui.plugin.oemapis.recyclerview.ContentListItemOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.MenuItemOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.TabOEMV1;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The permissive-default audit, in executable form.
 *
 * <p>{@code oem-apis} is the plugin contract: every value here crosses the boundary into an OEM
 * implementation this repository does not own, so a Builder default is not an internal convenience
 * — it is the value a plugin author gets when they say nothing. This class asserts <em>every</em>
 * Builder default in the module so that none of them can change without a test naming it.
 *
 * <p><b>These are characterisation tests, not approval.</b> Two of the values asserted below are
 * reported upstream as defects (see {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md}). Pinning
 * them is not agreement that they are correct; it is the only way to make an unfixed, externally
 * owned decision <em>visible</em> and change-detecting while the report is outstanding. Each is
 * marked. If one of these tests fails because upstream changed the value, that is the report being
 * answered — read it before editing the expectation.
 *
 * <p>Written because this module had zero tests across 2,439 lines, and every input here is
 * settable: no device, no emulator, no plugin loaded. See {@code docs/VERIFICATION.md} for
 * why "settable" is the property that decides whether coverage is even possible.
 */
@RunWith(JUnit4.class)
public class BuilderDefaultsTest {

    // ---------------------------------------------------------------------------------------
    // MenuItemOEMV1 — 14 builder fields
    // ---------------------------------------------------------------------------------------

    @Test
    public void menuItem_defaults_areExactlyTheseValues() {
        MenuItemOEMV1 item = MenuItemOEMV1.builder().build();

        assertEquals("key", 0, item.getKey());
        assertNull("title", item.getTitle());
        assertNull("icon", item.getIcon());
        assertTrue("visible", item.isVisible());
        assertTrue("enabled", item.isEnabled());
        assertFalse("primary", item.isPrimary());
        assertTrue("tinted", item.isTinted());
        assertFalse("showIconAndTitle", item.isShowingIconAndTitle());
        assertEquals("displayBehavior",
                MenuItemOEMV1.DISPLAY_BEHAVIOR_ALWAYS, item.getDisplayBehavior());
        assertFalse("checkable", item.isCheckable());
        assertFalse("checked", item.isChecked());
        assertFalse("activatable", item.isActivatable());
        assertFalse("activated", item.isActivated());
        assertNull("onClickListener", item.getOnClickListener());
    }

    /**
     * REPORTED UPSTREAM — {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md}, "A second occurrence
     * in the same API surface".
     *
     * <p>{@code mIsRestricted = false} is a restriction-positive property defaulted permissive, in
     * a block where defaults were plainly being chosen (three of its five boolean neighbours are
     * set to non-Java-default values). A plugin author constructing a {@code MenuItemOEMV1}
     * directly — which this Builder is public API precisely to allow — gets a menu item that never
     * renders as restricted.
     *
     * <p>It is currently unreachable in practice only because {@code MenuItemAdapterV1:62} calls
     * {@code setRestricted(...)} on every conversion. That is one call site standing between this
     * default and a live fail-open, and nothing records it as load-bearing — the
     * "cleared-conditionally" verdict in {@code docs/ARCHITECTURE.md}.
     */
    @Test
    public void menuItem_isRestricted_defaultsPermissive_reportedUpstream() {
        assertFalse("mIsRestricted defaults false — see AOSP-SECURE-DEFAULT-REPORT.md",
                MenuItemOEMV1.builder().build().isRestricted());
    }

    @Test
    public void menuItem_restrictedIsSettable_soTheDefaultIsAChoiceNotALimit() {
        // Distinguishes "the API cannot express restricted" from "the API defaults to unrestricted".
        // Only the second is true, which is what makes it a decision worth reporting.
        assertTrue(MenuItemOEMV1.builder().setRestricted(true).build().isRestricted());
    }

    // ---------------------------------------------------------------------------------------
    // ContentListItemOEMV1 — 5 boolean fields plus icon type
    // ---------------------------------------------------------------------------------------

    @Test
    public void contentListItem_defaults_areExactlyTheseValues() {
        ContentListItemOEMV1 item =
                new ContentListItemOEMV1.Builder(ContentListItemOEMV1.Action.NONE).build();

        assertEquals("action", ContentListItemOEMV1.Action.NONE, item.getAction());
        assertNull("title", item.getTitle());
        assertNull("body", item.getBody());
        assertNull("icon", item.getIcon());
        assertEquals("primaryIconType",
                ContentListItemOEMV1.IconType.STANDARD, item.getPrimaryIconType());
        assertNull("supplementalIcon", item.getSupplementalIcon());
        assertFalse("checked", item.isChecked());
        assertTrue("enabled", item.isEnabled());
        assertFalse("activated", item.isActivated());
        assertFalse("actionDividerVisible", item.isActionDividerVisible());
        assertNull("onClickListener", item.getOnClickListener());
        assertNull("onCheckedChangeListener", item.getOnCheckedChangeListener());
        assertNull("supplementalIconOnClickListener",
                item.getSupplementalIconOnClickListener());
    }

    /**
     * REPORTED UPSTREAM — {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md}, the primary finding.
     *
     * <p>{@code SecureView} documents itself as secure by default ({@code mSecure = true}, with a
     * javadoc saying so, in <em>both</em> SecureView implementations in the tree). The adapters
     * push {@code item.isSecure()} onto it on every bind, so an item that never called
     * {@code setSecure(true)} silently downgrades the view to insecure — the default path, not a
     * failure path.
     *
     * <p>Unlike the {@code MenuItemOEMV1} case this one has no call site standing in front of it.
     */
    @Test
    public void contentListItem_isSecure_defaultsPermissive_reportedUpstream() {
        assertFalse("mIsSecure defaults false — see AOSP-SECURE-DEFAULT-REPORT.md",
                new ContentListItemOEMV1.Builder(ContentListItemOEMV1.Action.NONE)
                        .build().isSecure());
    }

    @Test
    public void contentListItem_secureIsSettable_soTheDefaultIsAChoiceNotALimit() {
        assertTrue(new ContentListItemOEMV1.Builder(ContentListItemOEMV1.Action.NONE)
                .setSecure(true).build().isSecure());
    }

    /**
     * The neighbour that shows the defaults were being chosen rather than accepted.
     *
     * <p>{@code mIsEnabled = true} is a deliberate non-Java-default three lines above
     * {@code mIsSecure = false} in the same initialiser block. This is the observation the upstream
     * report rests on — that the author was making decisions on those exact lines — so it is worth
     * an assertion rather than a comment: if {@code mIsEnabled} ever silently becomes {@code false},
     * the report's central argument has changed and someone should know.
     */
    @Test
    public void contentListItem_enabled_isTheDeliberateNonDefault() {
        assertTrue(new ContentListItemOEMV1.Builder(ContentListItemOEMV1.Action.NONE)
                .build().isEnabled());
    }

    // ---------------------------------------------------------------------------------------
    // TabOEMV1 — 4 builder fields
    // ---------------------------------------------------------------------------------------

    @Test
    public void tab_defaults_areExactlyTheseValues() {
        TabOEMV1 tab = TabOEMV1.builder().build();

        assertNull("title", tab.getTitle());
        assertNull("icon", tab.getIcon());
        assertNull("onSelectedListener", tab.getOnSelectedListener());
        assertTrue("tinted", tab.isTinted());
    }

    // ---------------------------------------------------------------------------------------
    // TextOEMV1 — 2 builder fields, both unbounded by default
    // ---------------------------------------------------------------------------------------

    @Test
    public void text_defaults_areUnbounded() {
        TextOEMV1 text = new TextOEMV1.Builder("hello").build();

        // Both default to MAX_VALUE, i.e. "no limit". Worth pinning because these are the two
        // fields a UX restriction would have to clamp: LIMIT_STRING_LENGTH acts on exactly this
        // surface, and an unbounded default is the permissive one.
        assertEquals("maxLines", Integer.MAX_VALUE, text.getMaxLines());
        assertEquals("maxChars", Integer.MAX_VALUE, text.getMaxChars());
    }

    @Test
    public void text_limitsAreSettable() {
        TextOEMV1 text = new TextOEMV1.Builder("hello").setMaxLines(2).setMaxChars(40).build();

        assertEquals(2, text.getMaxLines());
        assertEquals(40, text.getMaxChars());
    }
}
