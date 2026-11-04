# Upstream report 2: `SecureView`'s secure-by-default is overwritten at bind time

**Status: drafted, NOT filed — premise RE-VERIFIED 2026-08-20, and a NEW question raised that must be settled first.**

> **The pre-filing check `docs/UPSTREAM-RECON.md` asked for is done, and the answer is no.** That
> document said to "check `ContentListItemOEMV2` before filing — `mIsSecure`'s default may already
> have changed." It has not. In the shipped `com.android.car.ui:car-ui-lib-plugin-apis:1.5.0`, the
> `ContentListItemOEMV2$Builder` constructor sets it **explicitly**:
>
> ```
> 32: iconst_0
> 33: putfield  #116   // Field mIsSecure:Z
> ```
>
> Permissive by explicit assignment, not merely by omission, in the version that runs on real head
> units. The report is stronger than when it was drafted, not weaker.
>
> **This fork STILL carries the defect, and trying to fix it produced a finding that may undermine
> this report's central claim.** `CarUiContentListItem.mIsSecure` was defaulted to `true`, matching
> `SecureView`, and reverted the same day. Measured on one machine, `CarUiListItemTest` (44 tests)
> four runs each way:
>
> | default | runs | failures |
> |---|---|---|
> | `false` (as upstream) | 4 | **0** |
> | `true` (the fix) | 4 | 3 tests in 1 run — plus 2 more failing runs earlier, including a gate run |
>
> The failures are click-shaped — `view.isChecked() was <false>` after a click Espresso reported as
> performed — i.e. the touch was **filtered**, which is exactly what `SecureView` does when it
> believes the window is obscured.
>
> **So this report's "no test observes it" is one-directional.** Mutating `SecureView` toward
> permissive changes nothing, as measured. Switching the default toward secure makes the filtering
> fire and the suite notices at once.
>
> **This raises a question the report does not currently address: is upstream's permissive default
> an oversight, or a deliberate accommodation of AAOS overlay windows?** If list-item touches are
> intermittently obscured on an AAOS head unit, then defaulting secure would silently drop user
> input, and `false` would be a design decision rather than a bug. The candidate mechanism was
> checked and does not survive inspection — `com.android.car.rotary` holds a `SYSTEM_ALERT_WINDOW`,
> but it is `pfl=TRUSTED_OVERLAY` and sized `(0,0)(0x0)`, and trusted overlays are excluded from
> obscured-flagging. So there is a measured correlation and no mechanism.
>
> **Do not file until that question is settled.** Filing a report that calls a deliberate
> accommodation an oversight would be worse than not filing at all.

**Status: drafted, NOT filed.** Same posture as `AOSP-FAIL-OPEN-REPORT.md` — filing is outward-facing
under Dart's name and needs a Google account we do not have.

**File this together with report 1, not instead of it.** Two findings — one correctness, one
security — produced by the same mechanical audit rather than by inspection is a substantially
stronger opening than either alone, and it is what makes "this pattern is not UX-restriction
specific" evidenced rather than asserted.

---

## Where to file

| Route | Target |
|---|---|
| Bug | [issuetracker.google.com](https://issuetracker.google.com) → Android Automotive OS |
| Patch | `android-review.googlesource.com` → `platform/packages/apps/Car/libs` |

This one is a **design question**, not a defect with an obvious patch — see "Why we are not
proposing a fix" below. File the bug; do not open a CL.

## Title

`car-ui-lib: CarUiContentListItem defaults isSecure to false, silently disabling SecureView's
obscured-touch filtering`

## Summary

`SecureView` exists to reject touches while the window is obscured — the standard Android defence
against overlay ("tapjacking") attacks — and it deliberately defaults to **secure**:

```java
// car-ui-lib/src/main/java/com/android/car/ui/SecureView.java
private boolean mSecure = true;

/** Sets if this view should ignore touch events when the window is obscured. Default true. */
public void setSecure(boolean secure) { ... }
```

`CarUiContentListItem` carries the same property and does **not** default it:

```java
// car-ui-lib/src/main/java/com/android/car/ui/recyclerview/CarUiContentListItem.java
private boolean mIsSecure;      // no initialiser -> false
```

`CarUiListItemAdapter` then pushes that value onto the view on every bind:

```java
((SecureView) mTouchInterceptor).setSecure(item.isSecure());
((SecureView) mReducedTouchInterceptor).setSecure(item.isSecure());
((SecureView) mActionContainerTouchInterceptor).setSecure(item.isSecure());
```

So binding any `CarUiContentListItem` that has not explicitly called `setSecure(true)` **downgrades
the SecureView from its documented secure default to insecure.**

## Why this is not simply "opt-in by design"

> **BLOCKING CORRECTION (2026-08-20). This section is answered by the commit that introduced the
> API, and the answer is "it is opt-in by design".**
>
> `git log -1 --format=%B fc27816ff` — "Add setSecure() to list items":
>
> > *"PermissionController needs to not allow the grant permission [buttons to be clicked when
> > there is a full or partial overlay onscreen]… Add a setSecure() method **that when set**, will
> > cause the CarUiListItem to not respond to touches"*
>
> Opt-in, for one named caller, stated in the change that added it. The argument below was written
> without reading that commit. It may still be worth arguing that the default is wrong — a
> tapjacking defence that every caller must remember to switch on is a defence most callers will
> not have — but that is a DESIGN DISAGREEMENT, not the defect report this document currently
> presents. Filing it as written tells the author they overlooked something they decided.
>
> Related: `SecureView` is instantiated only from `car_ui_list_item.xml` and
> `car_ui_list_item_compact.xml`, so its `mSecure = true` initialiser is unreachable by
> construction — which weakens "a deliberate secure default is being overwritten" to "two
> defaults disagree, and only one of them is ever read".

