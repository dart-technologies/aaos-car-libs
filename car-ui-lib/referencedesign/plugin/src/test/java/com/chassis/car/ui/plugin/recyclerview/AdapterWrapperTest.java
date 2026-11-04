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
package com.chassis.car.ui.plugin.recyclerview;

import static com.google.common.truth.Truth.assertThat;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.plugin.oemapis.recyclerview.AdapterDataObserverOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AdapterWrapper}'s observer bookkeeping — the plugin's first adapter-layer test.
 *
 * <p>The plugin is 3,736 SLOC with 23 tests covering three files, so this layer had none. That is
 * how a copy-paste inversion survived in it: {@code unregisterAdapterDataObserver} called
 * {@code mAdapter.registerAdapterDataObserver}, so every detach added ANOTHER plugin-side observer
 * to the app's adapter instead of removing the one that was there.
 *
 * <p>The correct form is three files away, in car-ui-lib's
 * {@code DelegatingContentLimitingAdapter}, which has the identical shape and calls
 * {@code unregisterAdapterDataObserver}. This is the copy that drifted.
 *
 * <p><b>Why it is worse than an ordinary leak.</b> The retaining object lives in the PLUGIN's
 * ClassLoader — {@code PluginFactorySingleton} loads the plugin APK into the host app's process
 * with its own loader — so nothing in the app's lifecycle can release it, and it is invisible to an
 * app-side leak trace. Because the observers accumulate rather than merely persisting,
 * notifications multiply: the symptom is a list that updates several times per change, in an app
 * the OEM did not write.
 */
@RunWith(RobolectricTestRunner.class)
public class AdapterWrapperTest {

    /** Records the plugin-side observers this adapter is asked to hold. */
    private static final class RecordingAdapter implements AdapterOEMV1<ViewHolderOEMV1> {
        final List<AdapterDataObserverOEMV1> mObservers = new ArrayList<>();

        @Override
        public void registerAdapterDataObserver(AdapterDataObserverOEMV1 observer) {
            mObservers.add(observer);
        }

        @Override
        public void unregisterAdapterDataObserver(AdapterDataObserverOEMV1 observer) {
            mObservers.remove(observer);
        }

        @Override
        public int getItemCount() {
            return 0;
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }

        @Override
        public int getStateRestorationPolicyInt() {
            return 0;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public ViewHolderOEMV1 createViewHolder(android.view.ViewGroup parent, int viewType) {
            return null;
        }

        @Override
        public void bindViewHolder(ViewHolderOEMV1 holder, int position) {
        }

        @Override
        public void onAttachedToRecyclerView(RecyclerViewOEMV1 recyclerView) {
        }

        @Override
        public void onDetachedFromRecyclerView(RecyclerViewOEMV1 recyclerView) {
        }

        // The remainder of AdapterOEMV1, implemented inertly. Listed explicitly rather than hidden
        // behind an abstract base: this is the OEM contract, and a version bump that ADDS a method
        // should break this file loudly rather than be absorbed by a default.
        @Override
        public void onViewAttachedToWindow(ViewHolderOEMV1 holder) {
        }

        @Override
        public void onViewDetachedFromWindow(ViewHolderOEMV1 holder) {
        }

        @Override
        public void onViewRecycled(ViewHolderOEMV1 holder) {
        }

        @Override
        public void setMaxItems(int maxItems) {
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean onFailedToRecycleView(ViewHolderOEMV1 holder) {
            return false;
        }
    }

    private static RecyclerView.AdapterDataObserver observer() {
        return new RecyclerView.AdapterDataObserver() {
        };
    }

    @Test
    public void detachingTheLastObserverRELEASESThePluginSideOne_ratherThanAddingAnother() {
        RecordingAdapter oem = new RecordingAdapter();
        AdapterWrapper wrapper = new AdapterWrapper(oem);
        RecyclerView.AdapterDataObserver app = observer();

        wrapper.registerAdapterDataObserver(app);
        assertThat(oem.mObservers).hasSize(1);

        wrapper.unregisterAdapterDataObserver(app);

        // The defect made this 2, not 0 -- and 3 after the next cycle, and so on.
        assertThat(oem.mObservers).isEmpty();
    }

    @Test
    public void repeatedAttachDetachCyclesDoNotAccumulate() {
        // The consequence rather than the mechanism. One cycle looks like an off-by-one; what makes
        // it matter is that a RecyclerView attaching and detaching across configuration changes
        // does this many times, and each one multiplies every subsequent notification.
        RecordingAdapter oem = new RecordingAdapter();
        AdapterWrapper wrapper = new AdapterWrapper(oem);

        for (int i = 0; i < 20; i++) {
            RecyclerView.AdapterDataObserver app = observer();
            wrapper.registerAdapterDataObserver(app);
            wrapper.unregisterAdapterDataObserver(app);
        }

        assertThat(oem.mObservers).isEmpty();
    }

    @Test
    public void aSecondAppObserverDoesNotAddASecondPluginSideObserver() {
        // Anti-vacuity in the other direction: the plugin-side observer is registered once for the
        // FIRST app observer and released only when the LAST one goes. A wrapper that registered
        // per-observer would pass both tests above and still be wrong.
        RecordingAdapter oem = new RecordingAdapter();
        AdapterWrapper wrapper = new AdapterWrapper(oem);
        RecyclerView.AdapterDataObserver first = observer();
        RecyclerView.AdapterDataObserver second = observer();

        wrapper.registerAdapterDataObserver(first);
        wrapper.registerAdapterDataObserver(second);
        assertThat(oem.mObservers).hasSize(1);

        wrapper.unregisterAdapterDataObserver(first);
        assertThat(oem.mObservers).hasSize(1);   // still one app observer left

        wrapper.unregisterAdapterDataObserver(second);
        assertThat(oem.mObservers).isEmpty();
    }
}
