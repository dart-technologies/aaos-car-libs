#!/bin/zsh
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
#
# Records one demo workflow to a playable .mp4, from a named display, driving the demo itself.
#
#   - screenrecord writes the MP4 index (moov) on exit, so it is stopped with SIGINT and the file
#     is checked before use.
#   - The display is named; screenrecord's default does not say which one it used.
#   - Each workflow drives itself over `am broadcast` (debuggable builds only; see DebugBroadcasts
#     and A2uiShowcaseScreen#registerCaseReceiverIfDebuggable). It never taps, because tap
#     coordinates depend on layout. A recording whose screen never changed is rejected.
#
# Usage:
#   record-demo.sh <workflow> [output.mp4]
#   record-demo.sh --list
#   record-demo.sh --all [output-dir]
set -u

# --------------------------------------------------------------------------------- the workflows
#
# id                 host  case id / arg        what the recording is supposed to show
#
# Case rows are the UseCaseCorpus; scripts/check-demo-workflows.py asserts both name the same cases.
typeset -A CASE_MASK CASE_WHAT
CASE_MASK=(
  now_playing         0x1ff   # FULLY_RESTRICTED
  browse_library      0x20    # LIMIT_CONTENT
  video_trailer       0x10    # NO_VIDEO
  search_and_filter   0xa     # NO_KEYBOARD | NO_FILTERING
  playback_settings   0x40    # NO_SETUP
  modal_confirmation  0x20    # anything restricted refuses outright
  passenger_video     0x1ff   # see MASK NOTE below
  handoff_to_driver   0x1ff   # see MASK NOTE below
  poi_discovery_order 0x1ff   # search/order disappear or refuse; navigation remains
  big_buck_bunny      0x10    # NO_VIDEO, audio remains
)
# These two are interesting at BASELINE only because of a second display, which one recording cannot
# show; FULLY_RESTRICTED shows what the surface becomes where the driver can see it.
CASE_WHAT=(
  now_playing         "survives FULLY_RESTRICTED — the case that must still render"
  browse_library      "LIMIT_CONTENT removes Tabs and deep lists"
  video_trailer       "NO_VIDEO removes Video, AudioPlayer deliberately stays"
  search_and_filter   "NO_KEYBOARD and NO_FILTERING remove text entry and pickers separately"
  playback_settings   "NO_SETUP drops configuration entirely"
  modal_confirmation  "BASELINE confirms; anything restricted refuses outright"
  passenger_video     "the full catalog, composed on a passenger display"
  handoff_to_driver   "composed at BASELINE, re-pruned at the driver's FULLY_RESTRICTED"
  poi_discovery_order "parked search/order become moving-safe navigation-only content"
  big_buck_bunny      "parked video is removed by NO_VIDEO while audio controls remain"
)

HOST_A_PKG=art.dart.a2ui.carapp
HOST_A_ACT=androidx.car.app.activity.CarAppActivity
HOST_B_PKG=art.dart.a2ui.carui
HOST_B_ACT=art.dart.a2ui.carui.A2uiCarUiActivity

ACTION_CASE=art.dart.a2ui.carapp.action.CASE
EXTRA_CASE_ID=art.dart.a2ui.carapp.extra.CASE_ID
ACTION_LISTEN=art.dart.a2ui.carapp.action.LISTEN
ACTION_SET=art.dart.a2ui.demo.action.SET
EXTRA_ACTIVE=art.dart.a2ui.demo.extra.ACTIVE
ACTION_SPEAK=art.dart.a2ui.carui.action.SPEAK
EXTRA_PHRASE=art.dart.a2ui.carui.extra.PHRASE
ACTION_HANDOFF=art.dart.a2ui.carui.action.HANDOFF
EXTRA_TO=art.dart.a2ui.carui.extra.TO

DEVICE_MP4=/sdcard/a2ui-demo.mp4

die()  { print -u2 "FAIL: $*"; exit 1; }
undet() { print -u2 "INDETERMINATE: $*"; exit 2; }
note() { print "  $*"; }