Three observations, each checkable in under a minute:

1. **`SecureView` chose the opposite default, explicitly and with documentation.** `= true` plus a
   javadoc stating "Default true" is a decision, not an accident. Two components in one library now
   express the same property with opposite defaults, and the un-chosen one wins because it is
   applied last.

   This is not a single author's preference. `mSecure = true` appears in **both** `SecureView`
   implementations in the tree — `car-ui-lib/.../SecureView.java:35` and
   `referencedesign/plugin/.../SecureView.java:31`. Two independent components chose secure by
   default; one list-item default overrides both.

2. **On the OEM boundary the permissive value is written deliberately**, in a block where defaults
   were plainly being considered:

   ```java
   // oem-apis/.../recyclerview/ContentListItemOEMV1.java, Builder
   private boolean mIsChecked = false;
   private boolean mIsEnabled = true;     // <- a deliberate non-default
   private boolean mIsActivated = false;
   private boolean mIsSecure = false;
   ```

   `mIsEnabled = true` shows the author was choosing defaults on those very lines.

3. **There is no opt-in path.** Across the whole repository at `e120cb74e` there is no XML attribute
   for it, no builder default other than `false`, and every `setSecure` call is
   `setSecure(item.isSecure())` — in `CarUiListItemAdapter`, `PluginFactoryAdapterV2`, and the
   reference design's `ListItemAdapter`. An app must call `setSecure(true)` per item, and nothing in
   the API surface suggests it needs to.

## A second occurrence in the same API surface

`ContentListItemOEMV1` is not the only Builder in `oem-apis` that defaults a safety-relevant property
permissive. `MenuItemOEMV1.Builder` does the same:

```java
// oem-apis/.../toolbar/MenuItemOEMV1.java, Builder
private boolean mIsVisible = true;
private boolean mIsEnabled = true;
private boolean mIsPrimary = false;
private boolean mIsTinted = true;
private boolean mIsRestricted = false;      // <- restriction-positive, permissive default
```

Again in a block where defaults are plainly being chosen — three of the five neighbours are set to
non-Java-default values.

**Today this one is not reachable**, and we say so plainly: `MenuItemAdapterV1:62` calls
`.setRestricted(mClientMenuItem.isRestricted())` on every conversion, and that value is computed by
`CarUxRestrictionsUtil.isRestricted(...)`, which returns `true` on a null reading. So the permissive
default is overwritten with a fail-closed value before anything observes it.

It is safe **because exactly one call site exists and happens to set it.** An OEM plugin author
constructing a `MenuItemOEMV1` directly — which the Builder is public API precisely to allow — gets
`isRestricted() == false` and menu items that never render `RESTRICTED_STATE`.

Lower stakes than the list-item case: `isRestricted()` drives drawable styling, while clicks are
gated separately by `isEnabled()`. The failure is *restricted items not looking restricted*, not
restricted items being clickable.

We include it because **one permissive Builder default is an oversight; two in the same API surface
is a convention.** Both are the same decision for the same owner: a safety-relevant property
defaulted permissive in a public Builder, where flipping it changes behaviour for external
consumers. Whatever is decided for `mIsSecure` should probably be decided for `mIsRestricted` at the
same time.

## No test in the library can catch this, in either direction

Measured, not assumed. `SecureView`'s field initialiser was mutated from `true` to `false` — which
is *exactly* the condition `CarUiContentListItem` already produces on every bind — and
`car-ui-lib`'s full instrumented suite was re-run:

