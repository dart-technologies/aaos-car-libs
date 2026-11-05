# Upstream report 3: the reference plugin ignores `maxVersion`, and an ABI mismatch crashes the app

**Status: drafted, NOT filed — and now REQUIRES RE-VERIFICATION before it can be.**

> `docs/UPSTREAM-RECON.md` establishes that this fork is based on a frozen AOSP mirror, while
> `car-ui-lib` ships actively from Google Maven (2.6.0 / plugin-apis 1.5.0). The reference plugin
> examined below is AOSP sample code from February 2025 and is **not published to Maven**, so the
> current sample may already differ. The release notes for `car-ui-lib` 2.5.0 list a
> *"max version detection"* fix, which suggests this area has been worked on.
>
> **The contract argument stands regardless** — ignoring `maxVersion` defeats an additive versioning
> scheme, and the additive scheme is confirmed: 20 interfaces added across five generations, none
> ever removed. But the claim *"the reference implementation does this today"* must be re-checked
> against current sources before this goes to Google.
>
> The reproduced crash below is also now better explained. Its proximate cause is a
> `default`-versus-`abstract` divergence in `LayoutStyleOEMV1.getSpanSize(int)` between the AOSP tree
> and every released artifact — see the recon document. Version negotiation is what should have
> contained that mismatch; it is not what created it.

**This one has something the others do not: a reproduced crash.** Reports 1 and 2 describe defects
whose consequences we reasoned about. This one we hit, spent hours misdiagnosing, and then fixed by
changing the environment rather than the code.

---

## Where to file