list_workflows() {
  print "case workflows (Host A, one per UseCaseCorpus entry):"
  for id in ${(k)CASE_MASK}; do printf "  %-20s %s\n" "$id" "$CASE_WHAT[$id]"; done | sort
  print "\ncross-cutting workflows:"
  printf "  %-20s %s\n" "restrictions" "Host A: BASELINE -> FULLY_RESTRICTED -> BASELINE on one case"
  printf "  %-20s %s\n" "voice-a"      "Host A: the microphone action, and what it says when refused"
  printf "  %-20s %s\n" "voice-b"      "Host B: a spoken phrase resolved against the rendered surface"
  printf "  %-20s %s\n" "handoff"      "Host B: a surface handed to display 4, re-decided there"
}

# --------------------------------------------------------------------------------- preconditions

require_device() {
  local n
  n=$(adb devices | grep -c "	device$")
  (( n == 1 )) || undet "expected exactly one device, found $n (adb devices)"
}

# Broadcasts reach the app's user, which on AAOS is not user 0; do not hardcode 10 either.
AM_USER=(--user current)

require_installed() {
  local pkg=$1
  adb shell pm list packages "--user" current 2>/dev/null | grep -q "package:$pkg\$" \
    || undet "$pkg is not installed for the current user. Build and install it first:
    (cd aaos-apps-gradle-project && ./gradlew :a2ui-host-carapp:installDebug :a2ui-host-carui:installDebug)"
}

require_debuggable() {
  # Broadcasts are dropped on release builds; refuse rather than record a static screen.
  local pkg=$1 flags
  flags=$(adb shell dumpsys package "$pkg" 2>/dev/null | grep -m1 "flags=\[" || true)
  [[ $flags == *DEBUGGABLE* ]] \
    || undet "$pkg is not a debuggable build, so its demo receivers are not registered.
    Every broadcast this script sends would be dropped and the recording would show nothing."
}

resolve_display() {
  if [[ -n ${DISPLAY_ID:-} ]]; then print "$DISPLAY_ID"; return; fi
  adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null | awk '/^Display /{print $2; exit}'
}

# --------------------------------------------------------------------------------- the recording

start_recording() {
  local display=$1
  adb shell rm -f "$DEVICE_MP4" 2>/dev/null
  # A ceiling, not the length: the workflow ends the recording.
  adb shell screenrecord --display-id "$display" --time-limit 180 "$DEVICE_MP4" &
  RECORD_PID=$!
  # The encoder takes a moment to open; earlier frames are lost.
  sleep 2
  adb shell pidof screenrecord >/dev/null 2>&1 || die "screenrecord did not start"
}

stop_recording() {
  # SIGINT, or the moov atom is never written.
  adb shell pkill -INT screenrecord 2>/dev/null
  local waited=0
  while adb shell pidof screenrecord >/dev/null 2>&1; do
    sleep 1
    (( waited++ ))
    (( waited > 20 )) && die "screenrecord did not exit after SIGINT; the file would be unplayable"
  done
  wait $RECORD_PID 2>/dev/null
  # It has exited, but the file it wrote is finished only once the device has flushed it.
  sleep 1
}

