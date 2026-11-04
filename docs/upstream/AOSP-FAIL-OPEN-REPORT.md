# Upstream report: fail-open in car-ui-lib driver-distraction gating

**Status: drafted, NOT filed.** Filing requires an AOSP Gerrit account and a signed CLA, and is an
outward-facing act under Dart's name. This is the artifact to file, not a record of having filed.

---

## Where to file

| Route | Target | Notes |
|---|---|---|
| Bug | [issuetracker.google.com](https://issuetracker.google.com) → Android Automotive OS | Lowest friction; no CLA |
| Patch | `https://android-review.googlesource.com` → `platform/packages/apps/Car/libs` | Needs Gerrit account + CLA |

Filing the bug first is the better move. It costs nothing, creates a citable public record, and
answers the useful secondary question: **does this frozen repo still accept patches?** The last
upstream commit (`e120cb74e`, 2025-02-28) is an OWNERS-file removal, so the answer is genuinely
unknown and worth knowing either way.

---

## Title

`car-ui-lib: CarUxRestrictionsUtil fails OPEN when car service is unreachable`

## Lead with this

**In `CarUxRestrictionsUtil` — the class through which every UX-restriction decision in `car-ui-lib`
is made — the permissive branch is the default on the failure path.** When car service cannot be
reached, the library reports that the vehicle is *unrestricted*.

That is not a typo-class slip, and the reason to say so is practical rather than rhetorical.

While building a standalone fork of this library and a Flutter binding on top of it, we hit the same
defect **five times**, at five different layers, in code written by three different parties
(AOSP, our fork, and our fork's own tests). Every instance failed in the permissive direction. Every
one was invisible from the layer above:

| # | Where | Shape |
|---|---|---|
| **1** | **AOSP, the defect below** | **error treated as permission** |
| 2 | our fork | absence indistinguishable from a benign reading (`false` == genuinely parked) |
| 3 | the platform API's contract | no event mistaken for no change (change-only listener needs a seed) |
| 4 | our Dart stream | the correction arrives before the pipeline carrying it |
| 5 | our wire parser | partial success reported as success (valid flags, unparseable provenance → permissive) |

Four of those five are ours, and we are not reporting them to you — they are ours to fix, and they
are fixed. We list them because they establish that in this domain **the permissive branch is the
easy default**, which is the argument for treating the one below as more than a three-line patch.
`car-ui-lib` is the reference implementation every AAOS app UI inherits from, and a relaxation-by-
default in its restriction path propagates silently to all of them.

The concrete ask is small — the fix is three lines. The broader suggestion is that this class
deserves a review pass with "what does this do when the signal is untrustworthy?" asked at each
branch, because the four we found in our own code all survived ordinary review and ordinary tests.

## Severity

Driver-distraction gating is a safety control. This defect releases it under precisely the
conditions where the signal is least trustworthy — and it does so silently, logging a message that
states the wrong conclusion as if it were intended.

## Affected code

**The defect is present in TWO files.** A patch to only the first leaves the second failing open —
worth stating plainly, because the second is easy to miss.

1. `car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java`, lines 85–92
2. `car-ui-lib/referencedesign/plugin/src/main/java/com/chassis/car/ui/plugin/uxr/CarUxRestrictionsUtil.java`

The second is a near-duplicate in the reference design plugin, and is arguably the clearer error:
it catches `SecurityException` **specifically** — the exact trigger the first file's own comment
names — and then treats it as permission:

```java
} catch (SecurityException e) {
    Log.w(TAG, "Unable to connect to car service, assuming unrestricted", e);
    listener.onUxRestrictionsChanged(new CarUxRestrictions.Builder(
            false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 0)
            .build());
}
```

Both take the same fix. The first, at
`e120cb74e7d654cd6cb22fe6127a19eaf2075f6f` (branch `main`, current HEAD), lines 85–92:

```java
} catch (RuntimeException e) {
    // Can't catch more specific exception, because
    // Car class literally throws RuntimeException or SecurityException on error.
    Log.w(TAG, "Unable to connect to car service, assuming unrestricted", e);
    mListener.onUxRestrictionsChanged(new CarUxRestrictions.Builder(
            false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 0)
            .build());
}
```

## The defect

`UX_RESTRICTIONS_BASELINE` is 0x0 — **no restrictions active** — and the `false` argument is
`requiresDistractionOptimization`. So when the library cannot reach car service, it reports that the
vehicle is unrestricted.

This contradicts the class's own default, 30 lines above, which is correct:

```java
@NonNull
private static CarUxRestrictions getDefaultRestrictions() {
    return new CarUxRestrictions.Builder(
            true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, 0)
            .build();
}
```

and it contradicts the disconnect path in the same constructor, which is also correct:

```java
} else {
    Log.w(TAG, "Car service disconnected, assuming fully restricted");
    mListener.onUxRestrictionsChanged(null);   // -> getDefaultRestrictions()
}
```

Three paths express "we do not have a trustworthy reading." Two fail closed. One fails open.

## Why it triggers in practice

The comment above the catch names the trigger itself: `Car` throws `RuntimeException` **or
`SecurityException`** on error. Reachable cases include:

- The app lacks the permission car service requires, or a permission is revoked at runtime →
  `SecurityException`
- Car service is not running or not yet up when the app binds early in boot
- The build is not automotive, or `android.hardware.type.automotive` is absent
- Any `RuntimeException` from `Car.createCar` on a misconfigured or partial head unit

Every one of these is a *state of ignorance*, not evidence that the vehicle is parked.

## Impact

Every `car-ui-lib` consumer of restriction state is affected, because they all read through this
singleton — `MenuItem` / `ToolbarControllerImpl`, `CarUiRecyclerViewImpl` content limiting,
`SearchView` keyboard suppression, and `UxrButton` / `UxrTextView` in `car-apps-common`.

Concretely: a head unit whose app hits a `SecurityException` binding to car service will render
toolbars, keyboards, unbounded lists and enabled buttons **while the vehicle is in motion**, having
logged only `"Unable to connect to car service, assuming unrestricted"`.

The failure is also invisible to testing that only exercises the happy path, since the correct
fail-closed default is installed at construction and is only overwritten on the exception path.

## Reproduction

1. Build any app against `car-ui-lib` with a `CarUiToolbar` or a content-limited `CarUiRecyclerView`.
2. Make `Car.createCar()` throw — easiest is to remove the car-service permission the app requests,
   or launch before car service is up.
3. Observe `Log.w(TAG, "Unable to connect to car service, assuming unrestricted")`.
4. `CarUxRestrictionsUtil.getInstance(ctx).getCurrentRestrictions().getActiveRestrictions()` returns
   `UX_RESTRICTIONS_BASELINE` (0), and `isRequiresDistractionOptimization()` returns `false`.

Expected: `UX_RESTRICTIONS_FULLY_RESTRICTED` (0x1ff) and `true`.

## Suggested fix

Route the exception path through the same fail-closed default as every other unknown state:

```java
} catch (RuntimeException e) {
    // Can't catch a more specific exception, because Car throws RuntimeException or
    // SecurityException on error.
    Log.w(TAG, "Unable to connect to car service, assuming fully restricted", e);
    mListener.onUxRestrictionsChanged(null);   // -> getDefaultRestrictions()
}
```

Passing `null` reuses the existing listener contract, which already maps null to
`getDefaultRestrictions()`. The change is three lines and removes a special case rather than adding
one.

## Regression test

`car-ui-lib` has no local unit test source set upstream, so this is currently unguarded. A test that
fails against the current implementation and passes against the fix:

```java
@Test
public void unreachableCarService_mustNotReportBaseline() {
    // Drive the same path the constructor's catch block takes.
    CarUxRestrictionsUtil util = /* instance whose car-service connect threw */;

    assertThat(util.getCurrentRestrictions().getActiveRestrictions())
            .isNotEqualTo(CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
    assertThat(util.getCurrentRestrictions().isRequiresDistractionOptimization()).isTrue();
}
```

## Related, lower severity

Worth mentioning in the same report only if the maintainers engage; do not bundle:

1. **`isRestricted()` is unsafe for composite masks.** It computes `(active & flags) != 0`, correct
   for its documented "any of these flags" contract. But `UX_RESTRICTIONS_FULLY_RESTRICTED` is
   0x1ff — the union of the nine restriction bits — so `isRestricted(FULLY_RESTRICTED, uxr)` reads
   as "is the vehicle fully restricted?" and actually answers "is anything restricted?". It returns
   true when only `UX_RESTRICTIONS_NO_VIDEO` is set. `UxrButton` defaults its mask to
   `FULLY_RESTRICTED`, so this is live, though in the conservative direction. A composite-safe
   sibling using `(active & mask) == mask` resolves it without changing `isRestricted`'s contract.

2. **Restrictions are collapsed to a single display.** The singleton binds via
   `context.getApplicationContext()`, which is always associated with the default display, so a
   secondary display receives the driver display's restrictions. Per AAOS, a front passenger screen
   can legitimately be at `BASELINE` while the driver's is fully restricted.

3. **No reconnection handling below API 30.** `minSdkVersion` is 28, but the
   `CarServiceLifecycleListener` path is R+. On P and Q a dead car service leaves the last value
   latched indefinitely.

---

## Disclosure posture

This is a safety-relevant defect in publicly readable Apache-2.0 code, already shipping. There is no
exploit and nothing to embargo, so ordinary public bug reporting is appropriate. Fixes for all of
the above are implemented in this fork with unit tests, and can be offered as patches if upstream
responds.

## Strengthening this before filing

Two things would make the report materially harder to dismiss, and both are already planned:

1. **A paired CTS run.** "Unmodified upstream fails X under condition Y; the patched fork does not"
   is evidence; a prose description of a `catch` block is a claim. See
   `docs/CTS-EVIDENCE-CONTRACT.md`, field 7 — the control run is against this exact commit,
   `e120cb74e`, which is also the fork point.
2. **The regression test.** Ours is in `car-ui-lib/src/test/.../CarUxRestrictionsUtilTest.java`
   (`unreachableCarService_mustNotReportBaseline`). It fails against the current implementation and
   passes against the fix, which is the cheapest possible demonstration that the defect is real.

Neither is required to file. Both are cheap if the run is happening anyway, and filing without them
is still better than not filing — see the note on testing whether this repo has anyone home.

The full taxonomy is at `docs/FAILURE-TAXONOMY.md`.

## File this with report 2

`docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md` reports a second finding from the same mechanical
audit: `CarUiContentListItem` defaults `isSecure` to false and thereby overwrites `SecureView`'s
documented secure-by-default at bind time, across the `oem-apis` plugin boundary.

Send them together. One correctness defect and one security defect, both produced by a rule rather
than by inspection, is a stronger opening than either alone — and two different safety domains is
what makes "this pattern is not UX-restriction specific" evidenced rather than asserted.
