# Demo

Host B (`art.dart.a2ui.carui`) on `AAOS_35_1408p_GoogleAPIs`. Stories are data
(`a2ui-automotive/demo/nyc-scenarios-v1.json`), speech is prerecorded, and motion is a live car-service
transition (`cmd car_service emulate-driving-state park|drive`), never a synthetic mask.

No keys needed to check it: the brief, audio guide and café pick are committed with their citations, and the
gateway, ranking and recorder tests replay them offline (commands in the README).

## NYC stories

| Scenario | s | Corpus case | Story | Proves |
|---|---:|---|---|---|
| `nyc_poi_order` | 78 | `poi_discovery_order` | Parked at Hudson Yards, bound for Brooklyn Museum: find coffee on the route, order ahead; drive → search and order go, route stays | Voice and touch share one policy; live re-projection |
| `nyc_curated_video` | 78 | `big_buck_bunny` | Waiting in Long Island City: open a curated short; drive → video view and decoder close, the audio-only control keeps playing | `NO_VIDEO` drops video, not audio |
| `nyc_concierge` | 90 | `poi_discovery_order`, then `big_buck_bunny` | Fifth House: parked order, brand audio kept in motion, visual chapter on arrival; closes on the fast foreground and higher-effort background lanes | The journey gets quieter, not unsafe; agents never control the car |
| `nyc_met` | 143 (13.5 + 121 + 8.5) | `met_visit`, then `ev_charge_coffee` | Preamble from the recording's own frames: the parked full screen as unsafe in motion, then locked out, then the real pruned driving screen. Thursday, Nov 5, Hudson Yards → The Met, both displays: cited Muse brief parked, timed entry confirmed parked; drive → driver screen simplifies, passenger plays the artwork film, Muse's audio guide for all; parked at The Met the film moves to the driver's screen. Then a low battery (vehicle HAL) → three chargers; parked and charging, the passenger keeps Big Buck Bunny, Muse's café pick (15 cafés compared) backs the usual coffee, and the driver opens Big Buck Bunny full-bleed, parked. Closing card: the journey with real thumbnails, the architecture | Muse does the research; the car decides per display |

Context is a dated snapshot (`demo/context/nyc-2026-09-07.json`, refresh due 2026-09-10 and not
enforced); venues, merchant and route are fictional and badged `DEMO`; agent lanes are in
`demo/agent-profiles-v1.json`. `nyc_met` instead uses real, attributed data: Thursday's NWS forecast
captured Nov 4 (`demo/context/nyc-met-2026-11-05.json`), an OSRM route and OSM chargers, The Met's
CC0 images, and a real OSM café near the first charger that Muse picked from cited sources; its
ticket and coffee order are demos and nothing is booked, sent or paid. Its narration never says
"demo" or claims a transaction (`check-nyc-demo.py` enforces both); one low-contrast line in the
canvas margin says so for the whole video: "Simulated drive on the Android Automotive emulator ·
demo data · no affiliation with places shown".

## Corpus (`UseCaseCorpus`: 10 cases, all 18 catalog types)

| Case | Types | Recorded at | Shows |
|---|---|---|---|
| `now_playing` | Text Image Icon Divider Row Column Button | 0x1ff | Survives full restriction as glanceable rows |
| `browse_library` | Tabs List Card | 0x20 | `LIMIT_CONTENT` removes tabs and deep lists |
| `video_trailer` | Video AudioPlayer Text | 0x10 | `NO_VIDEO` removes video; audio stays |
| `search_and_filter` | TextField ChoicePicker Text | 0xa | `NO_KEYBOARD` and `NO_FILTERING` prune independently |
| `playback_settings` | ChoicePicker Slider DateTimeInput CheckBox | 0x40 | `NO_SETUP` removes configuration |
| `modal_confirmation` | Modal Button Text | 0x20 | BASELINE confirms; restricted refuses; confirm re-reads UXR |
| `passenger_video` | Video List TextField Slider | 0x1ff | Full catalog for a passenger display, shown at driver restriction |
| `handoff_to_driver` | Video List TextField Button | 0x1ff | Re-pruned and re-decided at the destination |
| `poi_discovery_order` | TextField List Card Button Text | 0x1ff | Parked search/order becomes navigation-only |
| `big_buck_bunny` | TextField List Card Video AudioPlayer Button Text | 0x10 | Video removed; audio and safe play remain |

`UseCaseCorpusGatewayParityTest` replays each case for all four provider selectors (parser parity,
not deployed adapters); `check-demo-workflows.py` keeps `record-demo.sh` in step with the corpus.

## Run

