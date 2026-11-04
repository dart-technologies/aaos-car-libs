You are reviewing the repository in your workspace, read-only. Do not edit, create, delete or commit any file, and do not run builds that write outside a temp dir. Reading files, grep, git log/diff/show, and running the Python unit tests are fine.

## Context
This is "Muse2CarUI", a Meta AI Developer Hackathon submission due tonight at 21:00 ET. It is a fork of AOSP car-libs. Branch `sprint/meta-met` adds:
- **A2UI agent-generated UI on stock Android Automotive:**
  - Model and policy in `a2ui-model/`.
  - Host A, androidx.car.app, in `a2ui-host-carapp/`.
  - Host B, car-ui-lib, in `a2ui-host-carui/`.
  - The vehicle prunes the UI with fail-closed driver-distraction (UXR) rules, per display. Consequential actions run only when parked and confirmed.
- **A model-agnostic dev gateway** (`tools/a2ui-gateway/`) with adapters for Claude, ChatGPT, Gemini, Grok and Meta's Muse (`muse-spark-1.3`).
  - The Muse "brief" mode uses Responses API `web_search` with `user_location`, then a second structured-output call, because citations are lost when web search and a JSON schema share one call.
- **Muse-produced demo content**, all in `a2ui-automotive/demo/`:
  - a cited Met brief (`fixtures/met/BRIEF.json`)
  - an audio guide (`scripts/muse-audio-guide.py`, `fixtures/met/AUDIO_GUIDE.json`)
  - a café curation from OpenStreetMap candidates with cited per-criterion evidence and deterministic scoring (`scripts/muse-curate-coffee.py`, `fixtures/coffee/COFFEE.json`)
  - Muse Voice Transcribe for passenger speech-to-text (`scripts/prepare-elevenlabs-demo-audio.py --stt muse`)
  - Muse Code prompts in `docs/muse-code/`
  - a Muse vision review of the demo video (`docs/muse-review/`)
- **The demo scenario** `nyc_met` in `a2ui-automotive/demo/nyc-scenarios-v1.json`, recorded by `scripts/run-nyc-demo.py`, with gate checkers `scripts/check-*.py`.
- **Docs:** README.md, docs/DEMO.md, docs/PROVIDERS.md, docs/ARCHITECTURE.md, FORK.md.

Judging: Impact 30%; Use of Meta AI tooling 25% (creative use beyond a basic integration, unexpected capabilities); Technical depth 20%; Demo 25%.

Start with `git log --oneline origin/main..HEAD` and `git diff --stat origin/main..HEAD` to see what the sprint changed. Spend most of your effort there.

## Part 1: deep code review (correctness first)
Find real defects in the sprint's code:
- **Safety logic:** UXR pruning, parked-only gates, confirm flows, fail-closed paths.
- **Gateway:** adapter error handling, cache keys, timeouts, secrets handling (keys must never be logged or returned).
- **Demo-time behaviour:** parsing of the coffee, Met and weather fixtures; timing; races in Host B.
- **Scripts:** subprocess and ffmpeg handling, input validation.
- **Tests:** tests that cannot fail, or that test a copy of the logic instead of the code.
- **Honesty:** claims in docs or on-screen strings that the code or data don't back.

For each finding give:
- severity (critical, high, medium, low)
- file:line
- what is wrong, with the evidence quoted
- a concrete failure scenario
- the minimal fix

Verify each one by reading the code paths end to end, and drop anything you can't substantiate. Prefer few true findings to many guesses.

## Part 2: gaps to an A+ submission
From a judge's point of view, list what would still cost points in each criterion. Base it on the repo, README and docs a judge sees in 2 minutes. Give concrete fixes with exact wording or the file to change.

## Part 3: enhancement opportunities using the tool stack
Exhaustively examine how this project could use more of Meta's stack, and of the existing multi-model stack, in ways that are real, demonstrable and honest. Rank by judge impact per hour of work, and mark which are doable in under 2 hours tonight versus later. Consider at least:
- Muse Spark capabilities: vision on in-car camera or screen frames, structured output, web_search with location, reasoning-effort tiers, function or tool calling, parallel tool calls
- Muse Voice Transcribe (multilingual)
- Muse Code: headless review, agents, worktrees
- using Muse to verify its own outputs, e.g. citation checking or claim verification against `doNotClaim`
- comparing providers on the same case

For each, name the files to touch and how a judge would see it.

## Output
One markdown report with sections Part 1, Part 2 and Part 3, under 1,500 words, with no preamble.
