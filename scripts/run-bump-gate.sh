#!/bin/zsh
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
#
# The acceptance gate for a dependency bump. Passing tests are not the criterion, because the
# suite is blind in places; the criterion is that nothing observable changed:
#
#   0. the resolved version is the one asked for (a bump below the transitive floor changes nothing)
#   1. build coverage: every .java under every source root still compiles somewhere
#   1b. the published API surface (check-api-surface.py) and API lint (check-api-lint.py)
#   2. the JVM suites: modules from scripts/gate-modules.tsv, counts from docs/gate-counts.tsv
#   2b. coverage against docs/coverage-baseline.tsv
#   3. car-ui-lib instrumented: the failing set diffed against the recorded baseline
#   4. clean instrumented suites: car-telephony-common, car-rotary-lib, car-media-common
#
# Exit codes: 0 pass, 1 a check failed, 2 a check could not be run.
#
#   scripts/run-bump-gate.sh [--no-env] <label> [group:name=expectedResolvedVersion ...]
#
#   --no-env  do not establish the device environment (step 3-pre); accepted anywhere.

set -o pipefail
REPO=${0:a:h:h}
GP=$REPO/aaos-apps-gradle-project
# Scanned across all args: a flag naturally goes after the label.
NO_ENV=0
_argv=()
for _a in "$@"; do
  if [[ $_a == "--no-env" ]]; then NO_ENV=1; else _argv+=("$_a"); fi
done
set -- "${_argv[@]}"
LABEL=${1:-unlabelled}
shift 2>/dev/null
EXPECTED=("$@")
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BASELINE_FAILSET=$REPO/docs/baseline-failset.txt
INTERMITTENT=$REPO/docs/intermittent-tests.txt
rc=0

fail() { print -u2 "GATE FAIL: $*"; rc=1 }
undet() { print -u2 "GATE INDETERMINATE: $*"; [[ $rc -eq 0 ]] && rc=2 }

print "=== bump gate: $LABEL ==="