Needs the AVD prepared by `scripts/ensure-test-env.sh --fix`, `ffmpeg`/`ffprobe`, and the audio cache.

```bash
(cd aaos-apps-gradle-project && ./gradlew :a2ui-host-carui:assembleDebug \
  && adb install -r -t build/a2ui-host-carui/outputs/apk/debug/a2ui-host-carui-debug.apk)
DISPLAY_ID=$(adb shell dumpsys SurfaceFlinger --display-id | awk '/^Display /{print $2; exit}')
scripts/run-nyc-demo.py nyc_concierge /tmp/nyc-concierge.mp4 --display-id "$DISPLAY_ID"
scripts/run-nyc-demo.py nyc_met /tmp/nyc-met.mp4 --display-id 4619827259835644672 \
  --passenger-display-id 4619827551948147201
```

`nyc_met`'s `vehicleSignal` sets 18% three ways: the fake VHAL's stored `EV_BATTERY_LEVEL`
(`dumpsys …IVehicle/default --set 0x11600309 -f <Wh>`, which the 1 Hz continuous refresh keeps),
one `cmd car_service inject-vhal-event 0x11600309 <Wh>`, and Host B's `VEHICLE` broadcast
(`BATTERY_PCT`, reset to -1 after). Wh is read from `INFO_EV_BATTERY_CAPACITY`
(`cmd car_service get-property-value 0x11600106 0`); the stored level is restored on exit and Host B
gets `android.car.permission.CAR_ENERGY`. Any one route succeeding is enough; the sidecar logs each.

