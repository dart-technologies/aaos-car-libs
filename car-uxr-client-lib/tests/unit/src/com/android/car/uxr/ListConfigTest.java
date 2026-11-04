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

package com.android.car.uxr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.android.car.uxr.CarUxRestrictionsAppConfig.ListConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Builder-default assertions for {@link ListConfig} — the same audit run over {@code oem-apis},
 * applied here.
 *
 * <p>The interesting result is that this Builder <b>passes</b> the audit where the {@code oem-apis}
 * ones fail, and passes it by construction rather than by luck: both optional fields are declared
 * as nullable {@link Integer} rather than {@code int}, so "not specified" is a representable state
 * distinct from "specified as 0".
 *
 * <p>That is precisely option 2 of {@code docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md} — recover the
 * slot rather than pick a value — already idiomatic in this codebase. It is worth pinning for two
 * reasons: so the good pattern cannot silently regress to a primitive, and because a reviewer of
 * that report can be pointed at a working example inside the same tree rather than at a proposal.
 */
@RunWith(JUnit4.class)
public class ListConfigTest {

    private static final int ID = 7;

    @Test
    public void unsetOptionalFields_areNull_notZero() {
        ListConfig config = ListConfig.builder(ID).build();

        assertEquals(ID, config.getId());
        // The distinction the primitive would destroy: null means "app said nothing", and
        // UxrContentLimiterImpl falls back to the car's cap on exactly this check.
        assertNull("contentLimit", config.getContentLimit());
        assertNull("scrollingLimitedMessageResId", config.getScrollingLimitedMessageResId());
    }

    @Test
    public void explicitZero_isDistinguishableFromUnset() {
        ListConfig unset = ListConfig.builder(ID).build();
        ListConfig zero = ListConfig.builder(ID).setContentLimit(0).build();

        assertNull(unset.getContentLimit());
        assertEquals(Integer.valueOf(0), zero.getContentLimit());
    }

    @Test
    public void setValues_roundTrip() {
        ListConfig config = ListConfig.builder(ID)
                .setContentLimit(5)
                .setScrollingLimitedMessageResId(0x7f010002)
                .build();

        assertEquals(ID, config.getId());
        assertEquals(Integer.valueOf(5), config.getContentLimit());
        assertEquals(Integer.valueOf(0x7f010002), config.getScrollingLimitedMessageResId());
    }
}
