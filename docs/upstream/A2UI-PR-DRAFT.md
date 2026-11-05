# Draft PR: automotive conformance suite → a2ui-project/a2ui

**Status: drafted for review, NOT opened.** No fork exists, no branch pushed, no PR filed.

The content is ready and already passes A2UI's own validator. What follows is what would be
submitted, so it can be reviewed before anything becomes public.

---

## Mechanics

| | |
|---|---|
| Target | `a2ui-project/a2ui`, default branch |
| Fork | would need `dart-technologies/a2ui` (does not exist yet) |
| Branch | `automotive-conformance` |
| Files | **one added**, zero modified |
| File path | `conformance/core/automotive.yaml` |
| Source | `a2ui-automotive/conformance/automotive.yaml` in this repo, copied verbatim |

**Path matters.** A2UI's validator (`conformance/tests/test_conformance_yaml.py`) globs only
`conformance/{core,agent,extensions}`. A new top-level `conformance/automotive/` directory would be
silently skipped — present, never run, and green by omission. `core/` is also where
`accessibility.yaml` lives, which is the closest existing analogue.

## Proposed PR title

```
conformance: add automotive driver-distraction suite
```

## Proposed PR body

> Adds a conformance suite expressing Android Automotive OS driver-distraction rules using the
> mechanism A2UI already has, rather than proposing a new one.
>
> AAOS gates what a UI may show while a vehicle is moving, via
> `android.car.drivingstate.CarUxRestrictions`. Restricting a surface to a legal subset of
> components is exactly what `action: prune` with `args.allowed_components` already does — so each
> restriction level becomes one prune case, and driver distraction becomes machine-checkable rather
> than a style guide.
>
> `conformance/core/accessibility.yaml` is the precedent: an existing non-functional-constraint
> domain. Driver distraction is its direct analogue — a constraint on what may be presented,
> orthogonal to what the surface is for.
>
> **12 cases**, all at protocol v1.0, pruning against the 18 components of
> `specification/v1_0/catalogs/basic/catalog.json` in that catalog's own `anyComponent` ordering:
> `BASELINE`, `NO_VIDEO`, `NO_KEYBOARD`, `NO_FILTERING`, `NO_SETUP`, `LIMIT_CONTENT`,
> `FULLY_RESTRICTED`, plus four that catch real implementation bugs:
>
> - **`DRIVING_STATE_UNKNOWN` must prune as `FULLY_RESTRICTED`, not `BASELINE`.** The platform
>   treats `UNKNOWN` exactly like `MOVING`, and a default-`false` boolean is byte-identical to a
>   genuine "parked" reading. A renderer that has never received a restriction event must fail
>   closed.
> - **A passenger display can be `BASELINE` while the driver's is fully restricted** in the same
>   moving vehicle. Restrictions are per-display, not vehicle-wide.
> - **A surface composed on an unrestricted display and sent to the driver's must be re-pruned for
>   its destination.** The target display's restrictions win.
> - **A component outside the pruned set must not be reintroduced on request.** Pruning is the
>   enforcement boundary, which is what makes generative UI shippable when an agent eventually emits
>   something illegal.
>
> Additive only: one new file, nothing in the core spec touched. v1.0 is a stable candidate with an
> explicitly high bar for breaking changes.
>
> Two notes for reviewers:
>
> - Upstream prune coverage is currently 0.8 and 0.9 only, so these are also the first v1.0 prune
>   cases. v1.0 was confirmed to keep the v0.9 output shape (`$defs.anyComponent.oneOf` plus
>   `components`).
> - Restriction constants were read from `android.car.jar` (API 34) with `javap -constants`, not
>   from documentation — a widely-copied table of these values is wrong in most rows. Note the
>   plural `UX_RESTRICTIONS_*` naming, and that `FULLY_RESTRICTED` (0x1ff) is a composite of the
>   nine individual bits rather than a flag of its own.
>
> **Deliberately out of scope:** `prune` constrains which components may appear, not their values.
> So `LIMIT_CONTENT`'s item cap, `LIMIT_STRING_LENGTH`'s truncation and any glance-time budget are
> only half-expressible here, and `NO_DIALPAD` not at all (the basic catalog has no dialpad
> component). Closing that needs a value-constraint mechanism, which would be a spec addition — not
> bundled with this, deliberately.
>
> Verified: passes `conformance/tests/test_conformance_yaml.py` (9 passed — the 8 existing suites
> plus this one) and validates against `conformance/conformance_schema.json`.

## Pre-submission checklist

- [ ] Read `CONTRIBUTING.md` — A2UI is a Google-adjacent project and will likely require a CLA
- [ ] Check whether an automotive profile is already being discussed upstream (issues + discussions)
- [ ] Create `dart-technologies/a2ui` fork
- [ ] Re-run the validator against upstream `main` at submission time, not against the pinned clone
- [ ] Decide attribution: Dart Technologies, or an individual maintainer

## Sequencing

The value-constraint proposal (roadmap item 7) stays separate and comes **after** this lands. This
PR is purely additive and should be uncontroversial; a mechanism proposal is a spec change and a
different conversation. Bundling them would make the easy thing wait on the hard thing.
