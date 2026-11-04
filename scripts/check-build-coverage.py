#!/usr/bin/env python3
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
"""Which .java files in this repository actually get compiled — measured, not inferred.

Exclusion mechanisms vary (java.excludes, java.includes, per-file excludes), so this measures the
outcome: every .java under every configured source root against every .class the build produced.

USAGE

    ./gradlew -I ../scripts/dump-source-sets.gradle help -q | grep -E '^SRCSET|^FILTER' > /tmp/ss.tsv
    ./gradlew assembleDebug
    python3 scripts/check-build-coverage.py --source-sets /tmp/ss.tsv --build-dir <buildDir>

Exits non-zero if any source file is compiled by NO module.
"""

import argparse
import collections
import fnmatch
import os
import re
import sys

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)


def parse_source_sets(path):
    """Returns (srcdirs: {module: [dir]}, includes: {module: [pat]}, excludes: {module: [pat]})."""
    srcdirs = collections.defaultdict(list)
    includes = collections.defaultdict(list)
    excludes = collections.defaultdict(list)
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "SRCSET" and len(parts) >= 3:
                srcdirs[parts[1]].append(parts[2])
            elif parts[0] == "FILTER" and len(parts) >= 4:
                (includes if parts[2] == "include" else excludes)[parts[1]].append(parts[3])
    return srcdirs, includes, excludes


def matches(rel, patterns):
    """Ant-ish glob match, which is all these build files use."""
    for pattern in patterns:
        if fnmatch.fnmatch(rel, pattern):
            return True
        # '**/*.java' must also match a file at the root of the source dir.
        if pattern.startswith("**/") and fnmatch.fnmatch(rel, pattern[3:]):
            return True
    return False


def sources_for(module, srcdirs, includes, excludes):
    """Every .java the module's configuration selects, as (abs_path, expected_class_path)."""
    out = []
    inc = includes.get(module) or ["**/*.java"]
    exc = excludes.get(module) or []
    for root in srcdirs.get(module, []):
        if not os.path.isdir(root):
            continue
        for dirpath, _, filenames in os.walk(root):
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                abs_path = os.path.join(dirpath, name)
                rel = os.path.relpath(abs_path, root)
                if not matches(rel, inc) or matches(rel, exc):
                    continue
                out.append((abs_path, expected_class(abs_path, name)))
    return out


def expected_class(abs_path, name):
    """Top-level class path implied by the file's own package declaration."""
    try:
        with open(abs_path, encoding="utf-8", errors="ignore") as handle:
            match = PACKAGE_RE.search(handle.read())
    except OSError:
        match = None
    pkg = match.group(1).replace(".", "/") + "/" if match else ""
    return pkg + name[: -len(".java")] + ".class"


