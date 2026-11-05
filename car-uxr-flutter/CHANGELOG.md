# Changelog

## 0.2.0

**Breaking.** Taken deliberately while the consumer count is zero — the next roadmap item is the
first consumer, so this is the last free moment for it. Under `0.x` semver a breaking change goes in
the minor slot, hence 0.2.0 rather than 0.1.1.

* **`CarUxRestrictionsState`'s constructor is private.** The only route to a permissive state is
  `CarUxRestrictionsState.delivered`. `unavailable()`, `failClosed`, and any payload `fromMap`
  cannot fully parse are forced to `fullyRestricted` regardless of the flags offered.

  This closed a live defect: a payload could parse *partially* — valid flags, unparseable
  provenance — and yield `active=0x0` with `source=neverDelivered`. Unrestricted, on the strength of
  something we could not fully understand.

  Migration: replace `CarUxRestrictionsState(...)` with `CarUxRestrictionsState.delivered(...)` for
  genuine readings, or `CarUxRestrictionsState.unavailable(source)` otherwise.

* **Added the `allows*` family** — `allows(mask)` plus nine named accessors. Inverts the polarity so
  the shorthand callers actually write fails closed: `uxr?.allowsVideo ?? false` denies, where
  `uxr?.hasAny(noVideo) ?? false` permits. `allows()` also requires `isTrustworthy`, so permission
  cannot be granted from a reading that never arrived. `hasAny`/`hasAll` remain for genuine
  "is this restricted?" questions.

* **Added `CarUxrBuilder`**, now the documented call site. Hands the builder a non-nullable state,
  fail-closed until a real reading arrives — no nullable `snapshot.data` to default incorrectly. The
  raw `CarUxr.restrictions` stream remains public.

* **Fixed the seed/subscribe race.** The stream seeded before its host subscription was live,
  leaving a microtask window; an event landing in it was dropped, stranding that subscriber on the
  seed. A widget mounting exactly as restrictions tightened kept rendering the permissive value.
  Also fixed a hang: cancelling one subscriber blocked, because `async*` does not release from
  `await for` on cancel.

## 0.1.0

Initial binding: `CarUxRestrictions`, provenance and owning display exposed to Dart, fail-closed at
every boundary.