# ---------------------------------------------------------------- 0. resolved version
if (( ${#EXPECTED} )); then
  print "\n[0/4] resolved version (declared != resolved)"
  for spec in $EXPECTED; do
    coord=${spec%%=*}; want=${spec##*=}
    out=$( cd $GP && ./gradlew -I $REPO/scripts/dump-resolved-version.gradle \
             -PresolveCoord=$coord resolvedVersions -q 2>/dev/null | grep -E '^RESOLVED|^UNRESOLVED' )
    if [[ -z $out ]]; then
      undet "$coord: no module resolves it — cannot verify the bump took effect"
      continue
    fi
    if print -r -- $out | grep -q '^UNRESOLVED'; then
      undet "$coord: at least one configuration is unresolved; mixed resolved/unresolved output"
      continue
    fi
    versions=( ${(u)$(print -r -- $out | awk -F'\t' '$1=="RESOLVED"{print $5}')} )
    if (( ${#versions} == 0 )); then
      undet "$coord: present but unresolvable in every configuration"
      continue
    fi
    # Each row must be the expected version or a row in docs/resolution-exceptions.tsv.
    print -r -- $out | awk -F'\t' '$1=="RESOLVED"{print $2"\t"$3"\t"$5}' | sort -u > /tmp/gate-res.tsv
    RESOLUTION_EXCEPTIONS=$REPO/docs/resolution-exceptions.tsv \
    GATE_COORD=$coord GATE_WANT=$want python3 - <<'RESPY'
import os, sys
coord, want = os.environ['GATE_COORD'], os.environ['GATE_WANT']
exc_path = os.environ['RESOLUTION_EXCEPTIONS']
exceptions = {}
try:
    for line in open(exc_path):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.rstrip('\n').split('\t')
        if len(parts) < 5 or not parts[4].strip():
            print(f'  exception row lacks a reason: {line.strip()[:80]}', file=sys.stderr)
            sys.exit(2)
        if parts[0] == coord:
            exceptions[(parts[1], parts[2], parts[3])] = parts[4]
except FileNotFoundError:
    pass

rows = [tuple(l.rstrip('\n').split('\t')) for l in open('/tmp/gate-res.tsv') if l.strip()]
bad, used = [], set()
for module, config, version in rows:
    if version == want:
        continue
    key = (module, config, version)
    if key in exceptions:
        used.add(key)
        continue
    bad.append((module, config, version))

if bad:
    print(f'{coord} resolves to something other than {want}, with no recorded exception:',
          file=sys.stderr)
    for m, c, v in sorted(bad):
        print(f'    {m:<34}{c:<34}{v}', file=sys.stderr)
    print('    A declaration below the transitive floor is decorative; every other check',
          file=sys.stderr)
    print('    would then pass while measuring nothing. If this row is legitimate, record it',
          file=sys.stderr)
    print(f'    with a reason in {exc_path}.', file=sys.stderr)
    sys.exit(1)

# A recorded exception that matched nothing is a FAILURE, not a pass. An allowlist entry outliving
# the condition it waives is a silent hole — same lesson as the deleted PreferenceTest waiver.
stale = sorted(set(exceptions) - used)
if stale:
    print(f'{coord}: {len(stale)} recorded exception(s) matched NOTHING this run — the condition',
          file=sys.stderr)
    print('    they waive is gone, so they are now waiving something unknown. Delete them:',
          file=sys.stderr)
    for m, c, v in stale:
        print(f'    {m:<34}{c:<34}{v}', file=sys.stderr)
    sys.exit(1)

at_want = sum(1 for _, _, v in rows if v == want)
print(f'  {coord} resolves to {want} across {at_want} module/configuration pair(s)')
for m, c, v in sorted(used):
    print(f'    recorded exception: {m} {c} -> {v}')
RESPY
    case $? in
      0) ;;
      2) undet "$coord: resolution exceptions file is malformed" ;;
      *) fail "$coord resolution" ;;
    esac
  done
else
  print "\n[0/4] resolved version — SKIPPED, no group:name=version given."
  print "      A bump run without this cannot distinguish 'safe' from 'had no effect'."
fi

# ---------------------------------------------------------------- 1. build coverage
print "\n[1/4] build coverage"
# -PwithFlutter, or :car-uxr-flutter is excluded and its stale AAR fails the API-surface step.
( cd $GP && ./gradlew -PwithFlutter assembleDebug -q ) >/dev/null 2>&1 \
  || fail "assembleDebug did not succeed"
# Release assembly runs lint-vital, which debug does not, e.g. on the Instantiatable suppression
# CarUiRecyclerView needs (an interface car-ui-lib's inflater factory replaces at runtime).
( cd $GP && ./gradlew -PwithFlutter \
    :a2ui-host-carapp:assembleRelease :a2ui-host-carui:assembleRelease -q ) >/dev/null 2>&1 \
  || fail "demo-host assembleRelease did not succeed"
( cd $GP && ./gradlew -I $REPO/scripts/dump-source-sets.gradle help -q 2>/dev/null ) \
  | grep -E '^SRCSET|^FILTER' > /tmp/gate-ss.tsv
[[ -s /tmp/gate-ss.tsv ]] || undet "could not dump source sets"
python3 $REPO/scripts/check-build-coverage.py --source-sets /tmp/gate-ss.tsv \
  --build-dir $GP/build --baseline $REPO/scripts/build-coverage-baseline.txt --repo-root $REPO \
  | tail -4
case $? in 0) ;; 2) undet "build coverage could not be measured" ;; *) fail "build coverage" ;; esac

# Soong/Gradle parity. Report-only: nothing else here reads an Android.bp.
out=$(python3 $REPO/scripts/check-soong-parity.py 2>&1); st=$?
case $st in
  0) print "  ${out%%$'\n'*}" ;;
  2) undet "soong parity: $out" ;;
  *) fail "soong parity: $out" ;;
esac

out=$(python3 $REPO/scripts/check-screenshot-evidence.py 2>&1); st=$?
case $st in
  0) print "$out" ;;
  2) undet "screenshot evidence: $out" ;;
  *) fail "screenshot evidence: $out" ;;
esac

out=$(python3 $REPO/scripts/check-demo-assets.py 2>&1); st=$?
case $st in
  0) print "  $out" ;;
  *) fail "demo assets: $out" ;;
