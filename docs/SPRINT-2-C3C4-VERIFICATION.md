# Sprint 2 C3/C4 emulator verification (2026-11-12)

IoT + weather template walks and the media-controller walk on the local AAOS emulator,
plus a C2 re-walk with real taps. Entry criteria: Host A installable with IoT/weather
coverage (C3), and media passing a real controller (C4).

## Environment

| | |
|---|---|
| AVD | `AAOS_35` (`automotive_1080p_landscape`), wipe-data cold boot |
| Image | `system-images;android-35-ext15;android-automotive;arm64-v8a` r4 (same as Sprint 1) |
| Build | Debug at `3568df5` (main with #59); no temporary instrumentation this time |
| Session tweaks | DisplayCompat interrupter disabled (`pm disable-user`), as in Sprint 1/C2 |

Deviations from the earlier runs, stated plainly:

- The `booted` snapshot would not load (`virtio-snd` state error) and a cold boot on
  the stale userdata hung without guest writes, so the run used `-wipe-data -no-audio`
  with `-no-snapshot-save`. No snapshots were written (disk was at 6–12 GiB free).
- `-no-audio` means ExoPlayer cannot render audio: playback attempts end in
  `PlaybackState ERROR (Bluetooth audio disconnected)`. Transport *routing* is proven;
  audible playback is not.

## Walks

Each template root launches via its boolean extra on `CarAppActivity`, with
`am force-stop` between launches (the single-task activity otherwise absorbs a new
extra into the live session). All captures use `screencap -d 4619827259835644672`
to a file, then pulled — no adb text pollution.

### C3: IoT + weather

- IoT root renders the device list (`SESSION_START {root=iot}`, `ListTemplate`):
  living-room light, porch light, hall thermostat with state toggles.
- **Two real taps flipped two toggles** (living-room On→Off, porch Off→On), each
  re-rendering `ListTemplate`. Full interaction, not just pixels.
- Weather root renders the Midtown forecast (`SESSION_START {root=weather}`,
  `ListTemplate`): current + hourly + daily rows.

### C4: media controller

- The library session publishes as `a2ui-media` (`active=true`); media keys route
  to the player (`NONE` → play attempt).
- The car's source picker initially did **not** list us. Two manifest-level root
  causes, both fixed in #59 and re-verified here:
  1. The picker enumerates `android.media.browse.MediaBrowserService` services; we
     declared only the media3 action.
  2. `MediaSource.isMediaTemplate` skips browse services from car-app packages
     without an opt-in (`Skipping MBS ... belonging to non media template app`);
     the service's meta-data must contain `androidx.car.app.launchable=true`.
     A car-app `MEDIA` category was tested and is *not* the criterion.
- After the fix: the picker lists **A2UI Showcase**, selecting it browses our
  **Anchored FM** catalog (Downtown Loop / Park Ave Shuffle / Carmine St Blues),
  and tapping a track routes play to our ExoPlayer (errors only on the missing
  audio output — the `-no-audio` artifact above).
- Audit rules pin both manifest entries (`check-aaos-manifest.py`, 21→25 tests).

### C2 re-walk (taps)

- Nav preview renders one route row (Joe's Pizza, 3800 m · 13 min) with Go.
- **A real Go tap transitioned preview → guidance** (`NavigationTemplate`, Stop +
  estimate). The C2 report's "tap delivery is dead" gap was stale-snapshot state:
  on this fresh boot, template-body taps (Go, IoT toggles) all deliver.
- The guidance Stop action still does not fire (tap, long-press, host logs silent).
  Guidance-Activity teardown stays JVM-covered only.

### P5 lane (bonus, error path)

With no vendor keys and no staging backend on the worker, only the failure path
was exercisable: dev gateway (`serve.py --offline`) over `adb reverse`, Host B
asked via the `AGENT` broadcast. The turn reached the gateway over TLS (logged
`provider=claude ... status=503`) and the host logged
`agent turn failed: HTTP; keeping the current surface` — the P5 code executes
on-device and fails closed. A successful turn still needs staging or keys.

## Evidence

| Capture | Shows |
|---|---|
| `host-a-iot-list.png` | C3 device list with toggles |
| `host-a-iot-toggled.png` | Same list after two real taps flipped two toggles |
| `host-a-weather-list.png` | C3 Midtown forecast rows |
| `host-a-media-sources.png` | C4 picker listing A2UI Showcase |
| `host-a-media-browse.png` | C4 Anchored FM catalog in the car media center |
| `host-a-nav-guidance.png` | Replaced: guidance after a real Go tap (was temp-direct build) |

## Gaps

- Guidance Stop taps do not reach the app (3 attempts, host silent). Body-action
  taps work; this is specific to the guidance ActionStrip/header region.
- Audible playback unverified (`-no-audio` boot). Needs a boot with audio.
- No tile renderer yet (unchanged): the guidance map surface is owned, delivered,
  and drawn black.
- P5 success path needs staging or vendor keys; only the failure path ran here.
- The `booted` snapshot is unloadable; the next run pays a cold boot again unless
  somebody re-saves it with free disk.
