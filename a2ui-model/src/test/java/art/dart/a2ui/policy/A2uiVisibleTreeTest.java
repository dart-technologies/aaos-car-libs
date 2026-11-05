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
package art.dart.a2ui.policy;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

/**
 * {@link A2uiVisibleTree}'s contract, tested in the module that publishes it so it cannot be
 * changed to suit one host. Includes the two cases most likely to be "simplified": the unusable
 * limit and the container root that carries an action.
 */
public class A2uiVisibleTreeTest {

    private static A2uiComponent leaf(String id, String action) {
        A2uiComponent.Builder b = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId(id)
                .setTitle(id);
        if (action != null) {
            b.setAction(action).setVoicePhrase("say " + id);
        }
        return b.build();
    }

    private static A2uiComponent list(String id, A2uiComponent... children) {
        A2uiComponent.Builder b = new A2uiComponent.Builder(ComponentType.LIST).setId(id);
        for (A2uiComponent c : children) {
            b.addChild(c);
        }
        return b.build();
    }

    // isContainer, asserted over the whole enum so a new ComponentType cannot slip past.

    @Test
    public void exactlyThreeTypesAreContainers_andEveryOtherTypeIsARow() {
        for (ComponentType type : ComponentType.values()) {
            boolean expected = type == ComponentType.LIST
                    || type == ComponentType.COLUMN
                    || type == ComponentType.CARD;
            assertThat(A2uiVisibleTree.isContainer(type)).isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------------------------------
    // renderableRows.

    @Test
    public void aContainerWithChildrenRendersItsChildren_notItself() {
        A2uiComponent root = list("root", leaf("a", null), leaf("b", null));
        assertThat(A2uiVisibleTree.renderableRows(root)).hasSize(2);
        assertThat(A2uiVisibleTree.renderableRows(root).get(0).getId()).isEqualTo("a");
    }

    @Test
    public void aContainerWithNoChildrenRendersItself() {
        // The empty-container branch: it is drawn as its own row, which is why visibleRoot's
        // non-container case has to be reached by "rows == [root]" rather than by asking the type.
        assertThat(A2uiVisibleTree.renderableRows(list("empty"))).hasSize(1);
        assertThat(A2uiVisibleTree.renderableRows(list("empty")).get(0).getId()).isEqualTo("empty");
    }

    @Test
    public void aNonContainerRendersItself() {
        assertThat(A2uiVisibleTree.renderableRows(leaf("solo", "play"))).hasSize(1);
    }

    @Test
    public void aNullRootRendersNoRows_ratherThanThrowing() {
        assertThat(A2uiVisibleTree.renderableRows(null)).isEmpty();
    }

    @Test
    public void grandchildrenAreNotRows() {
        // No renderer recurses, so a control nested inside a rendered row is not on screen.
        A2uiComponent nested = list("row", leaf("hidden", "purchase"));
        A2uiComponent root = list("root", nested);
        assertThat(A2uiVisibleTree.renderableRows(root)).hasSize(1);
        assertThat(A2uiVisibleTree.renderableRows(root).get(0).getId()).isEqualTo("row");
    }

    // ---------------------------------------------------------------------------------------
    // visibleRoot: the content limit.

    @Test
    public void aNullRootIsNotVisible() {
        assertThat(A2uiVisibleTree.visibleRoot(null, 5)).isNull();
    }

    @Test
    public void theLimitTruncatesTheChildren_inOrder() {
        A2uiComponent root = list("root", leaf("a", null), leaf("b", null), leaf("c", null));
        A2uiComponent visible = A2uiVisibleTree.visibleRoot(root, 2);
        assertThat(visible.getChildren()).hasSize(2);
        assertThat(visible.getChildren().get(0).getId()).isEqualTo("a");
        assertThat(visible.getChildren().get(1).getId()).isEqualTo("b");
    }

    @Test
    public void aLimitLargerThanTheRowCountKeepsEveryRow() {
        A2uiComponent root = list("root", leaf("a", null), leaf("b", null));
        assertThat(A2uiVisibleTree.visibleRoot(root, 99).getChildren()).hasSize(2);
    }

    @Test
    public void grandchildrenAreDroppedFromTheVisibleTree() {
        // Descendants are dropped: one line per row, so voice must not reach inside it.
        A2uiComponent nested = list("row", leaf("hidden", "purchase"));
        A2uiComponent visible = A2uiVisibleTree.visibleRoot(list("root", nested), 5);
        assertThat(visible.getChildren()).hasSize(1);
        assertThat(visible.getChildren().get(0).hasChildren()).isFalse();
    }

    // Divergence 1: an unusable limit is a missing answer, not a stricter one.

    @Test
    public void aLimitBelowOneIsNotVisibleAtAll_ratherThanARootWithNoChildren() {
        A2uiComponent root = list("root", leaf("a", "play"));
        // Null, not an empty root: null reads as NO_MATCH, an empty root as "showing nothing".
        assertThat(A2uiVisibleTree.visibleRoot(root, 0)).isNull();
        assertThat(A2uiVisibleTree.visibleRoot(root, -1)).isNull();
        assertThat(A2uiVisibleTree.visibleRoot(root, A2uiVisibleTree.MIN_USABLE_CONTENT_LIMIT))
                .isNotNull();
    }

    @Test
    public void aLoneControlIsAlsoInvisibleAtAnUnusableLimit() {
        // The non-container branch must honour the limit too.
        assertThat(A2uiVisibleTree.visibleRoot(leaf("solo", "purchase"), 0)).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Divergence 2: a container root with children draws no row, so it must invoke nothing.

    @Test
    public void aContainerRootWithChildrenCannotBeInvoked() {
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.LIST)
                .setId("root")
                .setTitle("Library")
                .setSubtitle("42 albums")
                .setAction("purchase")
                .setVoicePhrase("buy everything")
                .setActionClass(A2uiActionClass.DESTRUCTIVE)
                .addChild(leaf("a", null))
                .build();

        A2uiComponent visible = A2uiVisibleTree.visibleRoot(root, 5);

        // No row is drawn for it, so all three invoking fields go...
        assertThat(visible.getAction()).isNull();
        assertThat(visible.getVoicePhrase()).isNull();
        assertThat(visible.getActionClass()).isNull();
        // ...and the descriptive ones stay: they name the tree, they invoke nothing.
        assertThat(visible.getId()).isEqualTo("root");
        assertThat(visible.getTitle()).isEqualTo("Library");
        assertThat(visible.getSubtitle()).isEqualTo("42 albums");
    }

    @Test
    public void aNonContainerRootKEEPSItsAction_whichIsTheOppositeAndMustStaySo() {
        // Here the root is the drawn row with a click listener, so its action stays; clearing it
        // unconditionally would pass the test above and fail this one.
        A2uiComponent visible = A2uiVisibleTree.visibleRoot(leaf("solo", "play"), 5);
        assertThat(visible.getAction()).isEqualTo("play");
        assertThat(visible.getVoicePhrase()).isEqualTo("say solo");
    }

    @Test
    public void anEmptyContainerRootIsDrawnAsItselfAndKeepsItsAction() {
        // The branch is decided by which case render takes, not by the root's type: an empty
        // container is drawn as its own row, so it keeps its action exactly as a leaf would.
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.LIST)
                .setId("empty")
                .setAction("refresh")
                .build();
        assertThat(A2uiVisibleTree.visibleRoot(root, 5).getAction()).isEqualTo("refresh");
    }

    // ---------------------------------------------------------------------------------------
    // The result is a copy, not a view.

    @Test
    public void theVisibleTreeIsACopy_soTheCallerCannotReachTheRenderedOne() {
        A2uiComponent child = leaf("a", "play");
        A2uiComponent root = list("root", child);
        A2uiComponent visible = A2uiVisibleTree.visibleRoot(root, 5);
        assertThat(visible).isNotSameInstanceAs(root);
        assertThat(visible.getChildren().get(0)).isNotSameInstanceAs(child);
        // ...and the copy carries the properties, which is what copyPropertiesFrom is for.
        assertThat(visible.getChildren().get(0).getAction()).isEqualTo("play");
    }
}
