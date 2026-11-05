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
package art.dart.a2ui.demo;

import androidx.annotation.NonNull;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

/** The one surface both hosts render, shared so the two renderers cannot drift apart unnoticed. */
public final class DemoSurface {

    private DemoSurface() {
    }

    /**
     * The showcase surface, produced by processing {@link DemoTrace}. Rooted at {@code Column},
     * not {@code List}: a pruned root takes its subtree with it, and List is not glanceable.
     * {@code A2uiPrunerTest} pins both.
     */
    @NonNull
    public static A2uiComponent create() {
        return DemoTrace.project();
    }
}
