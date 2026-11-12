# Sprint 2 C2 emulator acceptance (2026-11-11)

Navigation preview → guidance walk on the local AAOS emulator. Entry criterion: Host A
installable as a signed nav app with route-preview and guidance coverage.

## Environment

Same `AAOS_35` AVD and r4 image as Sprint 1
(see [SPRINT-1-VERIFICATION](SPRINT-1-VERIFICATION.md)). Debug build of the C2 code plus its
acceptance fixes (#53); temporary direct-to-guidance launch and surface logging, both
**reverted** after the run. DisplayCompat interrupter disabled for the session, as in Sprint 1.

## Walk

Launch with the nav extra roots at route preview; guidance was rooted directly because host
tap delivery is dead in this headless setup (see gaps). Telemetry chain observed:

- `SESSION_START {root=nav, host=a}`
- `TEMPLATE_RENDERED {template=RoutePreviewNavigationTemplate, host=a}`
- `TEMPLATE_RENDERED {template=NavigationTemplate, host=a}`
- Map surface delivered to the controller at 1080x600 (temporary log line, reverted)

## Evidence

| Capture | Shows |
|---|---|
| `host-a-nav-preview.png` | Route preview: one route row with distance + time, Go action |
| `host-a-nav-guidance.png` | Active guidance: current/next maneuver, destination estimate, Stop |

Both captured with `screencap -d 4619827259835644672` to a file and pulled — no adb text
pollution this time. The guidance pixels come from a build whose only delta from #53 was the
temporary direct-launch extra; the rendered template path is identical.

## Findings (all in #53)

- Route preview listed steps as rows; the template list is the route list and row taps select
  routes, so any step tap would have crashed. One row per route now.
- `setSurfaceCallback` crashed with `SecurityException`: `ACCESS_SURFACE` was never declared.
  Declared, plus an audit rule.
- `navigationStarted()` throws with no manager callback set. The controller sets one (host-side
  stop ends announcements) and clears it on stop.

## Gaps

- Update (2026-11-12 re-walk, see [SPRINT-2-C3C4-VERIFICATION](SPRINT-2-C3C4-VERIFICATION.md)):
  the tap death was stale-snapshot state. On a fresh boot a real Go tap transitioned
  preview → guidance, and IoT toggles flip. What stays dead is the guidance Stop action
  specifically (tap + long-press + host silence) — guidance teardown is JVM-covered only.
- No tile renderer yet: the map surface is owned and delivered, but undrawn.
