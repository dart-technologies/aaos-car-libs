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
package com.android.car.media.common.source;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.session.MediaBrowser;

import com.android.car.media.common.source.MediaBrowserConnector.BrowserEventListener;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link BrowsingState}s that can deliver browse pushes, for tests in other packages.
 *
 * <p>This class exists here, in test sources but in the PRODUCTION class's package, for one
 * reason: {@code BrowserEventRegistrar} is package-private, and so is the four-argument
 * {@code BrowsingState} constructor that takes one. The visible-for-testing constructor deliberately
 * passes {@code null} for the registrar, which makes {@code addBrowserEventListener} a silent no-op
 * — correct for a state with no connection behind it, and useless for testing the push path, since
 * a repository that registered nothing and a repository whose pushes were dropped would look
 * identical.
 *
 * <p>The alternative was widening production visibility so a test in another package could reach
 * them. That was rejected: the flip already changed this library's public surface and the audit
 * flagged the absence of a downstream compatibility gate for it, so adding more public API to buy
 * test access would enlarge exactly the problem that is already outstanding. A test-source class in
 * the same package buys the same access and ships to nobody.
 */
public final class TestBrowsingStates {

    private TestBrowsingStates() {
    }

    /**
     * Collects the listeners a {@link BrowsingState} is handed, so a test can fire pushes at them.
     *
     * <p>{@link #listenerCount()} is exposed because "the push was ignored" and "nothing was ever
     * registered to receive it" are different facts with the same visible outcome, and a test that
     * cannot tell them apart would pass against a repository that registers nothing at all.
     */
    public static final class Pushes implements MediaBrowserConnector.BrowserEventRegistrar {

        private final List<BrowserEventListener> mListeners = new ArrayList<>();

        @Override
        public void addBrowserEventListener(@NonNull BrowserEventListener listener) {
            mListeners.add(listener);
        }

        /** How many listeners registered against this connection. */
        public int listenerCount() {
            return mListeners.size();
        }

        /** Delivers what the browse service's {@code notifyChildrenChanged} would deliver. */
        public void childrenChanged(@NonNull String parentId) {
            for (BrowserEventListener listener : new ArrayList<>(mListeners)) {
                listener.onChildrenChanged(parentId);
            }
        }

        /** Delivers a search-results-changed push. */
        public void searchResultChanged(@NonNull String query) {
            for (BrowserEventListener listener : new ArrayList<>(mListeners)) {
                listener.onSearchResultChanged(query);
            }
        }
    }

    /** A {@link BrowsingState} whose {@code addBrowserEventListener} actually registers. */
    public static BrowsingState with(@NonNull MediaSource source, @Nullable MediaBrowser browser,
            @NonNull ConnectionStatus status, @NonNull Pushes pushes) {
        return new BrowsingState(source, browser, status, pushes);
    }
}
