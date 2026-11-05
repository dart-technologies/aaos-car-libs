# Demo

Host B (`art.dart.a2ui.carui`) on `AAOS_35_1408p_GoogleAPIs`. Stories are data
(`a2ui-automotive/demo/nyc-scenarios-v1.json`), speech is prerecorded, and motion is a live car-service
transition (`cmd car_service emulate-driving-state park|drive`), never a synthetic mask.

## NYC stories

| Scenario | s | Corpus case | Story | Proves |
|---|---:|---|---|---|
| `nyc_poi_order` | 78 | `poi_discovery_order` | Parked at Hudson Yards, bound for Brooklyn Museum: find coffee on the route, order ahead; drive → search and order go, route stays | Voice and touch share one policy; live re-projection |
| `nyc_curated_video` | 78 | `big_buck_bunny` | Waiting in Long Island City: open a curated short; drive → video view and decoder close, the audio-only control keeps playing | `NO_VIDEO` drops video, not audio |
| `nyc_concierge` | 90 | `poi_discovery_order`, then `big_buck_bunny` | Fifth House: parked order, brand audio kept in motion, visual chapter on arrival; closes on the fast foreground and higher-effort background lanes | The journey gets quieter, not unsafe; agents never control the car |

Context is a dated snapshot (`demo/context/nyc-2026-09-07.json`, refresh due 2026-09-10 and not
enforced); venues, merchant and route are fictional and badged `DEMO`; agent lanes are in
`demo/agent-profiles-v1.json`.

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
```

`run-nyc-demo.py` refuses another AVD, a second device, an inferred display, a non-debuggable Host B,
a stale STT fixture or a failed car-service transition (never substitutes `DEMO`), restores park on
exit, checks the MP4 (exactly the manifest's 60–180 s, ≥5 distinct frames, H.264/AAC, audible) and
writes a sidecar binding APK, manifest, fixtures and source state. MP4s stay outside git.

A scenario with `"recording": {"layout": "dual"}` also needs `--passenger-display-id` (reference AVD:
`4619827551948147201`). It records both physical displays with two `screenrecord` streams and
composes them at 1920×1080, driver left (1344×756), passenger right (504×756), labelled "Driver"
and "Passenger". The passenger stream starts at its first `passenger` action; until then the panel
shows a frame captured from that display 3 s earlier. Optional `chapterCards`: 1920×1080 PNGs over
the driver's picture at their `atMs` on the timeline, each held 2–3.5 s; the first opens the
timeline, and a later one with `fadeIn`/`fadeOut` eases in and out (an intermission that hides a case
switch and a clock jump). Every card's fade-out dips through dark: for its first half the card's
content eases to the background while the card is still opaque, then the dark lifts off the live
screen, so its text never ghosts over the next chapter's. Optional `endCardHoldFromMs` freezes
from there to the end. The canvas,
panel boxes, label and disclaimer positions are module constants at the top of `run-nyc-demo.py`.

Post-production, after capture and from the same raw recording, so it always matches the UI:

- `preamble`: cards prepended before the timeline. Each names a `style` (`unsafe`: the frame tinted
  red under a red "Driving · 40 mph" chip; `useless`: greyed under a lock and `lockText`; `answer`:
  untouched), the `frameAtMs` of the raw driver frame it wraps, `seconds` and its narration
  `clipId`. No caption is baked in: the burned-in captions carry the narration.
- `outro`: a closing card after the timeline, fading in over its held last frame. A calm `note`
  on top, the journey as vehicle-state segments from the `carState` actions (green parked, blue
  driving, labelled with `places`), a real thumbnail at each `beats` time (driver above, passenger
  below the driving segments; a placeholder tile if a frame cannot be extracted), and the
  `architecture` as title-only boxes (one may be a `badge` pill, with the outro's optional
  `badgeIcon`). It ends above the caption band. An optional `closingClipId` speaks after its `clipId`.
- `disclaimer`: a PNG line in the canvas margin, outside both panels, for the whole video. In the
  cockpit it sits in the rail band and is gone before the closing card fades in.
- A narration clip may carry `spoken`, the words the voice says when the caption's would be
  mangled; captions and the SRT keep `text`.
- Clips take an optional `section`: `preamble` clips count from the start of the video, `outro`
  clips from the closing card, the rest from the device timeline. The video is preamble + timeline +
  outro and must stay within 60–180 s; `durationSeconds` is the device timeline.

A dual recording is composed in a cockpit by default (`--no-cockpit` restores the side-by-side
canvas): `cards/cockpit-frame.png`, one still frame for the whole video, with the driver (1312×738)
and passenger (492×738) recordings in its windows at one scale (`cards/cockpit-layout.json`). When
the layout names intro screens (its optional `intro`) the video opens on them, pushing in 2.5% for
3.1 s with a screen glow; from 2.0 s an eased 1.1 s crossfade turns them into the first preamble
card. The preamble plays inside the driver window, as do the chapter cards. The closing card covers
the whole canvas. Captions (every section, from the real
speech lengths; a "Passenger" tag on guest lines) and the story rail (the scenario's `storyBeats`:
beat, progress, Parked or Driving) are PNGs from `scripts/demo_overlays.py` (Pillow, in a child process), and the SRT is
written next to the video. An intro adds 2.0 s.

`--render-only` composes an earlier capture without a device (`--from-raw <prefix>` names
`<prefix>.raw.mp4`, `.passenger.raw.mp4`, `.passenger-idle.png`; `--passenger-start` its stream
start). `python3 scripts/test_run_nyc_demo.py` checks the timeline, the composition, the preamble, the
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
| 19 TTS clips, 6 STT fixtures, 4 SFX | `a2ui-automotive/demo/audio/elevenlabs/` | **no** | `scripts/prepare-elevenlabs-demo-audio.py` (`ELEVENLABS_API_KEY`, ≤10,000 credits) | `check-nyc-demo.py --require-audio` |
| Claude corpus cache | `build/claude-corpus-cache/` | no | `scripts/claude-corpus-tester.py` | gateway schema on replay |
| Passenger clip, 19.3 s 720p | `a2ui-host-carui/src/main/res/raw/bbb_passenger.mp4` | yes | ffmpeg trim of Blender's MP4 (`demo/fixtures/bbb/SOURCE.json`) | `check-demo-assets.py` (SHA-256, source record) |
| Passenger clip, portrait 480×720 (2:3, audio copied) | `res/raw/bbb_passenger_portrait.mp4` | yes | ffmpeg crop of `bbb_passenger.mp4`, window following the subject (`demo/fixtures/bbb/SOURCE.json` `portrait`, `SOURCE_PORTRAIT.json`) | `check-demo-assets.py` (SHA-256, record) |
| Panel labels, a scenario's margin disclaimer | `a2ui-automotive/demo/cards/label-*.png`, its `disclaimer.image` | yes | `scripts/render-demo-cards.py labels`, `disclaimer` (Pillow; this ffmpeg has no drawtext) | `test_run_nyc_demo.py`, `check-nyc-demo.py` |
| Preamble and closing cards | next to the output (`<out>.preamble-N.png`, `<out>.outro.png`) | no | `run-nyc-demo.py` → `render-demo-cards.py preamble`, `outro`, from the raw recording's frames | `test_run_nyc_demo.py` |
| Cockpit frame, intro glow, layout | `demo/cards/cockpit-{frame,glow}.png`, `cockpit-layout.json` | yes | `scripts/demo_overlays.py cockpit` (procedural) | `test_demo_overlays.py` (windows are exactly the boxes) |
| Captions, story rail, intro frames, SRT | next to the output (`<out>.overlays/`, `<out>.srt`) | no | `run-nyc-demo.py` → `demo_overlays.py overlays` | `test_demo_overlays.py`, `test_run_nyc_demo.py` |

Generated `demo_*` media hold no third-party content (the placeholder video is not Big Buck Bunny).
`bbb_passenger.mp4` is: Big Buck Bunny (c) copyright 2008, Blender Foundation / www.bigbuckbunny.org,
CC BY 3.0, trimmed and re-encoded. ElevenLabs licensing is unverified for outward use.
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
