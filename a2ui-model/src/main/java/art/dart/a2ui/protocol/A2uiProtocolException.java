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
package art.dart.a2ui.protocol;

import androidx.annotation.NonNull;

/**
 * A message the processor refused. {@link Category#INTEGRITY} is upstream's own name;
 * {@link Category#CATALOG} is this implementation's: a component outside the negotiated catalog
 * is refused, never silently dropped.
 */
public class A2uiProtocolException extends RuntimeException {

    /** Why the message was refused. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
    // (docs/api-surface/a2ui-model.txt), and the memory rationale does not apply at this scale.
    @SuppressWarnings("Enum")
    public enum Category { INTEGRITY, CATALOG, MALFORMED }

    private final Category mCategory;

    public A2uiProtocolException(@NonNull Category category, @NonNull String message) {
        super(message);
        mCategory = category;
    }

    @NonNull
    public Category getCategory() {
        return mCategory;
    }
}
