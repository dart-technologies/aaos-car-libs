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

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.model.ComponentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live state for one surface: its catalog, its components in insertion order, and its data model.
 *
 * <p>Mutated only by {@link A2uiMessageProcessor}, which is the only thing that has seen the
 * messages in order.
 */
public final class A2uiSurface {

    private static final int MAX_PROJECTION_DEPTH = 64;
    private static final int MAX_PROJECTED_COMPONENTS = 1_000;

    private final String mSurfaceId;
    private final String mVersion;
    private final String mCatalogId;
    private final Map<String, Object> mMetadata;
    private final boolean mSendDataModel;
    /** id -> component properties, insertion-ordered because updateComponents order is the render order. */
    private final Map<String, Map<String, Object>> mComponents = new LinkedHashMap<>();
    private final Map<String, Object> mDataModel = new LinkedHashMap<>();

    A2uiSurface(@NonNull String surfaceId, @NonNull String catalogId) {
        this(surfaceId, A2uiProtocolVersions.V09, catalogId, null, false);
    }

    A2uiSurface(@NonNull String surfaceId, @NonNull String version,
            @Nullable String catalogId, @Nullable Map<String, Object> metadata,
            boolean sendDataModel) {
        mSurfaceId = surfaceId;
        mVersion = version;
        mCatalogId = catalogId;
        mMetadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        mSendDataModel = sendDataModel;
    }

    /** Structural snapshot used to make a multi-message transaction reversible. */
    @SuppressWarnings("unchecked")
    A2uiSurface(@NonNull A2uiSurface source) {
        mSurfaceId = source.mSurfaceId;
        mVersion = source.mVersion;
        mCatalogId = source.mCatalogId;
        mMetadata = source.mMetadata;
        mSendDataModel = source.mSendDataModel;
        for (Map.Entry<String, Map<String, Object>> entry : source.mComponents.entrySet()) {
            mComponents.put(entry.getKey(),
                    (Map<String, Object>) deepCopy(entry.getValue()));
        }
        mDataModel.putAll((Map<String, Object>) deepCopy(source.mDataModel));
    }

    @NonNull public String getSurfaceId() { return mSurfaceId; }
    @NonNull public String getVersion() { return mVersion; }
    /**
     * The surface default catalog from {@code createSurface}, or null when a v1.0 surface was
     * created without one. Every component on such a surface must name its own catalog.
     */
    @Nullable public String getCatalogId() { return mCatalogId; }
    /** The validated surface {@code metadata} from a v1.0 {@code createSurface}. */
    @NonNull public Map<String, Object> getMetadata() { return mMetadata; }
    /** Whether the agent asked for data-model echo via {@code sendDataModel}. */
    public boolean getSendDataModel() { return mSendDataModel; }

    /**
     * An immutable deep snapshot of the components; a shallow view would let a caller rewrite an
     * action the policy then trusts.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getComponents() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : mComponents.entrySet()) {
            out.put(e.getKey(), (Map<String, Object>) deepImmutable(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** An immutable snapshot of the data model, nested structures included. */
    @NonNull
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataModel() {
        return (Map<String, Object>) deepImmutable(mDataModel);
    }

