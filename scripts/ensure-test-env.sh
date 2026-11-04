#!/bin/zsh
# Copyright (C) 2026 Dart Technologies
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# Brings the AAOS emulator into the state every instrumented measurement here assumes, and verifies it.
#
# The image's car-ui-lib proxy plugin is built against an older oem-apis and crashes
# :car-ui-lib:connectedDebugAndroidTest. Replacing it needs -writable-system, which an emulator
# restart silently drops, so this verifies byte-equality rather than trusting the push.
#
#   scripts/ensure-test-env.sh          # verify only; non-zero if the environment is wrong
#   scripts/ensure-test-env.sh --fix    # boot -writable-system, push the plugin, verify

set -o pipefail
STRICT_ENV=""
for _a in "$@"; do [[ $_a == --strict-env ]] && STRICT_ENV=1; done
REPO=${0:a:h:h}
AVD=${AVD:-AAOS_35_1408p_GoogleAPIs}
PLUGIN=$REPO/aaos-apps-gradle-project/build/plugin/outputs/apk/debug/plugin-debug.apk
TARGET=/system/app/car-ui-lib-proxyplugin/car-ui-lib-proxyplugin.apk
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}

boot_wait() {
  adb wait-for-device
  for i in {1..90}; do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && return 0
    sleep 10
  done
  print -u2 "FAIL: boot did not complete"; return 1
}

verify() {
  [[ -f $PLUGIN ]] || { print -u2 "FAIL: no plugin APK built at $PLUGIN"; return 2 }
  adb pull $TARGET /tmp/.env-check.apk >/dev/null 2>&1 \
    || { print -u2 "FAIL: could not read $TARGET — is a device attached?"; return 2 }
  if cmp -s /tmp/.env-check.apk $PLUGIN; then
    print "OK: installed proxy plugin is byte-identical to :plugin:assembleDebug output."
    return 0
  fi
  print -u2 "WRONG ENVIRONMENT: the installed proxy plugin is NOT this repo's build."
  print -u2 "  installed: $(wc -c < /tmp/.env-check.apk | tr -d ' ') bytes"
  print -u2 "  expected:  $(wc -c < $PLUGIN | tr -d ' ') bytes"
  print -u2 "  Measurements taken now will use the image's older oem-apis: the suite truncates at"
  print -u2 "  227/497 and the baseline reads 50 failures instead of 13. Re-run with --fix."
  return 1
}

