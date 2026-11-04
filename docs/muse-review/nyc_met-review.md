# Muse review of the nyc_met demo video (2026-11-04)

muse-spark-1.3, Responses API with image input, reasoning effort high, 84.5 s. Input: 22 frames
(one every 6 s of the 132 s recording, 960 px), the clip and action timeline, and the doNotClaim list.

## Prompt
You are a demanding judge for the Meta AI Developer Hackathon. Rubric: Impact 30%, Use of Meta AI tooling 25% (creative use beyond a basic integration, unexpected capabilities), Technical depth 20%, Demo 25%. Below is a 132-second demo video of 'Muse2CarUI' (Meta's Muse designing in-car screens on Android Automotive; the car enforces driver-distraction rules), given as one frame every 6 seconds (labelled with its time) plus the exact narration/action timeline and the list of things the video must not claim. Review it frame by frame. Report, concisely: 1) a grade per criterion with one-line reasons; 2) every place the narration contradicts what is on screen at that time, or claims something on the do-not-claim list; 3) legibility or polish problems visible in specific frames; 4) the three changes that would most raise the score. Cite frame times.

## What we changed in response
- Narration that ran ahead of or beyond the screen: `met_narration_02`, `met_narration_05`, `ev_narration_02`,
  `ev_narration_03` (commit 5a4834a18).
- Title card footer: demo data, no endorsement by the places shown.

## What we kept, and why
- "Now we're moving": driving comes from car_service's driving-state emulation, not a DEMO preset.
- The parked film at 83 s is the Ken Burns film; a single sampled frame shows it as a still.
- The plan visible before the guest's request is the story's premise (the concierge prepared it).

## Muse's review (verbatim)
**Muse2CarUI - 132s - Demanding Judge Review**

### 1) Grades

* **Impact 18/30:** Real safety problem + parked vs driving enforcement is strong, but all signals/bookings/charging are simulated with no driver-outcome evidence.
* **Use of Muse 14/25:** Cited brief + 6-part audio guide + 15-cafe comparison shows planning, but admitted pre-made `before we left`, no live tool-call / unexpected capability shown.
* **Technical depth 11/20:** Stock Android Automotive + car-side prune + VHAL inject is right architecture, but no code/log/fail-closed proof; DRIVING still shows parked detail.
* **Demo 13/25:** Clear dual-screen story with good ticket/coffee disclaimers, but mistimed VO, illegible tiny text, wrong counts, filler bunny video.

### 2) Contradictions vs screen + DO-NOT-CLAIM

* **7s,13s vs 11.0s VO:** Driver already shows `Arrive 2:41 PM Hudson Yards -> The Met` + `Today at The Met` + route preview *before* user says `Take us to The Met`.
* **16.5s VO `Detail like this only appears while parked` vs 37s,43s:** DRIVING still shows `Chance of showers · high 74F National Weather Service` + `Timed entry Thu Nov 5 3:00 PM 2 guests`. Same detail as parked 19s,25s.
* **38.0s VO `Now we're moving. Driver simplifies, passenger doesn't have to` vs 37s,43s:** Driver keeps 3 detailed cards + pill `Put away for the drive: map, artwork, brief`; Passenger is `Ready when you are` blank. Opposite of claim. Artwork only starts at 45.9s action, seen 49s.
* **DO-NOT-CLAIM motion:** 38.0s `Now we're moving`, 94.5s `Back on the road` claim real motion while UI only shows `Demo data` + `DRIVING` preset. Actions 37.0s,93.5s `carState drive`. Prohibited: DEMO preset as motion.
* **83.4s VO `Parked at The Met, the film moves to driver's screen` vs 85s:** Driver shows still `Wheat Field with Cypresses` + `Close`, not film/video. Passenger blank. Also `Parked at The Met` claims live location at museum - prohibited live-location claim. Emulator only.
* **99.1s VO `found three chargers` vs 91s,97s,103s:** Header `Battery 18% · 3 chargers` but list shows only 2: `ChargePoint 0.3 mi`, `FLO 2 stalls`. Third missing.
* **109.2s VO `Parked and charging` vs 109s:** `Charging at ChargePoint · 18%` with battery still 18% as in 98.1s low-battery inject. Claims real charging session - prohibited. `Demo data` pill does not cure VO claim.
* **109.2s VO `checked 15 cafes, each claim backed by a source` vs 109s,115s:** Shows `Why Starbucks, 1488 3rd Ave Muse compared 15 cafes · open till 7 · oat milk · 3 min walk` with zero citations visible, unlike Met cards 19s,25s. Contradiction + risks prohibited claim that `open till 7` / `oat milk` menu/hours are live. No source shown.
* **Brand endorsement risk:** Names `ChargePoint, FLO, Starbucks 1488 3rd Ave, The Met` with no partnership disclaimer. Art has 49s-79s footer `Six works from The Met, Open Access CC0`, chargers/cafe have only tiny `© OpenStreetMap` + `Demo data`.
* **Compliant - do not break:** 29.0s `nothing is booked or paid`, 121.8s `order is a demo, so nothing goes to cafe`, 83.4s `Muse wrote that guide ... before we left` correctly avoids claiming real booking/order/live generation.

### 3) Legibility / polish - frame cites

* **7s,13s,19s,25s:** Source lines `Source metmuseum.org Hours and admission` ~6pt gray-on-black unreadable; `4.3 mi` map + `© OpenStreetMap contributors` illegible.
* **31s,121s:** Modal `Reserve 2 / Order your usual` covers background, truncates `THE MET · THURSDAY` to `SDAY`, `COFFEE` to `OFFEE`.
* **37s,43s,97s,103s:** `Demo data`, `Ask by voice`, `Put away for the drive` pills tiny low-contrast; `1/6-6/6` counts faint.
* **49s-79s:** Passenger captions `Vincent van Gogh 1889` etc tiny; 61s Aristotle, 73s Death of Socrates images dark/under-exposed.
* **85s:** Driver art with thick black border on gray matte + floating `Close`; passenger wasted black `Ready when you are`.
* **97s-127s:** Passenger loops irrelevant `Big Buck Bunny` cartoon during charge/coffee story; tiny footer `CC BY 3.0 Passenger zone demo setting`. Still playing behind 127s end-card.
* **Continuity:** Status clock 2:20 in 7s-55s, 2:21 in 61s-91s, jumps 5:15 in 97s-127s despite `wallClock 2026-11-05T17:15` at 91.5s; 109s pill `Parked: full detail is back` becomes `CHARGE-COFFEE` in 115s; bottom `63 63` + cryptic icons dominate.

### 4) Three highest-leverage fixes

1. **Prove Muse + fail-closed filter live:** Show Muse JSON with citations -> car policy log pruning map/brief/art on `drive`; make DRIVING truly minimal - remove weather/ticket detail that violates 16.5s rule in 37s,43s.
2. **Lock VO to state + honest overlay:** Don't pre-show answer 7s before 11s ask; show passenger art when you say it; list 3 chargers; show cafe sources; persistent footer `SIMULATED park/drive - DEMO data - no live weather/location/availability/booking/charging`.
3. **Make it readable on video:** Crop to one readable card at a time, kill bunny during end-card, fix clock to single afternoon, enlarge modals/captions, resolve brand use with `Demo - not endorsed by Met/OpenStreetMap/ChargePoint/Starbucks`.
