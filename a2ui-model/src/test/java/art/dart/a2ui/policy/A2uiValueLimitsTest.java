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

import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH;
import static art.dart.a2ui.policy.A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

/**
 * Covers the restriction dimension that component-type pruning cannot express, including
 * {@link #limitStringLength_isActuallyWired}: a platform-pinned constant can still be read by
 * nothing.
 */
public class A2uiValueLimitsTest {

    // The vehicle's own configuration, standing in for a live reading.
    private static final int VEHICLE_STRING = 120;
    private static final int VEHICLE_ITEMS = 21;
    private static final int VEHICLE_DEPTH = 3;

    private static A2uiValueLimits from(int active) {
        return A2uiValueLimits.from(active, VEHICLE_STRING, VEHICLE_ITEMS, VEHICLE_DEPTH);
    }

    // 1. LIMIT_STRING_LENGTH is read.

    @Test
    public void limitStringLength_isActuallyWired() {
        A2uiValueLimits limits = from(UX_RESTRICTIONS_LIMIT_STRING_LENGTH);

        assertThat(limits.getMaxStringLength()).isEqualTo(VEHICLE_STRING);
        assertThat(limits.isUnrestricted()).isFalse();
    }

    @Test
    public void limitStringLength_doesNotAlsoLimitContent() {
        A2uiValueLimits limits = from(UX_RESTRICTIONS_LIMIT_STRING_LENGTH);

        // Each bit governs its own dimension. A vehicle asking for shorter strings has not asked
        // for fewer items.
        assertThat(limits.getMaxContentItems()).isEqualTo(A2uiValueLimits.UNLIMITED);
        assertThat(limits.getMaxContentDepth()).isEqualTo(A2uiValueLimits.UNLIMITED);
    }

    @Test
    public void limitContent_governsItemsAndDepth_butNotStrings() {
        A2uiValueLimits limits = from(UX_RESTRICTIONS_LIMIT_CONTENT);

        assertThat(limits.getMaxContentItems()).isEqualTo(VEHICLE_ITEMS);
        assertThat(limits.getMaxContentDepth()).isEqualTo(VEHICLE_DEPTH);
        assertThat(limits.getMaxStringLength()).isEqualTo(A2uiValueLimits.UNLIMITED);
    }

    @Test
    public void unrelatedRestriction_limitsNothing() {
        assertThat(from(UX_RESTRICTIONS_NO_VIDEO).isUnrestricted()).isTrue();
        assertThat(from(UX_RESTRICTIONS_BASELINE).isUnrestricted()).isTrue();
    }

    @Test
    public void fullyRestricted_limitsEveryDimension() {
        assertThat(from(UX_RESTRICTIONS_FULLY_RESTRICTED).isUnrestricted()).isFalse();
        assertThat(from(UX_RESTRICTIONS_FULLY_RESTRICTED).getMaxStringLength())
                .isEqualTo(VEHICLE_STRING);
    }

    // 2. A set bit with no usable number still restricts; reading it as unlimited fails open.

    @Test
    public void bitSetButVehicleReportsZero_fallsBackConservatively_notUnlimited() {
        A2uiValueLimits limits = A2uiValueLimits.from(
                UX_RESTRICTIONS_LIMIT_STRING_LENGTH | UX_RESTRICTIONS_LIMIT_CONTENT, 0, 0, 0);

        assertThat(limits.getMaxStringLength()).isNotEqualTo(A2uiValueLimits.UNLIMITED);
        assertThat(limits.getMaxContentItems()).isNotEqualTo(A2uiValueLimits.UNLIMITED);
        assertThat(limits.getMaxContentDepth()).isNotEqualTo(A2uiValueLimits.UNLIMITED);
        assertThat(limits.getMaxStringLength())
                .isEqualTo(A2uiValueLimits.failClosed().getMaxStringLength());
    }

    @Test
    public void bitSetButVehicleReportsNegative_fallsBackConservatively() {
        A2uiValueLimits limits = A2uiValueLimits.from(
                UX_RESTRICTIONS_LIMIT_CONTENT, -1, -1, -1);

        assertThat(limits.getMaxContentItems())
                .isEqualTo(A2uiValueLimits.failClosed().getMaxContentItems());
    }

    @Test
    public void failClosed_isStricterThanATypicalVehicleConfiguration() {
        A2uiValueLimits failClosed = A2uiValueLimits.failClosed();

        assertThat(failClosed.getMaxStringLength()).isLessThan(VEHICLE_STRING);
        assertThat(failClosed.getMaxContentItems()).isLessThan(VEHICLE_ITEMS);
        assertThat(failClosed.getMaxContentDepth()).isLessThan(VEHICLE_DEPTH);
        assertThat(failClosed.isUnrestricted()).isFalse();
    }