# Wait for the device to go idle; a suite started into boot activity truncates.
wait_idle() {
  print "waiting for the device to settle..."
  for i in {1..40}; do
    local la=$(adb shell cat /proc/loadavg 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
    local inst=$(adb shell "ps -A | grep -c instrument" 2>/dev/null | tr -d '\r')
    [[ -z $la ]] && { sleep 10; continue }
    if (( ${la%.*} < 2 )) && [[ ${inst:-0} -le 1 ]]; then
      print "  settled: loadavg=$la, no instrumentation running"
      check_device_cleanliness || return 1
      return 0
    fi
    sleep 15
  done

  # Must not return 0 on timeout, or `wait_idle || exit 1` never fires and --strict-env is lost.
  print -u2 "WARN: device still busy after ~10min"
  if [[ -n $STRICT_ENV ]]; then
    print -u2 "FAIL: --strict-env given and the device never settled, so cleanliness was never"
    print -u2 "      measured. This is INDETERMINATE, not a clean environment."
    return 1
  fi
  # Not strict: a caveat, and cleanliness is still checked.
  print -u2 "  proceeding anyway; re-run with --strict-env to make this a hard failure"
  check_device_cleanliness || return 1
  return 0
}

# Device cleanliness, recorded beside the result. WARNs by default; --strict-env makes it fatal.
check_device_cleanliness() {
  local problems=0

  local leftovers=$(adb shell "ps -A -o NAME" 2>/dev/null | tr -d '\r' \
    | grep -E '^(art\.dart\.a2ui\.|com\.android\.car\.ui\.paintbooth)' | sort -u)
  if [[ -n $leftovers ]]; then
    print -u2 "  DIRTY: showcase/demo app(s) still running:"
    print -r -- "$leftovers" | sed 's/^/    /' >&2
    print -u2 "    They hold virtual displays and CPU. Clear with:"
    print -u2 "      for p in art.dart.a2ui.carapp art.dart.a2ui.carui com.android.car.ui.paintbooth; do adb shell am force-stop \$p; done"
    problems=$((problems+1))
  fi

  local displays=$(adb shell dumpsys display 2>/dev/null | grep -cE '^  Display [0-9]' | tr -d '\r')
  print "  displays=${displays:-?} (recorded, not asserted - the expected count is AVD-specific)"

  # Tests find controls by localized content descriptions, so the locale is test input.
  local locales=$(adb shell settings --user current get system system_locales 2>/dev/null \
    | tr -d '\r')
  local system_locales=$(adb shell settings --user 0 get system system_locales 2>/dev/null \
    | tr -d '\r')
  local resource_config=$(adb shell cmd activity get-config 2>/dev/null | head -1 | tr -d '\r')
  if [[ $locales != en-US* || $resource_config != *-en-rUS-* ]]; then
    print -u2 "  DIRTY: current-user locale list is '${locales:-<unreadable>}', expected en-US first"
    print -u2 "    active resource configuration: ${resource_config:-<unreadable>}"
    problems=$((problems+1))
  else
    print "  locale is deterministic (current=${locales}, user0=${system_locales:-<unreadable>}, resources=en-US)"
  fi

  local up=$(adb shell cat /proc/uptime 2>/dev/null | tr -d '\r' | cut -d' ' -f1 | cut -d. -f1)
  if [[ -n $up ]] && (( up > 43200 )); then
    print -u2 "  NOTE: guest uptime ${up}s (>12h). A cold boot is the standard control when a result"
    print -u2 "        looks environmental."
  fi

  # Espresso does not wait for animations, so non-zero scales make every instrumented suite racy.
  local scales_bad=0
  local scales_unknown=0
  local scale_report=""
  for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    local v=$(adb shell settings get global $key 2>/dev/null | tr -d '\r')
    if [[ -z $v ]]; then
      # Empty is not unset ("null"): adb answered nothing, so the scale is unknown, not wrong.
      scale_report="${scale_report}${key}=<unreadable> "
      scales_unknown=$((scales_unknown+1))
    else
      scale_report="${scale_report}${key}=${v} "
      # "null" is unset, i.e. the default 1.0: animations on.
      if [[ $v != "0.0" && $v != "0" ]]; then
        scales_bad=$((scales_bad+1))
      fi
    fi
  done
  if (( scales_unknown > 0 )); then
    print -u2 "  UNKNOWN: could not read the animation scales: ${scale_report}"
    print -u2 "    The device stopped answering; this is not a statement about the scales."
    problems=$((problems+1))
  elif (( scales_bad > 0 )); then
    print -u2 "  DIRTY: animation scales are not all zero: ${scale_report}"
    print -u2 "    Espresso does not synchronise on animations. Set them with:"
    print -u2 "      for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global \$k 0; done"
    problems=$((problems+1))
  else
    print "  animation scales are all zero (${scale_report})"
  fi

  if (( problems > 0 )); then
    if [[ -n $STRICT_ENV ]]; then
      print -u2 "FAIL: --strict-env given and the device is not clean"
      return 1
    fi
    print -u2 "  proceeding anyway; re-run with --strict-env to make this a hard failure"
  else
    print "  device is clean: no leftover showcase apps"
  fi
  return 0
}

if [[ ${1:-} != "--fix" ]]; then
  # Without `|| exit`, --strict-env printed FAIL and exited 0.
  wait_idle || exit 1
  verify; exit $?
fi

print "=== rebuilding the plugin from source ==="
# -PwithFlutter, matching the gate's project set; mixing configurations confuses check-api-surface.py.
( cd $REPO/aaos-apps-gradle-project && ./gradlew -PwithFlutter :plugin:assembleDebug -q ) || exit 2

print "=== booting $AVD with -writable-system ==="
adb emu kill 2>/dev/null; sleep 6
nohup emulator -avd $AVD -writable-system -no-snapshot-load -no-audio -no-boot-anim \
  > /tmp/emu-env.log 2>&1 &
boot_wait || exit 2

adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
adb remount >/dev/null 2>&1
# First remount disables verity and requires a reboot before /system is actually writable.
adb reboot; boot_wait || exit 2
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
adb remount 2>&1 | tail -1

print "=== pushing $PLUGIN -> $TARGET ==="
adb push $PLUGIN $TARGET 2>&1 | tail -1
adb shell "chmod 644 $TARGET"
# Set the boot user's locale before the final reboot; some system processes read persist.sys.locale.
adb shell settings --user 0 put system system_locales en-US
adb shell setprop persist.sys.locale en-US
adb reboot; boot_wait || exit 2

# AAOS switches to a foreground user during boot, so write that numeric user after the reboot, then
# switch away and back so running resources reload. No hard-coded user 10.
print "=== selecting deterministic en-US locale ==="
foreground_user=$(adb shell am get-current-user 2>/dev/null | tr -d '\r')
[[ $foreground_user == <-> ]] || { print -u2 "FAIL: could not resolve foreground user"; exit 2; }
adb shell settings --user "$foreground_user" put system system_locales en-US
if [[ $foreground_user != 0 ]]; then
  adb shell am switch-user 0 >/dev/null || exit 2
  adb shell am switch-user "$foreground_user" >/dev/null || exit 2
fi
for i in {1..30}; do
  active_locales=$(adb shell settings --user current get system system_locales 2>/dev/null \
    | tr -d '\r')
  active_config=$(adb shell cmd activity get-config 2>/dev/null | head -1 | tr -d '\r')
  [[ $active_locales == en-US* && $active_config == *-en-rUS-* ]] && break
  sleep 2
done
if [[ $active_locales != en-US* || $active_config != *-en-rUS-* ]]; then
  print -u2 "FAIL: locale did not propagate (user=$foreground_user locales=$active_locales config=$active_config)"
  exit 2
fi

# Animations off, after the last reboot: a -no-snapshot-load boot restores the default 1.0.
# check_device_cleanliness reads them back, since puts can silently no-op right after boot.
print "=== disabling animations ==="
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 0
done

# `|| exit 1`, or --fix --strict-env prints FAIL and exits 0 when the plugin matches.
wait_idle || exit 1
verify
