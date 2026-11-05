#!/bin/zsh
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
#
# Captures a screenshot that is actually a PNG, from a display that is actually named. Unnamed,
# screencap picks a display with no consistency guarantee and, over exec-out, prepends its warning
# to the image.
#
# Usage: capture-screenshot.sh <output.png> <display-id> <host> <package> <live|demo> <command>
set -u

out=${1:?usage: capture-screenshot.sh <output.png> <display-id> <host> <package> <live|demo> <command>}
display=${2:?an explicit display id is required}
host=${3:?host is required}
package=${4:?package is required}
state=${5:?live or demo state is required}
command=${6:?the command that established the state is required}
[[ $state == live || $state == demo ]] || {
  print -u2 "FAIL: state must be 'live' or 'demo'"
  exit 2
}

remote="/data/local/tmp/a2ui-screenshot-$$.png"
trap 'adb shell rm -f "$remote" >/dev/null 2>&1 || true' EXIT

# Saved on-device, not streamed over exec-out: some images print a FORTIFY diagnostic after IEND.
# Some API-35 images also exit non-zero after writing a complete PNG, so the PNG structure below
# decides, not the exit status.
capture_status=1
remote_ready=0
for capture_attempt in {1..3}; do
  adb shell rm -f "$remote" >/dev/null 2>&1 || true
  # Keep the remote pipe attached: redirecting both streams makes screencap abort on API-35 images.
  adb shell screencap -p -d "$display" "$remote"
  capture_status=$?
  for ready_attempt in {1..10}; do
    if adb shell test -s "$remote" >/dev/null 2>&1; then
      remote_ready=1
      break
    fi
    sleep 0.2
  done
  (( remote_ready )) && break
done
(( remote_ready )) || {
  print -u2 "FAIL: screencap produced no file for display $display (exit $capture_status)"
  exit 1
}
adb pull "$remote" "$out" >/dev/null 2>&1 || {
  print -u2 "FAIL: could not retrieve screenshot for display $display"
  exit 1
}
adb shell rm -f "$remote" >/dev/null 2>&1 || true
trap - EXIT

# Verify it is a PNG: a capture that silently is not an image goes unnoticed.
magic=$(head -c 8 "$out" | xxd -p)
if [[ $magic != "89504e470d0a1a0a" ]]; then
  print -u2 "FAIL: $out is not a PNG — it starts with:"
  head -c 120 "$out" | strings | head -2 | sed 's/^/    /' >&2
  exit 1
fi

script_dir=${0:A:h}
python3 "$script_dir/check-screenshot-evidence.py" --write "$out" \
  --host "$host" --package "$package" --display "$display" --state "$state" \
  --command "$command" || exit $?

bytes=$(wc -c < "$out" | tr -d ' ')
print "  captured $out (display $display, $bytes bytes, valid PNG + metadata)"
