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
package art.dart.a2ui.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The eighteen components of the A2UI v1.0 basic catalog, spelled as the catalog spells them so
 * {@link #catalogName()} needs no translation. {@code A2uiCatalogContractTest} fails if this set
 * and {@code a2ui-automotive/conformance/automotive.yaml} diverge.
 */
// metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
// (docs/api-surface/a2ui-model.txt), and catalogName() is an instance method.
@SuppressWarnings("Enum")
public enum ComponentType {
    TEXT("Text"),
    IMAGE("Image"),
    ICON("Icon"),
    VIDEO("Video"),
    AUDIO_PLAYER("AudioPlayer"),
    ROW("Row"),
    COLUMN("Column"),
    LIST("List"),
    CARD("Card"),
    TABS("Tabs"),
    MODAL("Modal"),
    DIVIDER("Divider"),
    BUTTON("Button"),
    TEXT_FIELD("TextField"),
    CHECK_BOX("CheckBox"),
    CHOICE_PICKER("ChoicePicker"),
    SLIDER("Slider"),
    DATE_TIME_INPUT("DateTimeInput");

    private final String mCatalogName;

    ComponentType(String catalogName) {
        mCatalogName = catalogName;
    }

    /** The name this component is written as in the A2UI catalog and conformance YAML. */
    @NonNull
    public String catalogName() {
        return mCatalogName;
    }

    /**
     * Resolves a catalog name, or null if it is not one of the eighteen; never a lenient fallback
     * ({@code test_automotive_out_of_catalog_component_is_not_reintroduced}).
     */
    @Nullable
    public static ComponentType fromCatalogName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (ComponentType type : values()) {
            if (type.mCatalogName.equals(name)) {
                return type;
            }
        }
        return null;
    }
}
