#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Runs metalava's API lint over the API surfaces this fork authors.

check-api-surface.py asks whether the published surface moved; this asks whether it follows
Android's API design rules. Metalava reads source, so it cannot replace the AAR-based checker.

Only fork-authored modules are linted (LINTED below). Inherited AOSP code is not: its findings
could only be baselined wholesale.

docs/api-lint-baseline.txt is deliberately empty: fix each finding at source, or suppress it at
the declaration with a reason. --update-baseline rewrites that file wholesale, header included.

Usage:
  check-api-lint.py                  lint; rc=1 on a finding not in the baseline
  check-api-lint.py --update-baseline  accept the current findings as the baseline
Exit codes: 0 clean, 1 a new finding, 2 could not be determined.
"""
import argparse
import os
import pathlib
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GP = os.path.join(REPO, "aaos-apps-gradle-project")
BASELINE = os.path.join(REPO, "docs", "api-lint-baseline.txt")

# Adding to this list is deliberate; each entry and non-entry has a measured reason.
LINTED = [
    ":a2ui-model",          # fork-authored, published library
    ":a2ui-host-carapp",    # fork-authored APK
    ":a2ui-host-carui",     # fork-authored APK
    ":car-uxr-flutter",     # mostly fork-authored
]

# Not linted: :car-uxr-client-lib, whose findings are all in AOSP-copyright files this fork does
# not own. Every other module in settings.gradle is inherited AOSP.


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def metalava_classpath():
    out = run([os.path.join(GP, "gradlew"), "-p", GP,
               "-I", os.path.join(REPO, "scripts", "metalava-resolver.gradle"),
               "resolveMetalava", "-q"])
    # Read from the task's output: the root project's buildDir is "build/AAOS Apps/".
    path = None
    for line in out.stdout.splitlines():
        if line.startswith("METALAVA_CP_FILE="):
            path = line.split("=", 1)[1].strip()
    if out.returncode != 0 or not path or not os.path.exists(path):
        detail = out.stderr.strip()[:300] or out.stdout.strip()[:300] or "no output"
        return None, "could not resolve metalava: " + detail
    text = pathlib.Path(path).read_text().strip()
    return (text, None) if text else (None, "metalava classpath resolved empty")


def module_inputs():
    """{project: (source_dirs, classpath_entries)} straight from Gradle."""
    # -PwithFlutter, or ':car-uxr-flutter' is excluded and reports "produced no inputs".
    out = run([os.path.join(GP, "gradlew"), "-p", GP, "-PwithFlutter",
               "-I", os.path.join(REPO, "scripts", "dump-api-lint-inputs.gradle"),
               # `help`: the dumper prints at projectsEvaluated, so any task triggers it.
               "help", "-q"])
    if out.returncode != 0:
        return None, "could not dump inputs: " + (out.stderr.strip()[:300] or "no output")
    srcs, cps = {}, {}
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        kind, project, value = parts
        if kind == "APILINT_SRC":
            srcs.setdefault(project, []).append(value)
        elif kind == "APILINT_CP":
            cps.setdefault(project, []).append(value)
    return {p: (srcs.get(p, []), cps.get(p, [])) for p in set(srcs) | set(cps)}, None


def java_files(dirs):
    found = []
    for d in dirs:
        for dirpath, _dirs, files in os.walk(d):
            found.extend(os.path.join(dirpath, f) for f in files if f.endswith(".java"))
    return sorted(found)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--modules", help="comma-separated gradle paths to lint instead of LINTED; "
                                      "for surveying a module before deciding to adopt it")
    args = ap.parse_args()
    linted = [m.strip() for m in args.modules.split(",")] if args.modules else LINTED

    cp, err = metalava_classpath()
    if err:
        print("INDETERMINATE: " + err, file=sys.stderr)
        return 2
    inputs, err = module_inputs()
    if err:
        print("INDETERMINATE: " + err, file=sys.stderr)
        return 2

    rc = 0
    for project in linted:
        if project not in inputs:
            # Not a pass: a module Gradle did not report is a module nobody linted.
            print("INDETERMINATE: %s produced no inputs (not in settings.gradle for this "
                  "invocation?)" % project, file=sys.stderr)
            return 2
        src_dirs, classpath = inputs[project]
        sources = java_files(src_dirs)
        if not sources:
            print("INDETERMINATE: %s has no java sources to lint" % project, file=sys.stderr)
            return 2
        if not classpath:
            # Without android.jar the findings are about missing classes, not API design.
            print("INDETERMINATE: %s has no classpath (android.jar missing?)" % project,
                  file=sys.stderr)
            return 2
        # android.jar by name: a non-empty classpath without it still yields confident nonsense.
        if not any(os.path.basename(e) == "android.jar" for e in classpath):
            print("INDETERMINATE: %s classpath has no android.jar; refusing to lint it badly"
                  % project, file=sys.stderr)
            return 2

        # --no-color is a top-level option and must precede `main`.
        cmd = ["java", "-cp", cp, "com.android.tools.metalava.Driver", "--no-color", "main",
               "--api-lint",
               "--source-path", ":".join(src_dirs),
               "--classpath", ":".join(classpath)]
        if args.update_baseline:
            cmd += ["--update-baseline", BASELINE]
        elif os.path.exists(BASELINE):
            cmd += ["--baseline", BASELINE]
        cmd += sources

        out = run(cmd, cwd=REPO)
        combined = (out.stdout + out.stderr).strip()
        if args.update_baseline:
            print("recorded baseline for %s -> %s" % (project, os.path.relpath(BASELINE, REPO)))
            continue
        if out.returncode == 0:
            print("OK:   %-14s no API-lint findings outside the baseline" % project)
        else:
            rc = 1
            print("FAIL: %s has API-lint findings not in the baseline:" % project)
            for line in combined.splitlines()[:40]:
                print("  " + line)
    if rc == 0 and not args.update_baseline:
        print("\nOK: the fork's own API passes metalava's design lint.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
