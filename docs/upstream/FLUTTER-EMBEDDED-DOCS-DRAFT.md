# Draft contribution: `docs.flutter.dev` embedded/automotive

**Status: drafted, NOT filed.** Item 9. The cheapest credibility available, and the only one of the
four upstream artifacts that is a documentation contribution rather than a defect report.

---

## Target

`flutter/website` — the repo behind `docs.flutter.dev`. The embedded section is thin and the
automotive story is essentially absent, despite Flutter shipping in production infotainment.

Contribution guide: `flutter/website/CONTRIBUTING.md`. Google CLA required, as with the AOSP reports.

## What to contribute, and why this specific thing

Not "Flutter works on AAOS" — that is marketing and a reviewer will treat it as such.

The gap worth filling is narrow, concrete, and currently undocumented anywhere: **a Flutter surface
in a vehicle has a legal obligation the framework knows nothing about.** Driver-distraction gating is
enforced by the platform, invisible to Dart, and getting it wrong is a safety issue rather than a
polish issue.

Proposed page: **"UX restrictions on Android Automotive"**, under embedded/automotive.

### Outline

1. **What `CarUxRestrictions` is** — the platform tells you what you may render while the vehicle is
   moving. Ten flags plus a composite. Not advisory: `distractionOptimized` activities are blocked by
   the system, and the rest is the app's responsibility.

2. **The three things a Flutter app must get right**, each with the failure it prevents:
   - **Seed from current state.** `CarUxRestrictionsManager`'s listener is change-only. A surface
     that binds while already restricted hears nothing until the next change.
   - **Fail closed.** "No reading" and "no restrictions" are byte-identical unless you force them
     apart. A `?? false` on a nullable restriction value renders video while driving.
   - **Restrictions are per-display.** A passenger screen can be `BASELINE` while the driver's is
     fully restricted. Binding to an application context collapses them onto the driver's.

3. **Why `StreamBuilder` is the trap**, with the two-line version:
   ```dart
   if (uxr?.hasAny(UxRestrictions.noVideo) ?? false) hideVideo();   // null -> video PLAYS
   if (uxr?.allowsVideo ?? false) showVideo();                      // null -> video HIDDEN
   ```
   Same defaulting habit, opposite safety. This single contrast is the most transferable thing in the
   page and applies well beyond automotive.

4. **A platform-channel sketch** — not a plugin advertisement. Enough that a reader can implement it,
   with the `ActivityAware` requirement called out: an engine-attach context is non-visual and
   `Context.getDisplay()` throws on it.

5. **Testing it without a car** — the AAOS emulator image, `--user 10` for `am start`, and
   `dumpsys car_service` to read the platform's own per-display restriction state back.

### Tone

Documentation, not a case study. No Dart Technologies branding, no links to the fork as a
dependency. Cite AOSP source for platform behaviour. If `car_uxr` is mentioned at all it belongs in a
one-line "an implementation of this exists at …", and the page must stand entirely without it.

The credibility comes from the page being correct and obviously written by someone who has done it —
not from attribution.

## Why it is cheap

Every technical claim already exists in this repo, verified:

| Claim | Evidence |
|---|---|
| listener is change-only, must seed | `CarUxRestrictionsUtil.register()`; instance 3 |
| fail-closed vs `?? false` | 43 Dart tests; the `allows*` family |
| per-display is real | `dumpsys car_service` showing `UxR: 0` on display 0 and `UxR: 511` on display 3 |
| engine-attach context throws | the PaintBooth crash; `CarUxrPluginTest.engineAttach_doesNotResolveTheUtil` |
| `--user 10` and the screencap quirk | `README.md`, found the hard way |

Writing is assembly, not research.

## Sequencing

Independent of the AOSP reports and the A2UI work — no dependency in either direction. It can go
first if a low-risk outward step is wanted before the defect reports, since it carries no claim about
anyone else's code.