| | tests | failures |
|---|---|---|
| unmutated | 497 | 13 |
| `mSecure = false` | 497 | 13 — **identical sets, zero new** |

(An earlier draft of this report cited 497/50. That baseline was measured against the proxy plugin
shipped in the AAOS emulator image, which is built against an older `oem-apis` than the tree under
test and crashes the run at 227/497. Rebuilding the plugin from the same tree drops the baseline to
13 and lets the suite complete. The `mSecure` result is unchanged either way — it was blind in both
environments — but every other figure in this report is from the corrected one.)

No test observes it. The same run showed the suite *does* have strong sensitivity elsewhere —
mutating `MenuItem.isEnabled` breaks **28** tests, `setTitle` breaks 5, and disabling
`CarUxRestrictionsUtil.isRestricted` outright breaks **21**. So this is a specific blind spot in an
otherwise responsive suite, not a weak suite.

The reason appears structural rather than neglectful: observing obscured-touch filtering requires
staging an actual overlaying window, which instrumentation cannot readily create.

> **CORRECTION (2026-08-20): this is false, and the counter-example is a test in this project's own
> history.** `CarUiListItemTest#test_secureListItem_doesntCallOnClickListeners` did exactly this —
> a companion APK (`car-ui-lib/src/androidTest/overlayservice/OverlayTestService.java`, 98 lines,
> `TYPE_APPLICATION_OVERLAY`, separate uid, installed through `car-ui-lib/AndroidTest.xml`) raised
> a real overlay and asserted `verify(clickListener, times(0)).onClick(any())` for a secure item
> against `times(1)` for an insecure one. It was removed in `627a58b5a`, and the commit message
> gives the reason in four words: *"It doesn't work in gradle."*
>
> So the coverage gap this report describes is real but MISATTRIBUTED. It is not that the condition
> resists instrumentation; it is that a Gradle build cannot install the second test APK the test
> needs. Restoring the test under Soong would close it. This paragraph, as written, tells the
> project's own maintainers that something is impossible when one of them wrote it and deleted it
> for a build reason.

The only other
path we found with the same property is the OEM plugin boundary (below).

Your own suite supplies the control for that explanation. Disabling `CarUiRecyclerViewImpl`'s
`LIMIT_CONTENT` cap — also vehicle state, also safety-relevant — **is** caught, by
`CarUiRecyclerViewTest#testUxRestriction_withLimitedContent_setsMaxItems`. The difference is that
`CarUxRestrictionsUtil` exposes `@VisibleForTesting setUxRestrictions(...)`, so that one test can
inject a mocked `CarUxRestrictions` instead of waiting for car service. Where a seam exists the
path is observable; where none exists it is not. `SecureView` has no equivalent seam for the
obscured-window condition — which is why we think the gap is a missing seam rather than a missing
test, and why adding tests in the existing style would not have closed it.

We mention it because it bears on how the decision below should be weighed: whichever option is
chosen, **regression risk cannot be assessed by running the existing tests.** They will pass either
way.

## Blast radius

Wider than `car-ui-lib`. Because `ContentListItemOEMV1` is part of `oem-apis` — the plugin contract
— and `PluginFactoryAdapterV2` propagates the value across it, the affected population is
`car-ui-lib` consumers **plus every OEM plugin built against that interface**.

It is also the **default path, not a failure path**. No error condition is required: normal
operation, every bind.

## What we are *not* claiming

We have not demonstrated an exploit, and we are not asserting a severity rating.

`FLAG_WINDOW_IS_OBSCURED` concerns obscuring **windows**, not views, so an app drawing inside its own
window is unaffected, while one using a `Dialog` or `PopupWindow` may not be. Sizing the real
exposure requires confidence about Android's same-app obscured semantics that we do not have — which
is exactly why we are reporting the inconsistency rather than proposing a patch.

The defensible claim is narrow and, we think, sufficient: **a component that documents itself as
secure-by-default is silently switched to insecure by the standard way of using it.**

## Why we are not proposing a fix

Initialising `mIsSecure = true` would restore consistency with `SecureView`'s stated default, but it
changes runtime behaviour for every existing consumer and every OEM plugin. Whether that is a
correction or a compatibility break is a judgement only the API owner can make.

Three options for the owner, in rough order of disruption:

1. **Default `mIsSecure` to `true`** in `CarUiContentListItem` and `ContentListItemOEMV1.Builder`,
   matching `SecureView`. Safest; may reject touches in apps that legitimately overlay their own
   lists.
