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

import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.recyclerview.CarUiListItemAdapter;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.recyclerview.ContentLimiting;
import com.android.car.ui.recyclerview.DelegatingContentLimitingAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code car-ui-lib} has two content-limiting mechanisms, unrelated by type.
 *
 * <table>
 *   <caption>The two mechanisms</caption>
 *   <tr><th></th><th>{@code CarUiRecyclerView.ItemCap}</th><th>{@code ContentLimiting}</th></tr>
 *   <tr><td>driven by</td><td>{@code CarUiRecyclerViewImpl}, automatically</td>
 *       <td>{@code UxrContentLimiter}, only after {@code setAdapter} AND {@code start()}</td></tr>
 *   <tr><td>implemented by</td><td>{@code CarUiListItemAdapter}</td>
 *       <td>{@code ContentLimitingAdapter} and its delegating subclass</td></tr>
 *   <tr><td>on truncation</td><td><b>silent</b></td><td>appends a "scrolling limited" row</td></tr>
 * </table>
 *
 * <p>Because they are unrelated, a {@code ContentLimiting} adapter loses the automatic
 * {@code ItemCap} path: an app that opts in and forgets {@code start()} gets no limiting at all.
 */
@RunWith(RobolectricTestRunner.class)
public class ContentLimitMechanismTest {

    private static List<CarUiListItem> items(int count) {
        List<CarUiListItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CarUiContentListItem item =
                    new CarUiContentListItem(CarUiContentListItem.Action.NONE);
            item.setTitle("row " + i);
            items.add(item);
        }
        return items;
    }

    /** Two interfaces, same package, signature and constant, with no type relationship. */
    @Test
    public void theTwoLimitingInterfacesAreUnrelatedTypes() {
        assertThat(CarUiRecyclerView.ItemCap.class.isAssignableFrom(ContentLimiting.class))
                .isFalse();
        assertThat(ContentLimiting.class.isAssignableFrom(CarUiRecyclerView.ItemCap.class))
                .isFalse();
    }

    /** …yet they declare the same method and the same sentinel. */
    @Test
    public void theTwoLimitingInterfacesDeclareTheSameContract() throws NoSuchMethodException {
        Method itemCap = CarUiRecyclerView.ItemCap.class.getMethod("setMaxItems", int.class);
        Method contentLimiting = ContentLimiting.class.getMethod("setMaxItems", int.class);

        assertThat(itemCap.getName()).isEqualTo(contentLimiting.getName());
        assertThat(CarUiRecyclerView.ItemCap.UNLIMITED).isEqualTo(ContentLimiting.UNLIMITED);
        assertThat(CarUiRecyclerView.ItemCap.UNLIMITED).isEqualTo(-1);
    }

    /** The default adapter is an ItemCap, capped by the view itself on every restriction change. */
    @Test
    public void defaultAdapterIsItemCap_soTheRecyclerViewLimitsItAutomatically() {
        CarUiListItemAdapter adapter = new CarUiListItemAdapter(items(6));

        assertThat(adapter).isInstanceOf(CarUiRecyclerView.ItemCap.class);
        assertThat(adapter).isNotInstanceOf(ContentLimiting.class);
    }

    @Test
    public void shrinkingDelegate_recomputesLimitedRangeBeforeRecyclerQueriesOldPosition() {
        List<CarUiListItem> mutable = items(9);
        CarUiListItemAdapter delegate = new CarUiListItemAdapter(mutable);
        DelegatingContentLimitingAdapter<?> limited =
                new DelegatingContentLimitingAdapter<>(delegate, 0);
        limited.setMaxItems(9);

        mutable.clear();
        mutable.addAll(items(2));
        limited.updateUnderlyingDataChanged(mutable.size(), 0);

        assertThat(limited.getItemCount()).isEqualTo(2);
        assertThat(limited.getItemViewType(0)).isEqualTo(delegate.getItemViewType(0));
        assertThat(limited.getItemViewType(1)).isEqualTo(delegate.getItemViewType(1));
    }

    /**
     * The opt-in adapter is not an {@code ItemCap}, so the view's automatic path skips it: unlimited
     * until {@code UxrContentLimiter.start()}.
     */
    @Test
    public void optInAdapterIsNotItemCap_soTheAutomaticPathSkipsIt() {
        DelegatingContentLimitingAdapter<?> wrapped = new DelegatingContentLimitingAdapter<>(
                new CarUiListItemAdapter(items(6)), 0);

        assertThat(wrapped).isInstanceOf(ContentLimiting.class);
        assertThat(wrapped).isNotInstanceOf(CarUiRecyclerView.ItemCap.class);
    }

    /**
     * Both cap at three: ItemCap reports three rows, ContentLimiting four, the extra being the
     * withheld-content message.
     */
    @Test
    public void itemCapTruncatesSilently_whileContentLimitingAddsAMessageRow() {
        CarUiListItemAdapter itemCap = new CarUiListItemAdapter(items(6));
        DelegatingContentLimitingAdapter<?> contentLimiting =
                new DelegatingContentLimitingAdapter<>(new CarUiListItemAdapter(items(6)), 0);

        itemCap.setMaxItems(3);
        contentLimiting.setMaxItems(3);

        assertThat(itemCap.getItemCount()).isEqualTo(3);
        assertThat(contentLimiting.getItemCount()).isEqualTo(4);
    }

    /**
     * Both honour {@code UNLIMITED} and start unlimited, which is why a forgotten {@code start()}
     * fails open silently.
     */
    @Test
    public void bothAreUnlimitedUntilToldOtherwise() {
        CarUiListItemAdapter itemCap = new CarUiListItemAdapter(items(6));
        DelegatingContentLimitingAdapter<?> contentLimiting =
                new DelegatingContentLimitingAdapter<>(new CarUiListItemAdapter(items(6)), 0);

        assertThat(itemCap.getItemCount()).isEqualTo(6);
        assertThat(contentLimiting.getItemCount()).isEqualTo(6);

        itemCap.setMaxItems(CarUiRecyclerView.ItemCap.UNLIMITED);
        contentLimiting.setMaxItems(ContentLimiting.UNLIMITED);

        assertThat(itemCap.getItemCount()).isEqualTo(6);
        assertThat(contentLimiting.getItemCount()).isEqualTo(6);
    }
}
