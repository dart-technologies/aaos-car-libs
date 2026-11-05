# Host B — the `car-ui-lib` A2UI showcase

The same `:a2ui-model` surface as Host A, rendered in process on the OEM system-UI layer through the
plugin, on the driver display. This is the demo host ([DEMO](../docs/DEMO.md)); pipeline and host
comparison: [ARCHITECTURE](../docs/ARCHITECTURE.md).

```bash
cd aaos-apps-gradle-project && ./gradlew :a2ui-host-carui:assembleDebug
adb install -r -t build/a2ui-host-carui/outputs/apk/debug/a2ui-host-carui-debug.apk
adb shell am start --user current -n art.dart.a2ui.carui/.A2uiCarUiActivity
```

## car-ui-lib behaviour found by building this

- **Two content limiters, unrelated types, identical contracts.** `CarUiRecyclerView.ItemCap`
  (applied automatically, truncates silently) and `ContentLimiting` (opt-in via `UxrContentLimiter`,
  adds a "content withheld" row) share `setMaxItems(int)` and `UNLIMITED = -1` with no inheritance.
  `CarUiRecyclerViewImpl` checks `instanceof ItemCap`, so wrapping an adapter for the better limiter
  removes the automatic one. The engineering view shows both columns side by side.
- **`UxrContentLimiterImpl.setAdapter()` does not push the current limit**; it arrives only through
  the listener `start()` registers. Opt in and forget `start()`, and nothing is limited.
- **Limiter and activity observe the same `CarUxRestrictionsUtil`** in unspecified order; Host B builds
  its adapters once and mutates their shared list instead of rebuilding inside a refresh.
- **The theme is load-bearing.** Without `@style/Theme.CarUi.WithToolbar` (or `.NoToolbar`)
  `CarUiInstaller` skips the activity, no inflater factory is installed, and the
  `CarUiRecyclerView` tag — an interface — fails to inflate. A `Presentation` never gets the factory:
  use `CarUiRecyclerView.create(context)` and guard its inflater, as the handoff path does.
- **`CarUxRestrictionsAppConfigParser` counts parser events** before the root element, so
  `res/xml/uxr_config.xml` keeps its comment block.

## Driving it

Debuggable, non-`user` builds only. Synthetic readings go through `setDemoUxRestrictions` and are
badged `DEMO`; the toolbar console offers live/preset restrictions, all ten corpus cases, the phrases
`buy album` and `play`, and a handoff destination picker. The same broadcasts the recorders use:

```bash
adb shell am broadcast --user current -a art.dart.a2ui.demo.action.SET --ei art.dart.a2ui.demo.extra.ACTIVE 32
adb shell am broadcast --user current -a art.dart.a2ui.carui.action.SPEAK --es art.dart.a2ui.carui.extra.PHRASE "'buy album'"
```

Tests run on the JVM (Robolectric where car-ui-lib needs it); counts in `docs/gate-counts.tsv`.
`CarUiRendererTest` pins row counts and the provenance rule against Host A's rule restated inline, so
it would not notice Host A changing (see [STATUS](../docs/STATUS.md)).
