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

import static com.google.common.truth.Truth.assertThat;

import android.graphics.drawable.ColorDrawable;

import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.recyclerview.CarUiListItemAdapter;
import com.android.car.ui.recyclerview.DelegatingContentLimitingAdapter;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The rules the keyed diff and the host cards rest on, without an Activity. */
@RunWith(RobolectricTestRunner.class)
public class HostRowsTest {

    /** A diff dispatched through a cap or a "limited" row would be inconsistent; refuse it. */
    @Test
    public void onlyAOneToOneListMayTakeADiff() {
        List<CarUiListItem> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CarUiContentListItem row = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
            row.setTitle("row " + i);
            rows.add(row);
        }
        CarUiListItemAdapter plain = new CarUiListItemAdapter(rows);
        DelegatingContentLimitingAdapter<?> limited =
                new DelegatingContentLimitingAdapter<>(new CarUiListItemAdapter(rows), 1);
        int message = limited.getScrollingLimitedMessageViewType();
        assertThat(HostRows.exposesAll(plain, 4, message)).isTrue();
        assertThat(HostRows.exposesAll(limited, 4, message)).isTrue();

        // Four rows under a cap of three is still four positions; the last is the message.
        limited.setMaxItems(3);
        assertThat(limited.getItemCount()).isEqualTo(4);
        assertThat(HostRows.exposesAll(limited, 4, message)).isFalse();
        plain.setMaxItems(3);
        assertThat(HostRows.exposesAll(plain, 4, message)).isFalse();

        limited.setMaxItems(-1);
        assertThat(HostRows.exposesAll(limited, 4, message)).isTrue();
    }

    @Test
    public void cardsClampToTheBudgetAndShedArtWhenImagesAreWithheld() {
        A2uiValueLimits limits = A2uiValueLimits.from(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH, 10, 0, 0);
        CarUiContentListItem card = HostRows.card(A2uiRestrictionPolicy.failClosedComponents(),
                limits, "DEMO order confirmed · preparing",
                Collections.singletonList("Ready in about 6 min"),
                new ColorDrawable(0), new ColorDrawable(1));

        // Ten characters including the ellipsis, A2uiPruner's rule.
        assertThat(card.getTitle().getPreferredText().toString()).isEqualTo("DEMO orde…");
        assertThat(card.getPrimaryIconType()).isEqualTo(CarUiContentListItem.IconType.STANDARD);
        assertThat(card.getOnClickListener()).isNull();
        assertThat(card.getAction()).isEqualTo(CarUiContentListItem.Action.NONE);

        CarUiContentListItem parked = HostRows.card(A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE),
                A2uiValueLimits.unrestricted(), "Welcome", Collections.emptyList(),
                new ColorDrawable(0), null);
        assertThat(parked.getPrimaryIconType()).isEqualTo(CarUiContentListItem.IconType.CONTENT);
        assertThat(HostRows.card(A2uiRestrictionPolicy.failClosedComponents(),
                limits, null, Collections.emptyList(), null, null)).isNull();
    }

    /** Listener identity changes every frame; whether there is one does not, and must count. */
    @Test
    public void aSignatureSeesWhetherARowCanBeActedOnButNotWhichLambda() {
        CarUiContentListItem a = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        a.setTitle("Play");
        a.setOnItemClickedListener(ignored -> { });
        CarUiContentListItem b = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        b.setTitle("Play");
        b.setOnItemClickedListener(ignored -> { });
        CarUiContentListItem refused = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        refused.setTitle("Play");

        assertThat(HostRows.signature(a)).isEqualTo(HostRows.signature(b));
        assertThat(HostRows.signature(a)).isNotEqualTo(HostRows.signature(refused));
    }
}