2. **Do not push the value when unset.** Distinguish "not specified" from "explicitly false" so
   `SecureView` keeps its own default unless overridden. Preserves existing behaviour for anyone who
   opted in.
3. **Keep the current behaviour and document it.** If permissive-by-default is intended, say so in
   both javadocs, and correct `SecureView`'s "Default true" — which is currently true of the class
   and false of every list item using it.

We have no stake in which. But **options 1 and 2 are not alternatives**, and a list of three invites
the assumption that they are — so it is worth saying plainly that there are two defects here at two
sites, sharing one symptom.

### The two sites are different defects

**Site A — `ContentListItemOEMV1.Builder:249`. A decision was made permissively.** The field is
written out explicitly, three lines below a deliberate `mIsEnabled = true`, among five initialisers
of which three restate the Java default. Nothing is missing; a value was chosen. Only 1 or 3 apply
— option 2 has nothing to act on.

**Site B — `CarUiContentListItem:130`. A state was lost.** The field is declared bare:

```java
private boolean mIsSecure;      // no initialiser
```

so `false` is indistinguishable between *"the caller asked for insecure"* and *"the caller never
expressed an opinion."* `CarUiListItemAdapter` pushes that undifferentiated value onto `SecureView`
on every bind (lines 257/263/269) and `PluginFactoryAdapterV2:500` carries it across the plugin
boundary, so the ambiguity propagates to every consumer.

| | Site A — Builder | Site B — bare field |
|---|---|---|
| **1.** default to `true` | reverses the decision — **fixes** | **relocates** the ambiguity to the safe side; unset and explicit-`true` now conflate, and both are secure |
| **2.** distinguish unset | nothing to act on | **recovers the state** — the only option that does |
| **3.** document as intended | documents the decision | documents a value whose two meanings remain merged |

### Option 2 is already your own convention

We are not proposing an unfamiliar pattern. `car-uxr-client-lib` does exactly this, in
`CarUxRestrictionsAppConfig.ListConfig`:

```java
// car-uxr-client-lib/src/com/android/car/uxr/CarUxRestrictionsAppConfig.java
private final Integer mContentLimit;                     // <- boxed, so "unset" is representable
@StringRes private final Integer mScrollingLimitedMessageResId;

@Nullable public Integer getContentLimit() { return mContentLimit; }
```

and `UxrContentLimiterImpl` reads it precisely as the distinction we are describing:

```java
if (mListConfig == null || mListConfig.getContentLimit() == null) {
    return carUxRestrictions.getMaxCumulativeContentItems();   // no override specified
}
return mListConfig.getContentLimit();                          // an override, even if it is 0
```

Boxed rather than primitive, so *"the app said nothing"* is a different state from *"the app said
zero"*, and the fallback is chosen on that distinction. Site B is the same problem — *"the caller
said nothing"* versus *"the caller said insecure"* — with the opposite representation.

So both patterns already exist in this codebase, in two libraries maintained by the same owner. Our
report is not that one of them is novel; it is that `mIsSecure` is on the wrong side of a line the
project has already drawn elsewhere.

### What that means for choosing

- **Option 1 alone** leaves site B's conflation in place. It stops being a *safety* problem, because
  the two merged cases both come out secure — but nothing downstream can still tell an opt-in from a
  caller that never had an opinion, so the same ambiguity is available to the next change.
- **Option 2 alone** leaves site A still choosing permissive, for every OEM plugin constructing the
  Builder directly.
- **Option 3** is coherent for both, and is the only one that requires no behaviour change — but it
  should then also correct `SecureView`'s "Default true", which is currently true of the class and
  false of every list item using it.

Only option 3 stands alone. A behaviour-changing fix that covers both sites is 1 at site A **plus**
1 or 2 at site B, and the choice between those two turns on whether you want the unset case merely
made safe or made visible.

We raise this because "distinguish unset from false" reads like a middle-disruption compromise when
it is the structurally distinct choice, and because presenting three options in one list is exactly
how the second defect gets closed on paper without being closed in code. Which route to take is
still yours.

## How this was found

By a mechanical audit, not by reading the code looking for bugs:

> At every safety boundary, identify the value produced by failure, absence, or default — and ask
> whether it is the permissive one.

Run across `car-ui-lib`, `car-apps-common`, `car-media-common` and `car-uxr-client-lib`. Five
predicates cleared, one cleared on review with reasons recorded, one failed — this one. The same
audit independently reproduced the fail-open in report 1.

That the rule surfaced a defect in **tapjacking defence** as well as in **driver-distraction gating**
is the reason we think it is worth your attention as a pattern and not only as two tickets. Full
method and results: `docs/FAILURE-TAXONOMY.md` in the fork.