esac

# Timed NYC scripts are credential-free data, so they stay testable without a provider key.
out=$(python3 $REPO/scripts/check-nyc-demo.py 2>&1); st=$?
case $st in
  0) print "  $out" ;;
  *) fail "NYC demo scenarios: $out" ;;
esac

# Offline: validates the prompt, gateway schema and generation grammar; prints a redacted request.
out=$(cd $REPO && python3 scripts/claude-corpus-tester.py \
  --prompt-file a2ui-automotive/prompts/oem-poi-media-corpus.md \
  --profile quality-cached --dry-run 2>&1); st=$?
case $st in
  0) print "  Claude corpus tester grammar/prompt preflight passed" ;;
  2) undet "Claude corpus tester preflight: $out" ;;
  *) fail "Claude corpus tester preflight: $out" ;;
esac

# A javadoc citing a test is what a reader trusts instead of re-deriving the rule.
out=$(python3 $REPO/scripts/check-cited-tests.py 2>&1); st=$?
case $st in
  0) print "  ${out%%$'\n'*}" ;;
  2) undet "cited tests: $out" ;;
  *) fail "cited tests: $out" ;;
esac

# Declared <overlayable> surfaces must be in the APKs; the build succeeds either way.
out=$(python3 $REPO/scripts/check-overlayable-packaged.py 2>&1); st=$?
case $st in
  0) print "$out" ;;
  2) undet "overlayable packaging: $out" ;;
  *) fail "overlayable packaging: $out" ;;
esac

# The public AAR surface: a changed signature or removed field passes every other step. An
# unrecorded change fails; accept one by committing its baseline with the code.
out=$(python3 $REPO/scripts/check-api-surface.py 2>&1); st=$?
case $st in
  0) print "$out" | tail -1 ;;
  # rc=2 is "could not measure", not "changed". The usual cause is a source touched without a
  # content change; clear it with `./gradlew :<module>:assembleDebug --rerun-tasks`.
  2) print "$out"
     undet "published API surface could not be measured: $(print -r -- $out \
              | grep -E '^(SKIP|INDETERMINATE):' | head -1)" ;;
  *) print "$out"; fail "published API surface changed without a recorded baseline" ;;
esac

# API lint on the fork's own API, against an empty baseline: any finding is a regression.
out=$(python3 $REPO/scripts/check-api-lint.py 2>&1); st=$?
case $st in
  0) print "$out" | tail -1 ;;
  2) undet "API design lint could not be determined: $out" ;;
  *) print "$out"; fail "new API-lint finding outside the baseline" ;;
esac