| Route | Target |
|---|---|
| Bug | [issuetracker.google.com](https://issuetracker.google.com) → Android Automotive OS |
| Patch | `android-review.googlesource.com` → `platform/packages/apps/Car/libs` |

A patch is plausible here, unlike report 2 — the contract already specifies the correct behaviour.

## Title

`car-ui-lib referencedesign: PluginVersionProviderImpl ignores maxVersion, so an app newer than the
plugin dies with AbstractMethodError instead of falling back`

## The contract

`PluginVersionProviderOEMV1.getPluginFactory` is explicit about both the requirement and the escape
hatch:

> OEMs should aim to return the highest version of the factory possible that is **<= `maxVersion`**.
> If the plugin is not able to provide that version, it **may return null**, in which case
> car-ui-lib will fall back to it's static, uncustomized implementation.

Two things are specified: honour `maxVersion`, and when you cannot, return `null` so the app
degrades to the built-in implementation.

## The reference implementation does neither

```java
// referencedesign/plugin/src/main/java/com/android/car/ui/plugin/PluginVersionProviderImpl.java
public class PluginVersionProviderImpl implements PluginVersionProviderOEMV1 {
    @Override
    public Object getPluginFactory(int maxVersion, Context context, String packageName) {
        return new PluginFactoryImpl(context);
    }
}
```

`maxVersion` is never read. There is no version check, no `null` path, and no validation — the
parameter is inert. The same is true of `packageName`, though that one is optional by the contract.

This is the file an OEM copies when starting a plugin. Whatever it does becomes the pattern.

## What it costs, reproduced

The AAOS emulator system image ships a build of this plugin at
`/system/app/car-ui-lib-proxyplugin/`. Running `car-ui-lib`'s own instrumented suite from a tree
whose `oem-apis` is *newer* than the shipped plugin's:

```
CarUiRecyclerViewTest > testGridLayout[1] FAILED
  java.lang.AbstractMethodError: abstract method
    "int com.android.car.ui.plugin.oemapis.recyclerview.LayoutStyleOEMV1.getSpanSize(int)"
  at com.chassis.car.ui.plugin.recyclerview.RecyclerViewAdapterProxyV1$3.getSpanSize(...:201)

Test run failed to complete. Instrumentation run failed due to Process crashed.
```

`getSpanSize(int)` is a **default** method in the current `oem-apis`. The shipped plugin was built
before it existed, so its copy of the interface has no implementation, and the call is unresolvable
at runtime.

Consequences we measured:

| | |
|---|---|
| the process | **crashes** — not an exception the caller can handle |
| the test suite | dies at **227 of 497**, taking 16 test classes with it |
| the reported baseline | **50** failures instead of the true **13** |
| `car-rotary-lib` | **45** failures instead of the true **6**, and 4 tests never running at all |

Rebuilding the plugin from the same tree and pushing it over the shipped one fixes all of it.

**In production the same mismatch is an app crash on a head unit**, whenever the plugin on the
system image is older than the `car-ui-lib` an app ships — which is the normal state of affairs,
since apps update far more often than system images.

## Why the fallback exists, and why not taking it is the defect

The contract's `null` return is designed for exactly this. Had the reference implementation compared
`maxVersion` against what it can actually provide and returned `null` on a mismatch, `car-ui-lib`
would have used its static implementation and the app would have rendered — unstyled, but alive.

Instead the failure path produces the *unsafe* value: an incompatible factory, handed over
confidently, that detonates on first use.

That shape is the reason we are filing this alongside reports 1 and 2. All three are the same
decision made the same way — **at a boundary where something can go wrong, the code produces the
permissive or optimistic value rather than the safe one**:

| Report | Boundary | Failure produces | Safe value available |
|---|---|---|---|
| 1 | car service unreachable | `BASELINE` — no restrictions | `FULLY_RESTRICTED` |
| 2 | list item never opted in | `isSecure = false` | `SecureView`'s documented `true` |
| **3** | **plugin/app ABI mismatch** | **an incompatible factory** | **`null` → documented fallback** |

Report 3 is the one where the safe value is not a judgement call. It is written into the interface
documentation as the thing to return.

## The app side has the mirror defect, and this fork carries it

Report 3 is about the plugin ignoring `maxVersion`. While preparing to adopt `plugin-apis 1.5.0` we
found `car-ui-lib`'s **own** negotiation code doing the complementary thing, in
`OemApiUtil.getPluginFactory`:

```java
Object factory = versionProvider.getPluginFactory(1, pluginContext, appPackageName);
if (factory instanceof PluginFactoryOEMV1) {
    oemPluginFactory = new PluginFactoryAdapterV1((PluginFactoryOEMV1) factory);
} else if (classExists("...PluginFactoryOEMV2") && factory instanceof PluginFactoryOEMV2) {
    oemPluginFactory = new PluginFactoryAdapterV2((PluginFactoryOEMV2) factory);
}
```

Two defects:

1. **It advertises `maxVersion = 1`** while being perfectly capable of consuming V2. A
   contract-honouring plugin would hand back a V1 factory, and the V2 branch would be dead code. The
   app under-reports what it supports.
2. **The chain is in ascending order.** `V1` is tested first, so a factory implementing both is
   adapted as V1 and its V2 capabilities are discarded. The file's own comment, twelve lines above,
   says *"in descending version order so that higher versions are preferred"* — that instruction is
   attached to the version-provider chain and was not followed for the factory chain.

So the negotiation fails from both ends: the plugin ignores the ceiling, and the app both understates
its ceiling and prefers the floor. Between them, the version scheme that exists to prevent ABI
mismatches accomplishes nothing — which is consistent with the `AbstractMethodError` this report
documents.

**This one is ours to fix, not Google's**, and it is being fixed as part of adopting
`plugin-apis 1.5.0`: advertise the highest `PluginFactoryOEMV#` actually implemented, and test the
chain highest-first. Recording it here because a report criticising the reference plugin for
ignoring `maxVersion` should say plainly that the library on the other side of the same call was
also getting it wrong, and that we found it by trying to do the thing we are recommending.

## Suggested fix

```java
public class PluginVersionProviderImpl implements PluginVersionProviderOEMV1 {
    /** The highest PluginFactoryOEMV# this plugin implements. */
    private static final int PROVIDED_VERSION = 2;

    @Override
    public Object getPluginFactory(int maxVersion, Context context, String packageName) {
        if (maxVersion < PROVIDED_VERSION) {
            // Contract: return null rather than a factory the app cannot speak to, so car-ui-lib
            // falls back to its static implementation instead of crashing on first call.
            return null;
        }
        return new PluginFactoryImpl(context);
    }
}
```

The constant has to be maintained by hand, which is unattractive — but the alternative currently in
the tree is a runtime crash with no diagnostic pointing at the version boundary. A comment on the
constant naming the failure mode would go a long way for the OEMs copying this file.

## Tests

This module had **zero tests across 5,502 lines**. We added
`PluginVersionProviderImplTest` (6 tests) which pins the current behaviour as *characterisation* —
including `maxVersion = 0` and negative values, neither rejected. They are written so that when the
fix lands the contract-violation tests fail, and the note on each says to delete rather than adjust
them.

## How this was found

Not by reading the code. By a 497-test suite truncating at 227 for hours, being misdiagnosed three
times — as suite flake, as a dependency-bump regression, as emulator degradation — and finally by
reading the crash text instead of the counts.

The executed-count guard flagged the truncation correctly every single time. What it could not say
is *why*, and every explanation attempted before reading the log was about the wrong layer.