    /**
     * Adds or merges a component (upstream's fixtures update properties in place). Copies deeply,
     * so a sender keeping a reference cannot change the surface later.
     */
    void putComponent(@NonNull String id, @NonNull Map<String, Object> props) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deepCopy(props);
        Map<String, Object> existing = mComponents.get(id);
        if (existing == null) {
            mComponents.put(id, copy);
        } else {
            existing.putAll(copy);
        }
    }

    /**
     * A structural copy for JSON-shaped data: maps and lists are rebuilt, immutable scalars passed
     * through. Not a general deep cloner.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static Object deepCopy(@Nullable Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                out.put(e.getKey(), deepCopy(e.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) value) {
                out.add(deepCopy(o));
            }
            return out;
        }
        return value;
    }

    /** The same shape, wrapped unmodifiable at every level rather than only the outermost. */
    @Nullable
    @SuppressWarnings("unchecked")
    static Object deepImmutable(@Nullable Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                out.put(e.getKey(), deepImmutable(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) value) {
                out.add(deepImmutable(o));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    /**
     * Writes a JSON-pointer path such as {@code /user/name}, creating intermediate objects. A
     * pointer through a non-object is MALFORMED, never a silent overwrite. On v1.0 surfaces a
     * null value deletes the key at the path; pre-1.0 surfaces keep the historical store-a-null
     * behaviour.
     */
    @SuppressWarnings("unchecked")
    void writeDataModel(@NonNull String pointer, @Nullable Object value) {
        List<String> parts = parsePointer(pointer);
        if (parts.isEmpty()) {
            if (!(value instanceof Map)) {
                throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                        "the data model root must remain an object");
            }
            mDataModel.clear();
            mDataModel.putAll((Map<String, Object>) deepCopy(value));
            return;
        }
        Map<String, Object> node = mDataModel;
        for (int i = 0; i < parts.size() - 1; i++) {
            String part = parts.get(i);
            Object next = node.get(part);
            if (next == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                node.put(part, created);
                node = created;
            } else if (next instanceof Map) {
                node = (Map<String, Object>) next;
            } else {
                throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                        "data model path " + pointer + " traverses a non-object at '"
                                + part + "'");
            }
        }
        String leaf = parts.get(parts.size() - 1);
        if (value == null && A2uiProtocolVersions.isV1(mVersion)) {
            node.remove(leaf);
            return;
        }
        node.put(leaf, deepCopy(value));
    }

    /** Reads a JSON-pointer path, or null if absent. */
    @Nullable
    @SuppressWarnings("unchecked")
    public Object readDataModel(@NonNull String pointer) {
        List<String> parts = parsePointer(pointer);
        Object node = mDataModel;
        for (String part : parts) {
            if (!(node instanceof Map)) {
                throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                        "data model path " + pointer + " traverses a non-object at '"
                                + part + "'");
            }
            Map<String, Object> object = (Map<String, Object>) node;
            if (!object.containsKey(part)) {
                return null;
            }
            node = object.get(part);
        }
        return deepImmutable(node);
    }

    /** Parses an RFC 6901 JSON pointer. URI-fragment pointers are intentionally unsupported. */
    @NonNull
    private static List<String> parsePointer(@NonNull String pointer) {
        if (pointer.isEmpty()) {
            return Collections.emptyList();
        }
        if (!pointer.startsWith("/")) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                    "data model path must be an RFC 6901 JSON pointer: " + pointer);
        }
        String[] encoded = pointer.substring(1).split("/", -1);
        List<String> decoded = new ArrayList<>(encoded.length);
        for (String token : encoded) {
            StringBuilder out = new StringBuilder(token.length());
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c != '~') {
                    out.append(c);
                    continue;
                }
                if (++i >= token.length()) {
                    throw malformedPointer(pointer);
                }
                char escape = token.charAt(i);
                if (escape == '0') {
                    out.append('~');
                } else if (escape == '1') {
                    out.append('/');
                } else {
                    throw malformedPointer(pointer);
                }
            }
            decoded.add(out.toString());
        }
        return decoded;
    }

    private static A2uiProtocolException malformedPointer(String pointer) {
        return new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                "data model path has an invalid JSON pointer escape: " + pointer);
    }

    /**
     * Projects this surface into the renderer-facing tree. The policy prunes this projection, not
     * protocol state, so a relaxation re-projects without another round trip. Values of the form
     * {@code {"path": "/user/name"}} resolve against the data model.
     */
    @NonNull
    public A2uiComponent project(@NonNull String rootId) {
        Map<String, Object> root = mComponents.get(rootId);
        if (root == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "surface " + mSurfaceId + " has no component with id " + rootId);
        }
        return projectOne(rootId, root, new HashSet<>(), 1, new int[]{0});
    }

    @SuppressWarnings("unchecked")
    private A2uiComponent projectOne(String id, Map<String, Object> props, Set<String> seen,
            int depth, int[] expanded) {
        if (depth > MAX_PROJECTION_DEPTH) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "component graph exceeds maximum depth " + MAX_PROJECTION_DEPTH);
        }
        if (expanded[0] >= MAX_PROJECTED_COMPONENTS) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "component graph exceeds maximum size " + MAX_PROJECTED_COMPONENTS);
        }
        if (!seen.add(id)) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "component graph repeats or cycles through " + id);
        }
        expanded[0]++;

        ComponentType type = ComponentType.fromCatalogName((String) props.get("component"));
        if (type == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.CATALOG,
                    "component " + id + " has type " + props.get("component")
                            + ", which the negotiated catalog does not define");
        }
        A2uiComponent.Builder builder = new A2uiComponent.Builder(type)
                .setId(id)
                .setTitle(resolve(props.get("title")))
                .setSubtitle(resolve(props.get("subtitle")))
                .setAction(resolve(props.get("action")))
                .setVoicePhrase(resolve(props.get("voicePhrase")))
                .setActionClass(declaredClass(id, props.get("actionClass")));

        Object children = props.get("children");
        if (children instanceof List) {
            for (Object childId : (List<Object>) children) {
                Map<String, Object> child = mComponents.get(String.valueOf(childId));
                if (child == null) {
                    throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                            "component " + id + " references unknown child " + childId);
                }
                builder.addChild(projectOne(String.valueOf(childId), child, seen,
                        depth + 1, expanded));
            }
        }
        return builder.build();
    }

    /**
     * The action this component carries, resolved exactly as the projection does, so a dispatch is
     * validated against the rendered surface.
     *
     * @throws A2uiProtocolException if the component does not exist or carries no action
     */
    @NonNull
    public String requireDeclaredAction(@NonNull String componentId) {
        Map<String, Object> props = mComponents.get(componentId);
        if (props == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "surface " + mSurfaceId + " has no component with id " + componentId);
        }
        String action = resolve(props.get("action"));
        if (action == null) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.INTEGRITY,
                    "component " + componentId + " carries no action");
        }
        return action;
    }

    /** The action class the surface declares for this component, or null if it declares none. */
    @Nullable
    public A2uiActionClass declaredActionClass(@NonNull String componentId) {
        Map<String, Object> props = mComponents.get(componentId);
        return props == null ? null : declaredClass(componentId, props.get("actionClass"));
    }

    /**
     * Reads the agent's declared action class. A malformed one fails the message: treating a typo
     * as "not declared" could downgrade an intended raise to SAFE.
     */
    @Nullable
    private A2uiActionClass declaredClass(String id, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return A2uiActionClass.valueOf(String.valueOf(value).trim());
        } catch (IllegalArgumentException e) {
            throw new A2uiProtocolException(A2uiProtocolException.Category.MALFORMED,
                    "component " + id + " declares actionClass " + value
                            + ", which is not an action class");
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private String resolve(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Object pointer = ((Map<String, Object>) value).get("path");
            if (pointer instanceof String) {
                Object resolved = readDataModel((String) pointer);
                return resolved == null ? null : String.valueOf(resolved);
            }
        }
        return String.valueOf(value);
    }
}