# ---------------------------------------------------------------- 2. JVM suites
print "\n[2/4] JVM suites"
# Gradle's status is checked. Tasks come from gate-modules.tsv, shared with record-gate-counts.py.
JVM_TASKS=()
while IFS=$'\t' read -r project builddir; do
  [[ "$project" == \#* || -z "$project" ]] && continue
  JVM_TASKS+=("${project}:testDebugUnitTest")
done < $REPO/scripts/gate-modules.tsv
# Derived from the manifest, and non-vacuous: an unreadable manifest must not pass as 0 == 0.
GATE_MODULE_ROWS=$(grep -vcE '^[[:space:]]*(#|$)' $REPO/scripts/gate-modules.tsv)
[[ -n $GATE_MODULE_ROWS ]] && (( GATE_MODULE_ROWS > 0 )) \
  || undet "gate-modules.tsv yielded no rows; zero suites is not a set to run"

# Check A: the while-read loop and grep agree (no final newline, whitespace-only or indented lines).
(( ${#JVM_TASKS[@]} == GATE_MODULE_ROWS )) \
  || undet "gate-modules.tsv has $GATE_MODULE_ROWS rows but yielded ${#JVM_TASKS[@]} tasks"

# Check B, the ratchet: against the committed manifest, which a working-tree edit cannot move
# (gate-counts.tsv is regenerated from it). Shrinking must be committed first.
COMMITTED_ROWS=$(git -C $REPO show HEAD:scripts/gate-modules.tsv 2>/dev/null \
                   | grep -vcE '^[[:space:]]*(#|$)')
if [[ -n $COMMITTED_ROWS ]] && (( COMMITTED_ROWS > 0 )); then
  (( GATE_MODULE_ROWS >= COMMITTED_ROWS )) \
    || undet "gate-modules.tsv has $GATE_MODULE_ROWS module(s); HEAD has $COMMITTED_ROWS.
    A module was removed from the gated set in the working tree. That may be right -- but it is a
    decision, and the ledgers regenerate from this file, so nothing downstream would say so."
fi

# Clear old results first: a run that fails before testing must not be counted from stale XML.
while IFS=$'\t' read -r project builddir; do
  [[ "$project" == \#* || -z "$builddir" ]] && continue
  rm -rf $GP/build/$builddir/test-results/testDebugUnitTest
done < $REPO/scripts/gate-modules.tsv
if ! ( cd $GP && ./gradlew "${JVM_TASKS[@]}" --rerun-tasks -q ) >/dev/null 2>&1; then
  fail "a JVM test task returned non-zero"
fi
# Expected counts come from docs/gate-counts.tsv, recorded only from a green run.
while read -r m n; do
  [[ -z ${m:-} ]] && continue
  d=$GP/build/$m/test-results/testDebugUnitTest
  if [[ ! -d $d ]]; then undet "$m produced no results"; continue; fi
  # --max-failures 0 is load-bearing: without it only the executed count is asserted.
  out=$(python3 $REPO/scripts/check-instrumented-run.py $d --expect $n --max-failures 0 2>&1)
  case $? in
    0) print "  $m: ${out##*executed=}" | head -1 ;;
    2) undet "$m: $out" ;;
    *) fail "$m: $out" ;;
  esac
done < <(python3 $REPO/scripts/record-gate-counts.py --print)

# Catches a suite that ran but is absent from the ledger, which the loop above cannot see.
# Status captured on its own line: inside `if ! cmd`, $? is the negation's status.
out=$(python3 $REPO/scripts/record-gate-counts.py --check 2>&1); st=$?
case $st in
  0) print "$out" ;;
  2) undet "gate-count ledger: $out" ;;
  *) fail "gate-count ledger: $out" ;;
esac

print "\n[2b/4] coverage"

# Coverage. The report task turns testDebugUnitTest's .exec into the report.xml read here.
COV_TASKS=()
while IFS=$'\t' read -r project builddir; do
  [[ "$project" == \#* || -z "$project" ]] && continue
  COV_TASKS+=("${project}:createDebugUnitTestCoverageReport")
done < $REPO/scripts/gate-modules.tsv
# Same shared list and floor as the JVM step.
(( ${#COV_TASKS[@]} == GATE_MODULE_ROWS )) \
  || undet "gate-modules.tsv has $GATE_MODULE_ROWS rows but yielded ${#COV_TASKS[@]} coverage tasks"
# --rerun-tasks: an up-to-date report task leaves a stale report.xml for the check to read.
if ! ( cd $GP && ./gradlew "${COV_TASKS[@]}" --rerun-tasks -q ) >/dev/null 2>&1; then
  fail "a coverage report task returned non-zero"
fi
# rc=1 is a regression, rc=2 a missing report; a missing report is not a pass.
out=$(python3 $REPO/scripts/check-coverage.py 2>&1); st=$?
case $st in
  0) print "  $out" ;;
  2) undet "coverage: $out" ;;
  *) fail "coverage: $out" ;;
esac

# ---------------------------------------------------------------- 3/4. instrumented
#
# The device environment is established, not assumed: ensure-test-env.sh --fix kills the emulator
# and boots $AVD (default AAOS_35_1408p_GoogleAPIs). With another emulator or a physical head unit
# attached, pass --no-env and establish it yourself. The skip is printed.
if [[ $NO_ENV != 1 ]]; then
  print "\n[3-pre] establishing the device environment (ensure-test-env.sh --fix --strict-env)"
  # --strict-env, or the status reflects only the proxy-plugin byte check.
  if ! $REPO/scripts/ensure-test-env.sh --fix --strict-env 2>&1 | sed 's/^/  /'; then
    undet "could not establish the device environment — instrumented gates 3 and 4 did not run"
    print "\n=== gate: $LABEL -> rc=$rc ==="; exit $rc
  fi
else
  print "\n[3-pre] SKIPPED environment setup (--no-env); this run did not establish its device state"
fi

if ! adb devices 2>/dev/null | grep -q "device$"; then
  undet "no device attached — instrumented gates 3 and 4 did not run"
  print "\n=== gate: $LABEL -> rc=$rc ==="; exit $rc
fi

# Exactly one device: connectedAndroidTest runs on every attached device and merges the results,
# so a second emulator doubles every count and `executed >= expect` passes.
ATTACHED=$(adb devices 2>/dev/null | grep -c "	device$")
if (( ATTACHED != 1 )); then
  undet "expected exactly one attached device, found $ATTACHED. Gradle runs the instrumented
    suites on ALL of them and merges the results, so every count would be multiplied and the
    `executed >= expected` comparison would accept it. Detach the others and re-run:"
  adb devices -l 2>/dev/null | grep "	device" | sed 's/^/      /' >&2
  print "\n=== gate: $LABEL -> rc=$rc ==="; exit $rc
fi

print "\n[3/4] car-ui-lib instrumented (504, failing set diffed)"
rm -rf $GP/build/car-ui-lib/outputs/androidTest-results
( cd $GP && ./gradlew :car-ui-lib:connectedDebugAndroidTest ) >/tmp/gate-uilib.log 2>&1
uilib_gradle_rc=$?
D=$GP/build/car-ui-lib/outputs/androidTest-results/connected/debug
# A truncated run skips the diff: tests that never ran look like baseline failures that passed.
# The expected count comes from docs/instrumented-counts.tsv.
CARUI_EXPECT=$(python3 $REPO/scripts/record-instrumented-counts.py --print \
                 | awk '$1=="car-ui-lib"{print $2}')
[[ -n $CARUI_EXPECT ]] || undet "no car-ui-lib row in docs/instrumented-counts.tsv"
if ! python3 $REPO/scripts/check-instrumented-run.py $D --expect ${CARUI_EXPECT:-503}; then
  undet "car-ui-lib run TRUNCATED — the failing set is NOT read, and nothing about this bump is"
  undet "  established by it. Re-run. (A partial run's missing failures are missing tests.)"
  print "\n=== gate: $LABEL -> rc=$rc ==="
  exit $rc
fi
(( uilib_gradle_rc == 0 )) || undet "car-ui-lib: gradle returned $uilib_gradle_rc — the failing \
set below is read from results the build did not stand behind; see /tmp/gate-uilib.log"
python3 $REPO/scripts/dump-failing-set.py $D > /tmp/gate-failset-sig.txt
cut -f1 /tmp/gate-failset-sig.txt | sort -u > /tmp/gate-failset.txt
grep -v '^\s*#' $BASELINE_FAILSET | awk 'NF' > /tmp/gate-baseline-sig.txt
cut -f1 /tmp/gate-baseline-sig.txt | sort -u > /tmp/gate-baseline.txt

# Signature check: a baselined test failing for a different reason leaves the set unchanged.
# Compared only for tests failing in both sets.
python3 - <<'SIGPY'
import sys
def load(path):
    out = {}
    for line in open(path):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        name, _, sig = line.partition("\t")
        out[name.strip()] = sig.strip()
    return out
base = load("/tmp/gate-baseline-sig.txt")
now = load("/tmp/gate-failset-sig.txt")
drift = [(n, base[n], now[n]) for n in sorted(set(base) & set(now))
         if base[n] and now[n] and base[n] != now[n]]
if drift:
    print("SIGNATURE DRIFT in %d baselined test(s):" % len(drift))
    for n, b, a in drift:
        print("    %s\n      baselined as: %s\n      now failing  : %s" % (n, b, a))
    sys.exit(1)
SIGPY
if [[ $? -ne 0 ]]; then
  fail "a baselined test changed its failure mode — that is a regression inside a known failure"
fi
# Newly-failing only: run-to-run variance is ~31 of 497 tests, so set identity fails on noise.
# Newly-passing is reported but never gates.
if [[ ! -s $BASELINE_FAILSET ]]; then
  undet "no recorded baseline failing set at $BASELINE_FAILSET — cannot diff"
else
  newfail=$(comm -13 /tmp/gate-baseline.txt /tmp/gate-failset.txt)
  newpass=$(comm -23 /tmp/gate-baseline.txt /tmp/gate-failset.txt)
  if [[ -n $newfail ]]; then
    # A newly-failing test listed in docs/intermittent-tests.txt is indeterminate, not failed.
    if [[ -s $INTERMITTENT ]]; then
      grep -v '^\s*#' $INTERMITTENT | awk 'NF{print $1}' | sort -u > /tmp/gate-intermittent.txt
    else
      : > /tmp/gate-intermittent.txt
    fi
    hardfail=$(print -r -- $newfail | sort | comm -23 - /tmp/gate-intermittent.txt)
    if [[ -n $hardfail ]]; then
      fail "$(print -r -- $hardfail | wc -l | tr -d ' ') NEWLY-FAILING test(s):"
      print -r -- $hardfail | sed 's/^/    /' | head -20
      flaky=$(print -r -- $newfail | sort | comm -12 - /tmp/gate-intermittent.txt)
      [[ -n $flaky ]] && print -r -- $flaky | sed 's/^/    (also, recorded-intermittent) /'
    else
      undet "$(print -r -- $newfail | wc -l | tr -d ' ') newly-failing test(s), ALL recorded as"
      undet "  intermittent. This run establishes nothing either way — RE-RUN."
      print -r -- $newfail | sed 's/^/    /'
      print "  Two CONSECUTIVE failures of the same one is a regression; the gate cannot see that."
    fi
  else
    print "  no newly-failing tests (baseline $(wc -l < /tmp/gate-baseline.txt | tr -d ' '), this run $(wc -l < /tmp/gate-failset.txt | tr -d ' '))"
  fi
  if [[ -n $newpass ]]; then
    print "  NOTE: $(print -r -- $newpass | wc -l | tr -d ' ') baseline failure(s) passed this run."
    print "        Informational only — this suite flakes by ~31/497 in that direction."
    print "        Do NOT record as an improvement without a second run reproducing it."
  fi
fi

# ---------------------------------------------------------------- 4. clean instrumented suites
# Expected fully green: executed >= N and failures == 0, with no baseline to diff.
print "\n[4/4] clean instrumented suites"
while read -r m n _ceiling; do
  [[ -z ${m:-} || $m == car-ui-lib ]] && continue   # car-ui-lib is step 3, with a failing baseline
  rm -rf $GP/build/$m/outputs/androidTest-results
  # ${m} braced: in zsh `$m:c` is a history modifier that eats the ':c'.
  # Status captured: a run that wrote green results and then failed is indeterminate.
  ( cd $GP && ./gradlew ":${m}:connectedDebugAndroidTest" ) >/tmp/gate-${m}.log 2>&1
  gradle_rc=$?
  T=$GP/build/$m/outputs/androidTest-results/connected/debug
  print "  $m (expect $n/0)"
  python3 $REPO/scripts/check-instrumented-run.py $T --expect $n --max-failures 0 \
    | sed 's/^/  /' || fail "$m instrumented"
  (( gradle_rc == 0 )) || undet "$m: gradle returned $gradle_rc even though the suite parsed clean \
— see /tmp/gate-${m}.log"
done < <(python3 $REPO/scripts/record-instrumented-counts.py --print)

# Counted, so a ledger that yields nothing cannot pass vacuously.
CLEAN_RUN=$(python3 $REPO/scripts/record-instrumented-counts.py --print | grep -vc '^car-ui-lib ')
(( CLEAN_RUN >= 3 )) || undet "instrumented ledger yielded $CLEAN_RUN clean suite(s), expected >= 3"

# ...and the ledger's own agreement, which additionally enforces the per-suite failure ceiling.
out=$(python3 $REPO/scripts/record-instrumented-counts.py --check 2>&1); st=$?
case $st in
  0) print "$out" ;;
  2) undet "instrumented ledger: $out" ;;
  *) fail "instrumented ledger: $out" ;;
esac

print "\n=== gate: $LABEL -> rc=$rc ==="
exit $rc
