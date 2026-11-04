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
exit, checks the MP4 (60–90 s, near target, ≥5 distinct frames, H.264/AAC, audible) and writes a
sidecar binding APK, manifest, fixtures and source state. MP4s stay outside git.

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

Generated media hold no third-party content (the video is not Big Buck Bunny footage). ElevenLabs licensing
is unverified for outward use. Regenerating spends credits: `--dry-run`, then `--verify-personas`, then bare.

**Claims.** Proves Host B rendering, policy on injected phrases, parked confirmation, live
re-projection, audio continuity. Not a live agent, live location/inventory/payment, production
speech, passenger-display pixels or media rights. Never narrate a `DEMO` mask as motion.

## Worker handoff (dartbot-worker.local)

Steps 1–2 and the audio copy are already done for user `dartbot` (2026-11-04); run them on a fresh machine.

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
rsync -a ~/handoff/car-libs-assets/elevenlabs/ a2ui-automotive/demo/audio/elevenlabs/
scripts/check-nyc-demo.py --require-audio
# 5. Boot and prepare the emulator: builds :plugin, -writable-system, plugin swap, en-US, no animations
scripts/ensure-test-env.sh --fix
# 6. Start the dev gateway (tools/a2ui-gateway/, see PROVIDERS.md), then expose it to the emulator
adb reverse tcp:8787 tcp:8787
# 7. Install Host B and run a story (commands under Run)
```

`ensure-test-env.sh --fix` reboots the emulator; re-run `adb reverse` after every boot. The reference
AVD's display id is `4619827259835644672`; record it in `.env` as `DISPLAY_ID`.
