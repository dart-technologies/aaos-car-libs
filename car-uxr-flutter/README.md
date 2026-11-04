# car_uxr — Android Automotive UX restrictions for Flutter

Exposes `CarUxRestrictions`, its delivery provenance and the owning display to Dart, and fails closed
wherever the signal cannot be trusted. Android only, one package: there is no iOS equivalent.

**The dependency runs one way: `car-uxr-flutter` → `car-ui-lib`.** Fail-closed defaults, per-display
keying, provenance and composite masks live in `car-ui-lib`'s Java, so a Kotlin OEM gets the same
guarantees without Flutter. The module is excluded from `aaos-apps-gradle-project` by default, and the
Flutter Maven repository is added only when it is enabled:

```bash
./gradlew -PwithFlutter :car-uxr-flutter:assembleDebug     # or CAR_UXR_WITH_FLUTTER=1
```

## Usage

```dart
CarUxrBuilder(
  builder: (context, uxr) => uxr.allowsVideo ? const VideoSurface() : const NowPlayingText(),
);
```

- **`CarUxrBuilder`, not `StreamBuilder`.** It hands over a non-nullable state that is fail-closed
  until a genuine reading arrives; a nullable `snapshot.data` invites `?? false`, which permits.
- **`allows*` for rendering decisions.** `uxr?.allowsVideo ?? false` hides video on null;
  `uxr?.hasAny(UxRestrictions.noVideo) ?? false` plays it. Keep `hasAny`/`hasAll` for "is this restricted?".
- **Use the stream, not the getter, to seed.** `CarUxr.restrictions` self-seeds and replays the last
  state to every subscriber. `CarUxr.current()` is for one-shot questions; get-then-subscribe loses changes.
- **Composites**: `uxr.isFullyRestricted` or `hasAll(mask)`. Never compare `activeRestrictions` to
  `fullyRestricted` with `==`, and never `hasAny(fullyRestricted)`.
- **Provenance**: `uxr.isTrustworthy` is false for `neverDelivered` and `disconnected`, the
  conservative fallback rather than a measurement.

## Guarantees

- A permissive state is unrepresentable without a delivered reading: the constructor is private, and
  `unavailable`, `failClosed` and any payload `fromMap` cannot fully parse are forced to fully restricted.
- Everything unknown is restricted: missing plugin, channel error, malformed payload, unknown enum,
  missing key, detached activity. Stream errors arrive as a fail-closed **event**, not a stream error.
- The Dart stream subscribes to the host before emitting its seed, so a change landing in between is
  not lost (`test/seed_subscribe_race_test.dart`).

## Host side (`CarUxrPlugin.java`)

1. `onListen` seeds synchronously, fail-closed when no activity is attached, and re-seeds on attach.
2. The listener is held in a strong field (`CarUxRestrictionsUtil` keeps observers weakly).
3. The util is resolved at `onAttachedToActivity`, never `onAttachedToEngine`: the engine's
   application context is non-visual and has no display.
4. Registration is released on `onCancel`, `onDetachedFromEngine` and activity detach; events are
   delivered on the main looper with registration generations, so late callbacks are dropped.

The plugin exposes `displayId` and not seat or occupant zone: seat → display mapping is unproven on
the available emulator (one driver zone); see [ARCHITECTURE](../docs/ARCHITECTURE.md#per-display).

## Tests

| Layer | How |
|---|---|
| Dart model, fail-closed paths, seeding races, wire contract | `flutter test` (pure Dart, no device) |
| Plugin host logic | `./gradlew -PwithFlutter :car-uxr-flutter:testDebugUnitTest` |
| End to end on an AAOS emulator | `example/integration_test` (reports `source: delivered` from car service) |

Integration tests do not cover the seeding races; those are timing bugs pinned deterministically in
unit tests. Run the example with `flutter run`: an APK installed with `adb install` sits on the splash.
Verified against the Flutter 3.44.9 embedding; the AAR packages only `art/dart/car_uxr` classes.