    @Test
    public void unrestricted_isUnlimitedInEveryDimension() {
        assertThat(A2uiValueLimits.unrestricted().isUnrestricted()).isTrue();
    }

    // 3. Truncation.

    @Test
    public void truncate_leavesStringsWithinTheLimitAlone() {
        assertThat(A2uiPruner.truncate("exactly-ten", 11)).isEqualTo("exactly-ten");
        assertThat(A2uiPruner.truncate("short", 99)).isEqualTo("short");
        assertThat(A2uiPruner.truncate(null, 5)).isNull();
    }

    @Test
    public void truncate_countsTheEllipsisInsideTheBudget() {
        String out = A2uiPruner.truncate("abcdefghij", 5);

        // Not 6. A result one character over the limit is not a truncation, it is an off-by-one
        // wearing one.
        assertThat(out).hasLength(5);
        assertThat(out).isEqualTo("abcd…");
    }

    @Test
    public void truncate_degenerateLimits() {
        assertThat(A2uiPruner.truncate("abcdef", 1)).isEqualTo("…");
        assertThat(A2uiPruner.truncate("abcdef", 0)).isEmpty();
        assertThat(A2uiPruner.truncate("abcdef", -3)).isEmpty();
    }

    @Test
    public void truncate_neverSplitsASurrogatePair() {
        // "ab" + musical G-clef (one code point, two chars) + "cd". Cutting at char 4 would land
        // between the surrogates and produce an unpaired one.
        String text = "ab𝄞cd";
        String out = A2uiPruner.truncate(text, 4);

        assertThat(out).hasLength(3);          // dropped the whole pair rather than half of it
        assertThat(out).isEqualTo("ab…");
        for (int i = 0; i < out.length(); i++) {
            assertThat(Character.isSurrogate(out.charAt(i))).isFalse();
        }
    }

    // 4. Applying limits to a tree.

    private static A2uiComponent leaf(ComponentType type, String title) {
        return new A2uiComponent.Builder(type).setTitle(title).build();
    }

    @Test
    public void contentItems_areCumulativeAcrossTheTree_notPerContainer() {
        // Three columns of three items each. A per-container reading would admit all nine.
        A2uiComponent.Builder root = new A2uiComponent.Builder(ComponentType.COLUMN);
        for (int c = 0; c < 3; c++) {
            A2uiComponent.Builder col = new A2uiComponent.Builder(ComponentType.COLUMN);
            for (int i = 0; i < 3; i++) {
                col.addChild(leaf(ComponentType.TEXT, "item " + c + "." + i));
            }
            root.addChild(col.build());
        }

        A2uiComponent out = A2uiPruner.applyLimits(root.build(),
                A2uiValueLimits.from(UX_RESTRICTIONS_LIMIT_CONTENT, 0, 5, 99));

        assertThat(A2uiPruner.countOfType(out, ComponentType.TEXT)).isEqualTo(5);
    }

    @Test
    public void layoutComponents_doNotConsumeTheItemBudget() {
        // Six Texts, each wrapped in its own Row, inside a Column. Counting layout would spend the
        // budget on structure and prune a surface for being well-formed.
        A2uiComponent.Builder root = new A2uiComponent.Builder(ComponentType.COLUMN);
        for (int i = 0; i < 6; i++) {
            root.addChild(new A2uiComponent.Builder(ComponentType.ROW)
                    .addChild(leaf(ComponentType.TEXT, "item " + i))
                    .build());
        }

        A2uiComponent out = A2uiPruner.applyLimits(root.build(),
                A2uiValueLimits.from(UX_RESTRICTIONS_LIMIT_CONTENT, 0, 6, 99));

        assertThat(A2uiPruner.countOfType(out, ComponentType.TEXT)).isEqualTo(6);
    }