`run-nyc-demo.py` refuses another AVD, a second device, an inferred display, a non-debuggable Host B,
a stale STT fixture or a failed car-service transition (never substitutes `DEMO`), restores park on
exit, checks the MP4 (exactly the manifest's 60–180 s, ≥5 distinct frames, H.264/AAC, audible) and
writes a sidecar binding APK, manifest, fixtures and source state. MP4s stay outside git.

A scenario with `"recording": {"layout": "dual"}` also needs `--passenger-display-id` (reference AVD:
`4619827551948147201`). It records both physical displays with two `screenrecord` streams and
composes them at 1920×1080, driver left (1344×756), passenger right (504×756), labelled "Driver"
and "Passenger". The passenger stream starts at its first `passenger` action; until then the panel
shows a frame captured from that display 3 s earlier. Optional `titleCard` (any 1920×1080 PNG, 2–3 s
over the opening of the timeline) and `endCardHoldFromMs` (freeze from there to the end). The canvas,
panel boxes, label and disclaimer positions are module constants at the top of `run-nyc-demo.py`.

Post-production, after capture and from the same raw recording, so it always matches the UI:

- `preamble`: cards prepended before the timeline. Each names a `style` (`unsafe`: the frame tinted
  red under a red "Driving · 40 mph" chip; `useless`: greyed under a lock and `lockText`; `answer`:
  untouched), the `frameAtMs` of the raw driver frame it wraps, `seconds`, a `caption` and its
  narration `clipId`. `nyc_met`: unsafe and useless wrap the parked brief at 12 s, answer the pruned
  driving screen at 26 s; 4.8 + 3.3 + 5.4 s, each dipping to the background.
- `outro`: a closing card after the timeline, fading in over its held last frame. The journey as
  vehicle-state segments from the `carState` actions (green parked, blue driving, labelled with
  `places`), a real thumbnail at each `beats` time (driver above, passenger below the driving
  segments; a placeholder tile if a frame cannot be extracted), the `architecture` boxes (Muse's with
  a "Muse · Meta" text badge and the Muse icon in white), and the `footer`. `nyc_met`: 8.5 s, one
  narration line then 2 s of silence.
- `disclaimer`: a PNG line in the canvas margin, outside both panels, for the whole video.
- Clips take an optional `section`: `preamble` clips count from the start of the video, `outro`
  clips from the closing card, the rest from the device timeline. The video is preamble + timeline +
  outro and must stay within 60–180 s; `durationSeconds` is the device timeline.

`--render-only` composes an earlier capture without a device (`--from-raw <prefix>` names
`<prefix>.raw.mp4`, `.passenger.raw.mp4`, `.passenger-idle.png`; `--passenger-start` its stream
start). On the 4 Nov capture: 143.0 s, the ffmpeg pass 10 s, about 30 s with cards and validation.
`python3 scripts/test_run_nyc_demo.py` checks the timeline, the composition, the preamble, the
closing card (thumbnails and the placeholder fallback) and the disclaimer without a device
(synthetic `lavfi` recordings, pixel read-back).

```bash
scripts/record-demo.sh --list            # 10 corpus cases + restrictions, voice-a, voice-b, handoff
scripts/record-demo.sh <workflow> [out.mp4]   # or --all [dir]; default demo-recordings/ (gitignored)
scripts/capture-screenshot.sh <out.png> <display-id> <host> <package> <live|demo> <command>
```

Recorders drive debug-only broadcasts and fail on a screen that never changed. The Host B console
(debug, non-`user` builds) offers presets, all 10 cases, `buy album`/`play`, and a handoff picker.

## Assets

| Asset | Where | In git | Made by | Checked by |
|---|---|---|---|---|
| Route preview, poster, album art, 8 s MP4, 12 s M4A | `a2ui-host-carui/src/main/res/{drawable-nodpi,raw}/demo_*` | yes | `scripts/generate-a2ui-demo-assets.sh` (ffmpeg, libx264, AAC) | `check-demo-assets.py` (SHA-256) |
| 43 TTS clips, 11 STT fixtures, 4 SFX | `a2ui-automotive/demo/audio/elevenlabs/` | **no** | `scripts/prepare-elevenlabs-demo-audio.py` (`ELEVENLABS_API_KEY`, ≤10,000 credits); `--stt muse` transcribes with Muse Voice Transcribe (`META_API_KEY`; `nyc_met`'s five guest lines) | `check-nyc-demo.py --require-audio` |
| Met audio guide: one line per film work, at most 11 words, with its cited sources | `a2ui-automotive/demo/fixtures/met/AUDIO_GUIDE.json` | yes | `scripts/muse-audio-guide.py` (Muse Spark, `web_search`, `META_API_KEY`, 7 calls; cites only pages about each Met object) | `check-nyc-demo.py` (one clip per work, timed to the film) |
| Muse's Thursday brief for The Met, with its citations | `a2ui-automotive/demo/fixtures/met/BRIEF.json` | yes | the dev gateway's `quality_cached` entry (Muse Spark, `web_search`); copy to `build/a2ui-gateway-cache/muse/quality_cached/<cacheKey>.json` and run `serve.py --offline` to replay it keyless | `test_gateway.py` (replays offline) |
| `nyc_met` café: OSM cafés within 400 m of the first charger, Muse's cited findings, scores, gate and pick | `a2ui-automotive/demo/fixtures/coffee/{COFFEE,SOURCE}.json` | yes | `scripts/muse-curate-coffee.py` (Overpass; Muse Spark `web_search`, one call per café within 6 min plus one scoring call; walk and ranking computed by the script) | `test_muse_curate_coffee.py`, `check-nyc-demo.py` (context) |
| Claude corpus cache | `build/claude-corpus-cache/` | no | `scripts/claude-corpus-tester.py` | gateway schema on replay |
| Passenger clip, 19.3 s 720p | `a2ui-host-carui/src/main/res/raw/bbb_passenger.mp4` | yes | ffmpeg trim of Blender's MP4 (`demo/fixtures/bbb/SOURCE.json`) | `check-demo-assets.py` (SHA-256, source record) |
| Passenger clip, portrait 480×720 (2:3, audio copied) | `res/raw/bbb_passenger_portrait.mp4` | yes | ffmpeg crop of `bbb_passenger.mp4`, window following the subject (`demo/fixtures/bbb/SOURCE.json` `portrait`, `SOURCE_PORTRAIT.json`) | `check-demo-assets.py` (SHA-256, record) |
| Panel labels, margin disclaimer | `a2ui-automotive/demo/cards/label-*.png`, `nyc_met-disclaimer.png` | yes | `scripts/render-demo-cards.py labels`, `disclaimer` (Pillow; this ffmpeg has no drawtext) | `test_run_nyc_demo.py`, `check-nyc-demo.py` |
| Preamble and closing cards | next to the output (`<out>.preamble-N.png`, `<out>.outro.png`) | no | `run-nyc-demo.py` → `render-demo-cards.py preamble`, `outro`, from the raw recording's frames | `test_run_nyc_demo.py` |
| Muse icon, white, 64 px (badge on the closing card) | `demo/cards/brand/muse-icon-white.png` | yes | `magick` from the public Muse icon SVG with its fill set to white | — |
| `nyc_met` route image, 1024×576 | `res/drawable-nodpi/route_met.png` | yes | `scripts/render-met-route.py` from `demo/fixtures/route/` (no map tiles) | `check-demo-assets.py` |
| Artwork film, 36 s 720p, silent | `res/raw/met_artwork_film.mp4` | yes | `scripts/render-met-artwork-film.py` (six CC0 works; `demo/fixtures/met/FILM.json` has per-work `startMs`) | `check-demo-assets.py` (SHA-256, record) |
| Artwork film, portrait 720×1080 for the passenger display | `res/raw/met_artwork_film_portrait.mp4` | yes | `render-met-artwork-film.py --orientation portrait` (full-bleed Ken Burns pans, bottom captions; `FILM_PORTRAIT.json`, same `startMs`) | `check-demo-assets.py`, `check-nyc-demo.py` (timing equals `FILM.json`) |
| Route, Met, NWS, chargers | `demo/fixtures/{route,nws,chargers}/` | yes | `scripts/fetch-nyc-met-fixtures.py`, `scripts/fetch-ev-chargers.py` (`python3 -I`) | `check-nyc-demo.py` (context) |
| `nyc_met` chapter title ("Thursday at The Met") | `demo/cards/nyc_met-title.png` | yes | `render-demo-cards.py title` | `check-nyc-demo.py` |

Generated `demo_*` media hold no third-party content (the placeholder video is not Big Buck Bunny).
`bbb_passenger.mp4` is: Big Buck Bunny (c) copyright 2008, Blender Foundation / www.bigbuckbunny.org,
CC BY 3.0, trimmed and re-encoded. `nyc_met` credits: route, chargers and café © OpenStreetMap
contributors (ODbL), routed by OSRM; café findings link their sources (`COFFEE.json`); artworks The Metropolitan Museum of Art Open Access (CC0); weather U.S.
National Weather Service (public domain). The only mark is the Muse icon on the closing card's Muse
badge, used to identify Meta's Muse (a Meta trademark); no endorsement implied. The six `met_guide_*`
lines are Muse's, copied from `AUDIO_GUIDE.json`, each citing only The Met's page for that work
(and Smarthistory's for Aristotle). ElevenLabs licensing is unverified for outward use.
Regenerating spends credits: `--dry-run`, then `--verify-personas`, then bare.

**Claims.** Proves Host B rendering, policy on injected phrases, parked confirmation, live
re-projection, audio continuity. Not a live agent, live location/inventory/payment, production
speech, passenger-display pixels or media rights. Never narrate a `DEMO` mask as motion.

## Fresh AAOS host

One-time setup for a Mac that runs the emulator and the gate.

```bash
# From the repo root. 1. JDK 21 (CI: Temurin 21) and SDK
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk
[ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH
sdkmanager --licenses   # review and accept once
sdkmanager "platform-tools" "emulator" "platforms;android-37.0" \
  "system-images;android-35-ext15;android-automotive;arm64-v8a"
# 2. Canonical AVD, hw.* as on the reference Mac
echo no | avdmanager create avd -n AAOS_35_1408p_GoogleAPIs -c 512M \
  -d automotive_1408p_landscape_with_google_apis -k "system-images;android-35-ext15;android-automotive;arm64-v8a"
CFG=~/.android/avd/AAOS_35_1408p_GoogleAPIs.avd/config.ini
for kv in PlayStore.enabled=no hw.cpu.ncore=4 hw.ramSize=3072 vm.heapSize=256 disk.dataPartition.size=6G \
  hw.lcd.width=1408 hw.lcd.height=792 hw.lcd.density=160 hw.display1.width=1408 hw.display1.height=792 \
  hw.display1.density=160 hw.display1.flag=1 hw.display6.width=400 hw.display6.height=600 \
  hw.display6.density=120 hw.gpu.enabled=yes hw.gpu.mode=host hw.keyboard=yes hw.dPad=yes \
  hw.rotaryInput=no hw.audioInput=yes hw.initialOrientation=portrait fastboot.forceColdBoot=yes; do
  sed -i '' "/^${kv%%=*} *=/d" "$CFG"; echo "$kv" >> "$CFG"; done
# 3. Local config: keys only for providers in use; never commit .env
cp .env.example .env && chmod 600 .env
# 4. Gitignored audio cache, verified
mkdir -p a2ui-automotive/demo/audio/elevenlabs
rsync -a <machine-with-cache>:<repo>/a2ui-automotive/demo/audio/elevenlabs/ a2ui-automotive/demo/audio/elevenlabs/
scripts/check-nyc-demo.py --require-audio
# 5. Boot and prepare the emulator: builds :plugin, -writable-system, plugin swap, en-US, no animations
scripts/ensure-test-env.sh --fix
# 6. Start the dev gateway (tools/a2ui-gateway/, see PROVIDERS.md), then expose it to the emulator
adb reverse tcp:8787 tcp:8787
# 7. Install Host B and run a story (commands under Run)
```

`ensure-test-env.sh --fix` reboots the emulator; re-run `adb reverse` after every boot. The reference
AVD's display id is `4619827259835644672`; record it in `.env` as `DISPLAY_ID`.