def compiled_classes(build_dir):
    """Every top-level .class the build produced, mapped to the module dirs that produced it."""
    produced = collections.defaultdict(set)
    for dirpath, _, filenames in os.walk(build_dir):
        if os.path.basename(dirpath) == "classes" and "javac" in dirpath:
            continue
        for name in filenames:
            if not name.endswith(".class"):
                continue
            full = os.path.join(dirpath, name)
            # .../build/<module>/intermediates/javac/<variant>/<task>/classes/<pkg>/<Name>.class
            marker = os.sep + "classes" + os.sep
            if marker not in full:
                continue
            rel = full.split(marker, 1)[1]
            module = os.path.relpath(full, build_dir).split(os.sep)[0]
            produced[rel].add(module)
    return produced


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-sets", required=True)
    parser.add_argument("--build-dir", required=True)
    parser.add_argument("--baseline", help="file of knowingly-uncompiled paths, each with a reason")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args()

    baseline = set()
    if args.baseline and os.path.isfile(args.baseline):
        with open(args.baseline, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#"):
                    baseline.add(os.path.abspath(os.path.join(args.repo_root, line)))

    srcdirs, includes, excludes = parse_source_sets(args.source_sets)
    produced = compiled_classes(args.build_dir)

    if not srcdirs:
        # Nothing selected must not print "OK: all 0 files" (a cache hit suppresses the dump).
        print(f"FAIL: no source sets parsed from {args.source_sets}", file=sys.stderr)
        print("      Zero files selected is not zero files wrong. Check that "
              "dump-source-sets.gradle still prints SRCSET rows.", file=sys.stderr)
        return 2

    if not produced:
        print(f"FAIL: no .class files under {args.build_dir}", file=sys.stderr)
        print("      Nothing was built, so this measures nothing. Run assembleDebug first.",
              file=sys.stderr)
        return 2

    # Which module(s) claim each source file, and is it compiled anywhere?
    claimed = collections.defaultdict(list)
    per_module = {}
    for module in sorted(srcdirs):
        files = sources_for(module, srcdirs, includes, excludes)
        per_module[module] = files
        for abs_path, cls in files:
            claimed[abs_path].append((module, cls))

    orphans = []
    for abs_path, entries in sorted(claimed.items()):
        if not any(cls in produced for _, cls in entries):
            orphans.append((abs_path, entries))

    # A file filtered out by every module never enters `claimed`, so walk the roots directly.
    all_roots = {d for dirs in srcdirs.values() for d in dirs if os.path.isdir(d)}
    on_disk = set()
    for root in all_roots:
        for dirpath, _, filenames in os.walk(root):
            for name in filenames:
                if name.endswith(".java"):
                    on_disk.add(os.path.join(dirpath, name))
    unselected_all = sorted(on_disk - set(claimed))
    unselected = [p for p in unselected_all if os.path.abspath(p) not in baseline]
    accepted = [p for p in unselected_all if os.path.abspath(p) in baseline]
    stale_baseline = sorted(baseline - {os.path.abspath(p) for p in unselected_all})

    print(f"{'module':<32} {'selected':>9} {'compiled':>9}  filters")
    print("-" * 78)
    for module in sorted(per_module):
        files = per_module[module]
        built = sum(1 for _, cls in files if cls in produced)
        marks = []
        if excludes.get(module):
            marks.append(f"{len(excludes[module])} exclude")
        inc = includes.get(module) or []
        if inc and inc != ["**/*.java"]:
            marks.append(f"{len(inc)} explicit include")
        flag = "" if built == len(files) else "   <-- MISMATCH"
        print(f"{module:<32} {len(files):>9} {built:>9}  {', '.join(marks) or '-'}{flag}")

    shared = {d: [m for m in srcdirs if d in srcdirs[m]] for d in
              {d for dirs in srcdirs.values() for d in dirs}}
    shared = {d: ms for d, ms in shared.items() if len(ms) > 1}
    if shared:
        print("\nSHARED SOURCE ROOTS — no single build.gradle describes what ships:")
        for directory, modules in sorted(shared.items()):
            print(f"  {directory}\n      claimed by: {', '.join(sorted(modules))}")

    print()
    failed = False

    if orphans:
        failed = True
        print(f"FAIL: {len(orphans)} source file(s) selected by a module but never compiled:",
              file=sys.stderr)
        for abs_path, entries in orphans:
            print(f"  {abs_path}", file=sys.stderr)
            for module, cls in entries:
                print(f"      selected by {module}, expected {cls}", file=sys.stderr)

    if unselected:
        failed = True
        print(f"FAIL: {len(unselected)} .java file(s) sit under a configured source root but are",
              file=sys.stderr)
        print("      selected by NO module — filtered out everywhere, compiled nowhere:",
              file=sys.stderr)
        for abs_path in unselected:
            owners = [m for m in srcdirs
                      if any(abs_path.startswith(d + os.sep) for d in srcdirs[m])]
            print(f"  {abs_path}", file=sys.stderr)
            print(f"      inside the source root of: {', '.join(sorted(owners))}",
                  file=sys.stderr)

    if stale_baseline:
        failed = True
        print(f"FAIL: {len(stale_baseline)} baseline entr(ies) no longer describe anything —",
              file=sys.stderr)
        print("      the file is now compiled, or moved, or was deleted. Remove the entry:",
              file=sys.stderr)
        for path in stale_baseline:
            print(f"  {path}", file=sys.stderr)

    if failed:
        print("\n      A file present in the tree and absent from every build is"
              " indistinguishable", file=sys.stderr)
        print("      from a covered one. If an exclusion is intended, add it to the baseline"
              " WITH", file=sys.stderr)
        print("      its reason — that converts a silent consequence into a recorded decision.",
              file=sys.stderr)
        return 1

    print(f"  OK: all {len(claimed)} .java files under every configured source root are")
    print("      selected by some module and compiled.")
    if accepted:
        print(f"      {len(accepted)} knowingly excluded, each with a recorded reason in"
              f" {os.path.basename(args.baseline)}:")
        for path in accepted:
            print(f"        {os.path.relpath(path, args.repo_root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
