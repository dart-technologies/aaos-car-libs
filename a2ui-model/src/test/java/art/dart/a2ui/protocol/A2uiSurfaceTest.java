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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionClass;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link A2uiSurface}'s rejection paths. Notably
 * {@link #aTypoedActionClassIsRefused_ratherThanReadAsNotDeclared}: a typo read as "not declared"
 * would let an intended raise fall back to SAFE.
 */
public class A2uiSurfaceTest {

    private static final String CATALOG = "automotive";

    private static A2uiSurface surface() {
        return new A2uiSurface("s1", CATALOG);
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------------------------------
    // Identity.

    @Test
    public void aSurfaceReportsTheIdAndCatalogItWasCreatedWith() {
        A2uiSurface s = surface();
        assertThat(s.getSurfaceId()).isEqualTo("s1");
        assertThat(s.getCatalogId()).isEqualTo(CATALOG);
    }

    // The data model: a pointer is a JSON pointer or a malformed message, never read leniently.

    @Test
    public void aWriteToAPathThatIsNotAJsonPointerIsMalformed() {
        A2uiSurface s = surface();
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.writeDataModel("user/name", "ada"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
        // ...and it wrote nothing on the way out.
        assertThat(s.readDataModel("/user/name")).isNull();
    }

    @Test
    public void aReadOfAPathThatIsNotAJsonPointerIsMalformed() {
        A2uiSurface s = surface();
        s.writeDataModel("/user/name", "ada");
        assertThrows(A2uiProtocolException.class, () -> s.readDataModel("user/name"));
        assertThat(s.readDataModel("/user/name")).isEqualTo("ada");
    }

    @Test
    public void aReadThatTraversesThroughANonObjectIsMalformed() {
        A2uiSurface s = surface();
        s.writeDataModel("/user", "ada");
        // /user is a String, so /user/name traverses through a non-map.
        assertThrows(A2uiProtocolException.class, () -> s.readDataModel("/user/name"));
    }

    @Test
    public void jsonPointerEscapesAndEmptySegmentsRoundTrip() {
        A2uiSurface s = surface();
        s.writeDataModel("/a~1b/~0key/", "value");

        assertThat(s.readDataModel("/a~1b/~0key/")).isEqualTo("value");
        assertThat(s.getDataModel()).containsKey("a/b");
    }

    @Test
    public void malformedJsonPointerEscapeIsRejectedOnReadAndWrite() {
        A2uiSurface s = surface();
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.writeDataModel("/bad~2key", "value"));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
        assertThrows(A2uiProtocolException.class, () -> s.readDataModel("/bad~2key"));
    }

    @Test
    public void rootPointerReplacesTheObjectButRejectsScalarRoots() {
        A2uiSurface s = surface();
        s.writeDataModel("/old", "value");
        s.writeDataModel("", props("new", Arrays.asList("a", "b")));

        assertThat(s.readDataModel("/old")).isNull();
        assertThat((List<?>) s.readDataModel("/new")).containsExactly("a", "b").inOrder();
        assertThrows(A2uiProtocolException.class, () -> s.writeDataModel("", "scalar"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dataModelWritesCopyInputAndReadsReturnImmutableSnapshots() {
        A2uiSurface s = surface();
        Map<String, Object> sent = props("items", new java.util.ArrayList<>(Arrays.asList("a")));
        s.writeDataModel("/payload", sent);
        ((List<Object>) sent.get("items")).add("mutated");

        Map<String, Object> read = (Map<String, Object>) s.readDataModel("/payload");
        assertThat((List<?>) read.get("items")).containsExactly("a");
        assertThrows(UnsupportedOperationException.class, () -> read.put("x", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) read.get("items")).add("x"));
    }

    // ---------------------------------------------------------------------------------------
    // Projection.

    @Test
    public void projectingARootTheSurfaceDoesNotHaveIsAnIntegrityFailure() {
        A2uiSurface s = surface();
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("missing"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        assertThat(e).hasMessageThat().contains("missing");
    }

    @Test
    public void projectingAComponentWhoseTypeIsNotInTheCatalogIsACatalogFailure() {
        A2uiSurface s = surface();
        // Ingest validates the type; this asserts the second refusal, at projection, in case ingest
        // is ever relaxed.
        s.putComponent("root", props("id", "root", "component", "Hologram"));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("root"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
        assertThat(e).hasMessageThat().contains("Hologram");
    }

    @Test
    public void aComponentReferencingAChildTheSurfaceDoesNotHaveIsAnIntegrityFailure() {
        A2uiSurface s = surface();
        // "Column": "Container" is not a catalog type and would yield CATALOG, not INTEGRITY.
        s.putComponent("root", props("id", "root", "component", "Column",
                "children", Collections.singletonList("ghost")));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("root"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        assertThat(e).hasMessageThat().contains("ghost");
    }

    @Test
    public void anUnknownTypeIsRefusedBeforeItsChildrenAreEvenLookedAt() {
        A2uiSurface s = surface();
        // Both defects at once: the catalog refusal must win over the missing child.
        s.putComponent("root", props("id", "root", "component", "Hologram",
                "children", Collections.singletonList("ghost")));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("root"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
    }

    @Test
    public void aPropertyBoundToTheDataModelResolvesThroughIt() {
        A2uiSurface s = surface();
        s.writeDataModel("/track", "Kind of Blue");
        s.putComponent("t", props("id", "t", "component", "Text",
                "title", props("path", "/track")));
        assertThat(s.project("t").getTitle()).isEqualTo("Kind of Blue");

        // The point of binding rather than resending: the component is untouched and the title
        // changes anyway.
        s.writeDataModel("/track", "Blue Train");
        assertThat(s.project("t").getTitle()).isEqualTo("Blue Train");
    }

    @Test
    public void aBoundPropertyWhosePathIsAbsentResolvesToNull_notToTheLiteralPointer() {
        A2uiSurface s = surface();
        s.putComponent("t", props("id", "t", "component", "Text",
                "title", props("path", "/nothing/here")));
        // The failure that would otherwise show a driver the string "/nothing/here".
        assertThat(s.project("t").getTitle()).isNull();
    }

    @Test
    public void aNonStringBoundValueIsStringified_ratherThanDropped() {
        A2uiSurface s = surface();
        s.writeDataModel("/count", 42);
        s.putComponent("t", props("id", "t", "component", "Text",
                "title", props("path", "/count")));
        assertThat(s.project("t").getTitle()).isEqualTo("42");
    }

    // ---------------------------------------------------------------------------------------
    // Declared action classes. The dangerous direction is a declaration being read as an absence.

    @Test
    public void aTypoedActionClassIsRefused_ratherThanReadAsNotDeclared() {
        A2uiSurface s = surface();
        s.putComponent("buy", props("id", "buy", "component", "Button",
                "action", "play", "actionClass", "DESTRCUTIVE"));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("buy"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
        assertThat(e).hasMessageThat().contains("DESTRCUTIVE");
    }

    @Test
    public void aWellFormedActionClassIsCarriedOntoTheProjectedComponent() {
        A2uiSurface s = surface();
        s.putComponent("buy", props("id", "buy", "component", "Button",
                "action", "purchase", "actionClass", "DESTRUCTIVE"));
        A2uiComponent c = s.project("buy");
        assertThat(c.getActionClass()).isEqualTo(A2uiActionClass.DESTRUCTIVE);
    }

    @Test
    public void declaredActionClassIsNullForAComponentTheSurfaceDoesNotHave() {
        // Not an exception: asking about an absent component is a question, not a malformed
        // message. The processor's requireSurface/project paths are what refuse absent things.
        assertThat(surface().declaredActionClass("nobody")).isNull();
    }

    @Test
    public void declaredActionClassIsNullWhenTheComponentDeclaresNone() {
        A2uiSurface s = surface();
        s.putComponent("t", props("id", "t", "component", "Text"));
        assertThat(s.declaredActionClass("t")).isNull();
    }

    @Test
    public void declaredActionClassReadsWhatTheComponentDeclares() {
        A2uiSurface s = surface();
        s.putComponent("buy", props("id", "buy", "component", "Button",
                "action", "purchase", "actionClass", "CONSEQUENTIAL"));
        assertThat(s.declaredActionClass("buy")).isEqualTo(A2uiActionClass.CONSEQUENTIAL);
    }

    // Snapshot immutability: a shallow view would let a caller rewrite protocol state.

    @Test
    public void theComponentSnapshotCannotBeUsedToRewriteTheSurface() {
        A2uiSurface s = surface();
        s.putComponent("buy", props("id", "buy", "component", "Button", "action", "purchase"));
        Map<String, Map<String, Object>> snapshot = s.getComponents();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("evil", new HashMap<>()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.get("buy").put("action", "play"));

        // ...and the surface still says what it said.
        assertThat(s.project("buy").getAction()).isEqualTo("purchase");
    }

    @Test
    public void mutatingTheMapPassedToPutComponentDoesNotReachTheSurface() {
        A2uiSurface s = surface();
        Map<String, Object> sent = props("id", "buy", "component", "Button", "action", "purchase");
        s.putComponent("buy", sent);

        sent.put("action", "play");
        sent.put("children", Arrays.asList("a", "b"));

        assertThat(s.project("buy").getAction()).isEqualTo("purchase");
        assertThat(s.project("buy").hasChildren()).isFalse();
    }

    @Test
    public void aProjectedParentReportsItsChildren() {
        A2uiSurface s = surface();
        s.putComponent("root", props("id", "root", "component", "Column",
                "children", Arrays.asList("a", "b")));
        s.putComponent("a", props("id", "a", "component", "Text", "title", "one"));
        s.putComponent("b", props("id", "b", "component", "Text", "title", "two"));

        A2uiComponent root = s.project("root");
        assertThat(root.hasChildren()).isTrue();
        assertThat(root.getChildren()).hasSize(2);
        // Insertion order is render order -- the surface's docstring says so, and a set-backed
        // implementation would pass hasChildren() while getting this wrong.
        assertThat(root.getChildren().get(0).getTitle()).isEqualTo("one");
        assertThat(root.getChildren().get(1).getTitle()).isEqualTo("two");
    }

    @Test
    public void duplicateAndDiamondChildrenAreRejectedInsteadOfExpanded() {
        A2uiSurface duplicate = surface();
        duplicate.putComponent("root", props("id", "root", "component", "Column",
                "children", Arrays.asList("leaf", "leaf")));
        duplicate.putComponent("leaf", props("id", "leaf", "component", "Text"));
        assertThat(assertThrows(A2uiProtocolException.class,
                () -> duplicate.project("root")).getCategory())
                .isEqualTo(A2uiProtocolException.Category.INTEGRITY);

        A2uiSurface diamond = surface();
        diamond.putComponent("root", props("id", "root", "component", "Column",
                "children", Arrays.asList("left", "right")));
        diamond.putComponent("left", props("id", "left", "component", "Column",
                "children", Collections.singletonList("shared")));
        diamond.putComponent("right", props("id", "right", "component", "Column",
                "children", Collections.singletonList("shared")));
        diamond.putComponent("shared", props("id", "shared", "component", "Text"));
        assertThat(assertThrows(A2uiProtocolException.class,
                () -> diamond.project("root")).getCategory())
                .isEqualTo(A2uiProtocolException.Category.INTEGRITY);
    }

    @Test
    public void projectionDepthIsBounded() {
        A2uiSurface s = surface();
        for (int i = 0; i < 65; i++) {
            Map<String, Object> node = props("id", "n" + i, "component", "Column");
            if (i < 64) {
                node.put("children", Collections.singletonList("n" + (i + 1)));
            }
            s.putComponent("n" + i, node);
        }

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("n0"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        assertThat(e).hasMessageThat().contains("depth");
    }

    @Test
    public void projectionExpandedNodeCountIsBounded() {
        A2uiSurface s = surface();
        java.util.ArrayList<String> children = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String id = "leaf" + i;
            children.add(id);
            s.putComponent(id, props("id", id, "component", "Text"));
        }
        s.putComponent("root", props("id", "root", "component", "Column",
                "children", children));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> s.project("root"));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        assertThat(e).hasMessageThat().contains("maximum size");
    }
}
