# Draft proposal to A2UI: `allowed_components` is an incomplete description of a constrained surface

**Status: drafted, NOT filed.** Sequenced **after** the automotive catalog PR
(`A2UI-PR-DRAFT.md`) lands. That PR is purely additive and should be uncontroversial; this is a spec
change and a different conversation. Bundling them makes the easy thing wait on the hard one.

---

## The argument in one sentence

A surface is constrained by **which components it may use**, **how large their values may be**, and
**whether the constraint was derived from something real** — and `prune` carries only the first.

Two gaps, proposed together because they are the same gap seen twice: *the catalog cannot know
anything about the input it is pruning against.*

## Gap 1 — value constraints

`action: prune` with `args.allowed_components` constrains which components may appear. It cannot
constrain their values. In the automotive domain that leaves restrictions half-expressible:

| Restriction | Component half (expressible) | Value half (not expressible) |
|---|---|---|
| `UX_RESTRICTIONS_LIMIT_CONTENT` (0x20) | drop `Tabs`, `Modal`, `Video` | `getMaxCumulativeContentItems()` capping `List` length; nesting depth |
| `UX_RESTRICTIONS_LIMIT_STRING_LENGTH` (0x4) | nothing to drop | `getMaxRestrictedStringLength()` truncating `Text` |
| glance budget ("readable in under 2 seconds") | nothing to drop | an enforced maximum |

`UX_RESTRICTIONS_NO_DIALPAD` (0x1) has no expressible half at all in the `basic` catalog.

A conformant renderer can therefore emit a `List` of two hundred items under `LIMIT_CONTENT` and
pass. The component set is legal; the surface is not.

## Gap 2 — provenance

Every conformance case supplies its restriction level as an **input**, and `prune` applies it
faithfully. Nothing in the format carries where that level came from.

So a renderer that derives `allowed_components` from a stale, defaulted, or never-delivered
restriction value emits a **perfectly conformant permissive catalog**, and the suite cannot tell.

This makes the suite's own `driving_state_unknown_fails_closed` case weaker than its name suggests.
It pins that `UNKNOWN` prunes like `FULLY_RESTRICTED` *once you have concluded the state is
`UNKNOWN`*. It cannot pin that a renderer noticed it never received a reading — which is the failure
that actually occurs.

### Why this is not a hypothetical, and how to check it without trusting us

The reference implementation of automotive UI — AOSP's `car-ui-lib` — does this **both ways in one
library**:

| Location | Failure default | Effect |
|---|---|---|
| `car-apps-common/util/CarPackageManagerUtils.isDistractionOptimized(...)` | `false` on a null manager | routes to `NO_SETUP` → **restricted**. Correct |
| `car-ui-lib/utils/CarUxRestrictionsUtil` catch block | `UX_RESTRICTIONS_BASELINE` | reports **unrestricted** when car service is unreachable |

Same library, plausibly the same engineers, opposite outcomes. The only variable is whether the
predicate states *permission* or *restriction* — and therefore what its failure value means.

Both are two-minute reads at `e120cb74e` in `platform/packages/apps/Car/libs`. A reviewer can verify
the claim without trusting anything else in this proposal, which is the point of citing it.

The lesson generalises past automotive: **wherever a constraint is derived from a signal, "we have no
signal" and "the signal says unconstrained" are representationally identical unless something forces
them apart.** A catalog that accepts a constraint without provenance cannot distinguish them either.

## What we are proposing

Deliberately shaped as **additive** — A2UI v1.0 is a stable candidate with an explicitly high bar for
breaking changes, and neither gap requires altering existing behaviour.

### 1. Value constraints in the prune input

Extend `args` alongside `allowed_components`, leaving current semantics untouched when absent:

```yaml
action: prune
args:
  allowed_components: [Text, Icon, Row, Column, Divider, Button, List]
  component_constraints:
    List:  {max_items: 4, max_depth: 1}
    Text:  {max_length: 120}
```

Conformance then asserts a surface exceeding a stated constraint is rejected or truncated —
`F2` in our use-case set, "legal component, illegal content length", which is currently unexpressible.

### 2. Provenance on the constraint

A declaration that the restriction driving the prune was actually measured:

```yaml
action: prune
args:
  allowed_components: [Text, Icon, Row, Column, Divider, Button]
  constraint_source: delivered      # delivered | never_delivered | disconnected
```

with the rule that **anything other than `delivered` must prune at least as tightly as the most
restrictive level the catalog defines**. That makes "we never heard from the vehicle" and "the
vehicle says unrestricted" different inputs, which is the whole point.

Three values rather than a boolean, because "never delivered" and "was delivered and then lost" have
different diagnostics even though both must fail closed. This mirrors `RestrictionsSource` in our
`car_uxr` binding, which exists for exactly this reason.

## What we are not proposing

- No change to existing prune semantics. Both fields are optional; absent, behaviour is identical.
- No automotive-specific vocabulary in the core spec. Both fields are domain-neutral — a kiosk, a
  TV ten-foot UI, or an accessibility profile has the same two questions.
- No claim that A2UI must enforce these at runtime. The ask is that the **format can express them**,
  so conformance can test them.

## Provenance of the proposal itself

Both gaps were found by building the automotive catalog and a renderer binding, not by reading the
spec. Every claim is backed by a passing test in `dart-technologies/aaos-car-libs`, and the
underlying defect pattern is documented with six instances across two safety domains in
`docs/FAILURE-TAXONOMY.md`.

That matters for one reason: a proposal that says "the format is missing something" is far easier to
weigh when the author can say precisely which test they could not write.
