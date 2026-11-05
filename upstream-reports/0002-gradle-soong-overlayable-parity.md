# Upstream candidate 2 — the Gradle build drops car-ui-lib's entire <overlayable> surface

**Target:** `packages/apps/Car/libs` — `car-ui-lib/car-ui-lib/build.gradle`
**Status:** PREPARED, NOT SUBMITTED. This one is a REPORT rather than a patch, deliberately: the
fix requires a decision about vendored resources that belongs to the module's owners.

## The finding

`build.gradle` declares:

```
res.srcDirs = ['src/main/res', 'src/main/res-private']
```

`src/main/res-overlayable` is **not** listed. Gradle reads only the directories it is told about,
so the 604-item `<overlayable name="car-ui-lib">` surface — including 43 `layout` entries, the
strongest form of the OEM seam — is absent from every artifact the Gradle build produces. Verified
with `aapt2 dump overlayable` against a consuming APK: nothing.

Soong builds this module and knows the path, so the surface is present in platform builds. The gap
is Gradle-only, and silent: no error, no warning, and a green build.

## Why simply adding the directory does not work

That directory also contains `removed_resources.xml`, a bookkeeping file listing resources deleted
from the overlayable so RROs do not target them. AGP compiles its `<item>` entries as real resource
definitions, and `layout/car_ui_base_layout_toolbar_legacy` then collides with `res-private`:

```
[layout/car_ui_base_layout_toolbar_legacy] .../res-private/layout/car_ui_base_layout_toolbar_legacy.xml
[layout/car_ui_base_layout_toolbar_legacy] .../res-overlayable/values/removed_resources.xml
Error: Duplicate resources
```

## Two things worth the owners' attention

1. A resource listed as *removed from the overlayable* while still present in `res-private` looks
   like an inconsistency independent of the build system.
2. Two sibling modules with the identical directory layout — `paintbooth` and `car-rotary-lib` —
   have the same omission and are fixable by declaring the directory, because neither carries a
   `removed_resources.xml`. That is the entire difference between the three.

## Suggested resolutions, in order of preference

- Split `removed_resources.xml` out of `res-overlayable/`, so the directory contains only the
  overlayable declaration and can be declared as a resource dir in both build systems.
- Or reconcile the removed-resources list with `res-private` so no name appears in both.
