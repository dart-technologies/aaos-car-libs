# PRD: a2ui-qnx for the QNX Global Hackathon

- Status: Draft for review
- Date: 2026-11-24

## The opportunity

The [QNX Global Hackathon](https://qnx.devpost.com) (Nov 2 – Dec 15 2026, 2pm
EST; ~3 weeks left) offers $50,000 ($10,000 grand prize, $4,000 per category).
QNX 8.0 is free for non-commercial use, and Dart already holds an SDP 8.0
non-commercial license. QNX needs developers who make its platform easier to
build on; Dart needs automotive platform access beyond Android. A public,
QNX-native A2UI compatibility layer serves both: it ports working AAOS
interface tooling and reusable policy tests into the QNX developer community.

**Recommended category: 5, Open-Source Contribution & Ports** ($4,000 + grand
eligibility). The artifact is exactly what the category asks for: a port of an
existing open-source project to QNX, in a new repository created during the
submission period (no forks), with QNX build files and documentation. The Best
Codelab / Tutorial bonus is a natural second shot: the port write-up doubles as
the entry. Category 2 (Automotive) rewards end-user vehicle apps; our asset is
platform and tests, so 5 > 2 > 4.

Submission requires: public repo with an detectable open-source license, text
description, a **<3 minute public YouTube demo**, build/run instructions (QNX
version, target, toolchain), and an explicit map of the QNX evidence (files,
functions, demo moments). Judging (Jan 15–29): Technological Implementation,
Design, Potential Impact, Innovation & AI Creativity.

## Verified constraints (shape the plan)

- **No macOS host.** QNX SDP 8.0 supports x86_64 Windows/Linux hosts only
  (Ubuntu 22.04/24.04, RHEL 9); the quickstart states macOS is unsupported. The
  M2 worker cannot run the toolchain, and SDP alone wants 8 GB RAM + ~10 GB
  disk. Day-1 action: provision a cloud x86_64 Ubuntu 24.04 build VM.
- **No hardware needed.** QEMU is a first-class target (x86_64 and AArch64);
  RPi5 is optional, not required.
- **C++, not Rust.** Community rustc is tested for QNX 7.x with 8.0 still in
  progress; the qualified Ferrocene toolchain for SDP 8.0 was announced Nov 17
  2026 but is commercial. `qcc` C++17 is first-class and every sample uses it.
  Keep a Rust port as a follow-up, not a sprint risk.
- **No graphics in scope.** The demo is headless: IPC round-trips and policy
  verdicts over the QEMU serial console. QNX Screen is explicitly out; it would
  double the schedule for no judging gain under category 5.

## Architecture: a2ui-qnx

A QNX process exposes a bounded A2UI surface endpoint over native synchronous
IPC. A client sends a surface plus a trusted, display-specific vehicle-context
snapshot; the server returns the allowed projection and action decisions. Two
thin QNX seams, both standard: a channel server (`ChannelCreate` /
`MsgReceive` / `MsgReply`) and optionally a resource-manager frontend
(`resmgr_attach`) presenting the same contract as `/dev/a2ui`. All policy logic
sits behind those seams in portable C++ with zero QNX dependencies, so the same
tests run on the Linux host and on QEMU.

```
a2ui-qnx/                 (new public repo, Apache-2.0, created in-period)
  core/                   portable C++17: surface validation, pruner, policy,
                          protocol encode — no QNX headers
  qnx/                    thin adapter: channel server + resmgr frontend
  vectors/                vendored JSON/YAML: schemas, conformance suite,
                          gateway schema, exported corpus
  tests/                  C++ harness replaying the same vectors (host + QEMU)
  images/                 mkifs buildfile + QEMU launch for the demo
  codelab/                bonus-entry tutorial: the port, step by step
```

## What ports directly (verified in this tree)

| aaos-car-libs artifact | Size | a2ui-qnx use |
|---|---|---|
| `a2ui-automotive/upstream-schemas/*.json` (A2UI v1 @294dacce, legacy @44a420b) | 4 files | vendored as-is; validation targets |
| `a2ui-automotive/conformance/automotive.yaml` (per-level prune cases × 18-component catalog) | 1 file | vendored as-is; conformance vectors |
| `a2ui-automotive/agent-gateway-v1.schema.json` | 7 KB | vendored as-is; wire contract |
| `a2ui-automotive/demo/nyc-scenarios-v1.json` | 29 KB | scenario vectors for the demo drive |
| `a2ui-model` policy + protocol + model packages | 16 files | mechanical C++ port; only Android imports are `@NonNull/@Nullable/@VisibleForTesting` |
| `a2ui-model` policy tests (8 files) + `UseCaseCorpus` | — | assertions ported 1:1; corpus needs a JSON export task (new, small) |

The Java corpus is code-embedded, so week 1 includes a JSON export (schema,
corpus, vectors) that also serves future non-Android consumers — that export is
part of the ecosystem-multiplier story.

## Sprint (3 weeks)

- **Week 0 (days 1–2): host.** Cloud Ubuntu VM, SDP 8.0 install with the held
  license, QEMU x86_64 target boots, sample IPC program runs. Exit: screenshot
  of a QNX shell in QEMU. Create the empty `a2ui-qnx` repo (in-period).
- **Week 1: core.** Corpus JSON export in aaos-car-libs (small PR); C++ port of
  policy/protocol/model; host-side test harness green against the vendored
  vectors. Exit: `ctest` green on Linux, zero QNX headers in `core/`.
- **Week 2: target.** QNX IPC adapter + resmgr frontend; mkifs image; end-to-end
  drive on QEMU (surface in, projection + decisions out); record the <3 min
  video (console + narration + architecture card). Exit: video draft + evidence
  map.
- **Week 3: land.** Buffer for toolchain surprises; codelab write-up; submit
  (repo URL, video, instructions, category 5, upstream URL
  `github.com/dart-technologies/aaos-car-libs`).

Effort: one engineer full-time, or two (core + QNX seams) to de-risk week 2.

## Risks

- Linux host access is the critical path — nothing QNX-specific can start on
  the Mac. Mitigation: VM on day 1; `core/` develops anywhere.
- Scope creep into graphics or Rust. Mitigation: both explicitly out (above).
- Ferrocene/Rust temptation from the Nov 17 announcement. Mitigation: cite as
  future work in the submission; do not depend on it.
- Video rules: no third-party trademarks or copyrighted music without
  permission. The demo uses repo-generated narration only.

## Positioning (for the submission text)

Dart as an interoperability contributor, not just an entrant: existing AAOS
expertise, working AI-interface tooling, and reusable, versioned policy tests,
ported to QNX with the seams (schemas, vectors, corpus export) that let the
next QNX developer reuse them. The codelab entry makes the recipe explicit.