    @Test
    public void depthLimit_cutsSubtrees_ratherThanFlatteningThemUp() {
        // Column > Column > Text. With depth 2, the inner Column is at depth 2 and its Text at 3.
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.COLUMN)
                        .addChild(leaf(ComponentType.TEXT, "deep"))
                        .build())
                .build();

        A2uiComponent out = A2uiPruner.applyLimits(tree,
                A2uiValueLimits.from(UX_RESTRICTIONS_LIMIT_CONTENT, 0, 99, 2));

        // Cut, not lifted into the visible level.
        assertThat(A2uiPruner.countOfType(out, ComponentType.TEXT)).isEqualTo(0);
        assertThat(out.getChildren()).hasSize(1);
    }

    @Test
    public void from_neverProducesAZeroBudget() {
        // No mask yields a zero budget, which the guard's own test below relies on.
        for (int mask = 0; mask <= UX_RESTRICTIONS_FULLY_RESTRICTED; mask++) {
            A2uiValueLimits limits = A2uiValueLimits.from(mask, 0, 0, 0);
            assertThat(limits.getMaxStringLength()).isGreaterThan(0);
            assertThat(limits.getMaxContentItems()).isGreaterThan(0);
            assertThat(limits.getMaxContentDepth()).isGreaterThan(0);
        }
    }

    @Test
    public void exclusionGuard_dropsTheRootWhenTheBudgetIsZero() {
        // Reached only through the test-only door, because from() cannot produce this. Exercising
        // it anyway: a guard that has never executed is a guess about what it would do.
        A2uiComponent out = A2uiPruner.applyLimits(leaf(ComponentType.TEXT, "anything"),
                A2uiValueLimits.degenerateForTesting(A2uiValueLimits.UNLIMITED, 0, 99));

        assertThat(out).isNull();
    }

    @Test
    public void exclusionGuard_dropsTheRootWhenTheDepthBudgetIsZero() {
        A2uiComponent out = A2uiPruner.applyLimits(leaf(ComponentType.TEXT, "anything"),
                A2uiValueLimits.degenerateForTesting(A2uiValueLimits.UNLIMITED, 99, 0));

        assertThat(out).isNull();
    }

    @Test
    public void unrestrictedLimits_leaveTheTreeIdentical() {
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.TEXT, "a fairly long title that is not truncated"))
                .build();

        A2uiComponent out = A2uiPruner.applyLimits(tree, A2uiValueLimits.unrestricted());

        assertThat(out.getChildren()).hasSize(1);
        assertThat(out.getChildren().get(0).getTitle())
                .isEqualTo("a fairly long title that is not truncated");
    }

    @Test
    public void stringLimit_appliesAtEveryDepth_notOnlyTheRoot() {
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.ROW)
                        .addChild(leaf(ComponentType.TEXT, "0123456789abcdef"))
                        .build())
                .build();

        A2uiComponent out = A2uiPruner.applyLimits(tree,
                A2uiValueLimits.from(UX_RESTRICTIONS_LIMIT_STRING_LENGTH, 8, 0, 0));

        String title = out.getChildren().get(0).getChildren().get(0).getTitle();
        assertThat(title).hasLength(8);
        assertThat(title).isEqualTo("0123456…");
    }

    // 5. The platform getters the contract names must exist. Checked by reflection, since the stub
    // jar's bodies throw; a rename would otherwise fail only in the host module.

    @Test
    public void platformGettersExistWithTheNamesTheJavadocClaims() throws Exception {
        Class<?> uxr = android.car.drivingstate.CarUxRestrictions.class;

        assertThat(uxr.getMethod("getMaxRestrictedStringLength").getReturnType())
                .isEqualTo(int.class);
        assertThat(uxr.getMethod("getMaxCumulativeContentItems").getReturnType())
                .isEqualTo(int.class);
        assertThat(uxr.getMethod("getMaxContentDepth").getReturnType()).isEqualTo(int.class);
    }

    // 6. Transforms rebuild the tree, so a field they forget (worst: the action) is silently erased.

    private static A2uiComponent interactive() {
        return new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy")
                .setTitle("Buy album")
                .setSubtitle("consequential")
                .setAction("purchase")
                .setActionClass(A2uiActionClass.DESTRUCTIVE)
                .build();
    }

    @Test
    public void pruning_preservesIdActionAndDeclaredClass() {
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(interactive())
                .build();

        A2uiComponent out = A2uiPruner.prune(tree, A2uiRestrictionPolicy.failClosedComponents());

        A2uiComponent button = out.getChildren().get(0);
        assertThat(button.getId()).isEqualTo("buy");
        assertThat(button.getAction()).isEqualTo("purchase");
        assertThat(button.getActionClass()).isEqualTo(A2uiActionClass.DESTRUCTIVE);
        assertThat(button.isInteractive()).isTrue();
    }

    @Test
    public void applyingLimits_preservesIdActionAndDeclaredClass() {
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(interactive())
                .build();

        A2uiComponent out = A2uiPruner.applyLimits(tree,
                A2uiValueLimits.from(UX_RESTRICTIONS_LIMIT_STRING_LENGTH, 4, 0, 0));

        A2uiComponent button = out.getChildren().get(0);
        assertThat(button.getTitle()).hasLength(4);          // the transform did its job...
        assertThat(button.getAction()).isEqualTo("purchase"); // ...without erasing the rest
        assertThat(button.getId()).isEqualTo("buy");
        assertThat(button.getActionClass()).isEqualTo(A2uiActionClass.DESTRUCTIVE);
    }

    @Test
    public void nonInteractiveComponentsReportNoAction() {
        A2uiComponent text = new A2uiComponent.Builder(ComponentType.TEXT).setTitle("x").build();

        assertThat(text.isInteractive()).isFalse();
        assertThat(text.getAction()).isNull();
        assertThat(text.getActionClass()).isNull();
    }
}