pull_and_verify() {
  local out=$1 display=$2
  adb pull "$DEVICE_MP4" "$out" >/dev/null 2>&1 || die "could not pull $DEVICE_MP4"
  adb shell rm -f "$DEVICE_MP4" 2>/dev/null

  # The first box must be ftyp. Necessary, not sufficient: the moov check below is the real one.
  local magic
  magic=$(dd if="$out" bs=1 skip=4 count=4 2>/dev/null)
  [[ $magic == "ftyp" ]] || {
    print -u2 "FAIL: $out is not an MP4 — its first box type is '${magic}'"
    exit 1
  }
  # `moov` present anywhere means the container was finalised. Without it the file plays in nothing.
  if ! strings "$out" 2>/dev/null | grep -q "moov"; then
    die "$out has no moov atom — screenrecord was killed before it could finalise the container"
  fi

  local bytes
  bytes=$(wc -c < "$out" | tr -d ' ')
  # A weak proxy for machines without ffmpeg: a static screen compresses to almost nothing.
  (( bytes > 100000 )) || die "$out is only $bytes bytes — the screen almost certainly never changed"

  # The real check counts distinct content frames; size cannot tell a static recording from a good one.
  if (( $+commands[ffprobe] )) && (( $+commands[ffmpeg] )); then
    local duration
    duration=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out" 2>/dev/null)
    duration=${duration%%.*}
    [[ -n $duration ]] || die "$out has no readable duration"
    (( duration >= 8 )) || die "$out is only ${duration}s — too short to contain the transition"

    # Count distinct frames: screenrecord emits frames only on change, so timestamps can miss.
    # The crop drops the status and nav bars so a ticking clock does not count as change.
    local distinct
    distinct=$(ffmpeg -v error -i "$out" -vf "crop=iw:ih*0.76:0:ih*0.12" -f framehash - 2>/dev/null \
               | grep -v '^#' | awk '{print $NF}' | sort -u | wc -l | tr -d ' ')
    [[ -n $distinct && $distinct != 0 ]] || die "could not hash any frame of $out"
    (( distinct >= 3 )) || die "$out has only $distinct distinct content frame(s).
    The file is a valid MP4 of a screen that barely changed, which is what a dropped broadcast, a
    backgrounded app, or the wrong display all look like. The status and nav bars are cropped out of
    this count on purpose, so a ticking clock cannot satisfy it."
    note "recorded $out (display $display, ${bytes} bytes, ${duration}s, ftyp + moov, \
$distinct distinct content frames)"
  else
    note "recorded $out (display $display, $bytes bytes, ftyp + moov present)"
    note "NOTE: ffmpeg/ffprobe absent, so 'the screen changed' was inferred from SIZE, not measured."
  fi
}

# --------------------------------------------------------------------------------- the workflows

launch() {
  local pkg=$1 act=$2
  adb shell am start "${AM_USER[@]}" -n "$pkg/$act" >/dev/null 2>&1 \
    || die "could not launch $pkg/$act"
  sleep 3
  adb shell dumpsys activity activities 2>/dev/null | grep -q "$pkg" \
    || undet "$pkg did not come to the foreground; the recording would show the launcher"
}

bcast() {
  # adb rejoins and re-splits remote arguments, so each element is single-quoted with ${(qq)};
  # otherwise "buy album" arrives as two words.
  local -a remote=(am broadcast "${AM_USER[@]}" "$@")
  local remote_cmd="${(j: :)${(@qq)remote}}"
  adb shell "$remote_cmd" >/dev/null 2>&1
}

set_restrictions() { bcast -a "$ACTION_SET" --ei "$EXTRA_ACTIVE" "$1"; sleep 2; }
select_case()      { bcast -a "$ACTION_CASE" --es "$EXTRA_CASE_ID" "$1"; sleep 2; }

run_case_workflow() {
  local id=$1 mask=$CASE_MASK[$1]
  launch $HOST_A_PKG $HOST_A_ACT
  note "case $id — $CASE_WHAT[$id]"
  set_restrictions 0            # start from BASELINE so the change is visible in the recording
  select_case "$id"
  sleep 3                       # hold the unrestricted surface long enough to read
  note "applying $mask"
  set_restrictions "$mask"
  sleep 4                       # the transition is the point of the recording
  set_restrictions 0            # ...and back, so the recording shows it is reversible
  sleep 3
}

run_restrictions_workflow() {
  launch $HOST_A_PKG $HOST_A_ACT
  select_case now_playing
  for mask in 0 0x8 0x10 0x20 0x1ff 0; do
    note "restrictions -> $mask"
    set_restrictions "$mask"
    sleep 3
  done
}

run_voice_a_workflow() {
  # Granted so the recording is not the RECORD_AUDIO dialog; the prompt has its own tests.
  adb shell pm grant $HOST_A_PKG android.permission.RECORD_AUDIO 2>/dev/null
  launch $HOST_A_PKG $HOST_A_ACT
  select_case now_playing
  set_restrictions 0
  note "Host A: microphone at BASELINE"
  bcast -a "$ACTION_LISTEN"; sleep 4
  # NO_VOICE_TRANSCRIPTION makes voice unavailable, not merely unheard; the surface must say which.
  note "Host A: microphone under NO_VOICE_TRANSCRIPTION (0x100)"
  set_restrictions 0x100
  bcast -a "$ACTION_LISTEN"; sleep 4
  set_restrictions 0; sleep 2
}

