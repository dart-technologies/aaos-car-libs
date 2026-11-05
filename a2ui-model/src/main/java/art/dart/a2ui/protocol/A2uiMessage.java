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
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One A2UI lifecycle message, in the shapes of upstream's conformance fixtures
 * (<a href="https://github.com/a2ui-project/a2ui">a2ui-project/a2ui</a>,
 * {@code conformance/core/message_processor.yaml} at v1-candidate {@code bb4600b}):
 *
 * <pre>{@code
 * { version: "v0.9", createSurface:    { surfaceId, catalogId, theme?, sendDataModel? } }
 * { version: "v0.9", updateComponents: { surfaceId, components: [ { id, component, ...props } ] } }
 * { version: "v0.9", updateDataModel:  { surfaceId, path: "/user/name", value } }
 * { version: "v0.9", deleteSurface:    { surfaceId } }
 * }</pre>
 *
 * <p>A protocol type, distinct from the renderer-facing {@code A2uiComponent}.
 */
public final class A2uiMessage {

    /** The lifecycle verbs this implementation understands. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
    // (docs/api-surface/a2ui-model.txt), and the memory rationale does not apply at this scale.
    @SuppressWarnings("Enum")
    public enum Kind { CREATE_SURFACE, UPDATE_COMPONENTS, UPDATE_DATA_MODEL, DELETE_SURFACE }

    private final Kind mKind;
    private final String mVersion;
    private final String mSurfaceId;
    private final String mCatalogId;
    private final List<Map<String, Object>> mComponents;
    private final String mPath;
    private final Object mValue;

    private A2uiMessage(Kind kind, String version, String surfaceId, String catalogId,
            List<Map<String, Object>> components, String path, Object value) {
        mKind = kind;
        mVersion = version;
        mSurfaceId = surfaceId;
        mCatalogId = catalogId;
        mComponents = components == null ? Collections.emptyList()
                : Collections.unmodifiableList(components);
        mPath = path;
        mValue = value;
    }

    @NonNull
    public static A2uiMessage createSurface(
            @NonNull String version, @NonNull String surfaceId, @NonNull String catalogId) {
        return new A2uiMessage(Kind.CREATE_SURFACE, version, surfaceId, catalogId, null, null, null);
    }

    @NonNull
    public static A2uiMessage updateComponents(@NonNull String version, @NonNull String surfaceId,
            @NonNull List<Map<String, Object>> components) {
        return new A2uiMessage(
                Kind.UPDATE_COMPONENTS, version, surfaceId, null, components, null, null);
    }

    @NonNull
    public static A2uiMessage updateDataModel(@NonNull String version, @NonNull String surfaceId,
            @NonNull String path, @Nullable Object value) {
        return new A2uiMessage(
                Kind.UPDATE_DATA_MODEL, version, surfaceId, null, null, path, value);
    }

    @NonNull
    public static A2uiMessage deleteSurface(@NonNull String version, @NonNull String surfaceId) {
        return new A2uiMessage(Kind.DELETE_SURFACE, version, surfaceId, null, null, null, null);
    }

    @NonNull public Kind getKind() { return mKind; }
    @NonNull public String getVersion() { return mVersion; }
    @NonNull public String getSurfaceId() { return mSurfaceId; }
    @Nullable public String getCatalogId() { return mCatalogId; }
    @NonNull public List<Map<String, Object>> getComponents() { return mComponents; }
    @Nullable public String getPath() { return mPath; }
    @Nullable public Object getValue() { return mValue; }

    /** A component entry, as a mutable map, for building traces in tests and fixtures. */
    @NonNull
    public static Map<String, Object> component(@NonNull String id, @NonNull String component) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("component", component);
        return m;
    }

    @Override
    public String toString() {
        return mKind + "(" + mSurfaceId + (mPath != null ? " " + mPath : "") + ")";
    }
}