run_voice_b_workflow() {
  adb shell pm grant $HOST_B_PKG android.permission.RECORD_AUDIO 2>/dev/null
  launch $HOST_B_PKG $HOST_B_ACT
  set_restrictions 0; sleep 2
  note "Host B: injecting the exact displayed SAFE phrase 'play'"
  bcast -a "$ACTION_SPEAK" --es "$EXTRA_PHRASE" "play"; sleep 4
  # At 0x1ff the voice gate refuses before A2uiActionPolicy is consulted (same toast either way).
  note "Host B: a CONSEQUENTIAL phrase at 0x1ff — refused by the VOICE GATE, before the action policy"
  set_restrictions 0x1ff; sleep 2
  bcast -a "$ACTION_SPEAK" --es "$EXTRA_PHRASE" "buy album"; sleep 4
  set_restrictions 0; sleep 2
}

run_handoff_workflow() {
  # The destination (virtual display 4) cannot be recorded: screenrecord rejects it and screencap
  # "succeeds" with a non-image. So this records the source and logs the destination's decision.
  adb logcat -c 2>/dev/null
  launch $HOST_B_PKG $HOST_B_ACT
  set_restrictions 0; sleep 2
  note "Host B: handing the surface to display 4 (destination is not recordable — see the .log)"
  bcast -a "$ACTION_HANDOFF" --ei "$EXTRA_TO" 4
  sleep 6
  # Apply the destination's 0x1ff here so the contrast is visible on a recordable display.
  note "Host B: applying the destination's own 0x1ff to the source, so the contrast is visible"
  set_restrictions 0x1ff
  sleep 5
  set_restrictions 0
  sleep 3
  HANDOFF_LOG=1
}

# --------------------------------------------------------------------------------- entry point

[[ ${1:-} == "--list" ]] && { list_workflows; exit 0; }

workflow=${1:?usage: record-demo.sh <workflow> [output.mp4]   (--list to see them)}

if [[ $workflow == "--all" ]]; then
  outdir=${2:-demo-recordings}
  mkdir -p "$outdir" || die "could not create $outdir"
  failed=0
  for id in ${(ok)CASE_MASK} restrictions voice-a voice-b handoff; do
    print "\n=== $id"
    "$0" "$id" "$outdir/$id.mp4" || failed=1
  done
  (( failed == 0 )) || die "at least one workflow did not record"
  print "\nall workflows recorded into $outdir/"
  exit 0
fi

out=${2:-$workflow.mp4}

require_device
display=$(resolve_display)
[[ -n $display ]] || undet "could not determine a display id"

case $workflow in
  restrictions|voice-a) pkg=$HOST_A_PKG ;;
  voice-b|handoff)      pkg=$HOST_B_PKG ;;
  *)
    [[ -n ${CASE_MASK[$workflow]:-} ]] || die "unknown workflow '$workflow' (--list to see them)"
    pkg=$HOST_A_PKG
    ;;
esac
require_installed "$pkg"
require_debuggable "$pkg"

start_recording "$display"
case $workflow in
  restrictions) run_restrictions_workflow ;;
  voice-a)      run_voice_a_workflow ;;
  voice-b)      run_voice_b_workflow ;;
  handoff)      run_handoff_workflow ;;
  *)            run_case_workflow "$workflow" ;;
esac
stop_recording
pull_and_verify "$out" "$display"

if [[ -n ${HANDOFF_LOG:-} ]]; then
  # The evidence the video cannot carry, because the destination display is not recordable.
  log_out="${out%.mp4}.log"
  adb logcat -d -s A2uiCarUiActivity:I 2>/dev/null | grep -E "handoff |displays this app can see" \
      > "$log_out" || true
  if [[ -s $log_out ]]; then
    note "handoff evidence in $log_out:"
    grep -E "handoff " "$log_out" | tail -1 | sed 's/^/    /'
  else
    # Not fatal -- the video is still valid -- but the run proved less than it set out to.
    print -u2 "  WARNING: no handoff log line was captured, so this recording shows the surface
    being handed off and NOTHING about what the destination did with it."
  fi
fi
