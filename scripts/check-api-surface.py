#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Pins the public API surface of the AARs this repository publishes.

No test here is a downstream consumer, so a changed public or protected signature passes every
suite. This reads the surface from the built AAR, which is what a consumer gets, and compares it
with a checked-in baseline. A change is not an error; an unrecorded change is.

Excluded: R, R$* and BuildConfig (generated churn). Protected members are API to a subclass and
are included. The R package is pinned separately; see manifest_package_of().

Usage
-----
  check-api-surface.py            compare against the baseline; rc=1 on an unrecorded change
  check-api-surface.py --record   rewrite the baseline (i.e. accept the change deliberately)
"""
import argparse
import io
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GP = os.path.join(REPO, "aaos-apps-gradle-project")
BASELINE_DIR = os.path.join(REPO, "docs", "api-surface")

# Modules are discovered, not listed: every com.android.library project in settings.gradle is
# published and pinned, and apps are skipped. Exclusions are explicit and carry a reason.
EXCLUDED = {
    "car-media-common-consumer-fixture":
            "consumer-only compile fixture, never published",
    # Pinning them would pin our copy of someone else's API.
    "car-system-stubs": "compile-only platform stubs, never published",
    "oem-apis": "no main sources; the plugin contract comes from the published artifact",
}

SETTINGS = os.path.join(GP, "settings.gradle")

# Distinguishes "this module is switched off in this configuration" from "this module\n# should be here and is not". Only the second is a reason to withhold a verdict.
OPTIONAL_ABSENT = "conditionally included and not built in this configuration"

LIBRARY_PLUGIN = "com.android.library"
APPLICATION_PLUGIN = "com.android.application"

# Plugin ids are read from code, never comments; alias() is resolved through the version catalog.
_PLUGIN_ID = re.compile(r"""(?:\bapply\s*\(?\s*plugin\s*[:=]\s*|\bid\s*\(?\s*)['"]([\w.\-]+)['"]""")
_PLUGIN_ALIAS = re.compile(r"\balias\s*\(\s*([A-Za-z_][\w.]*)\s*\)")


def _strip_comments(text):
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", text, flags=re.S))


def catalog_plugins():
    """{'libs.plugins.android.library': 'com.android.library', ...} from gradle/*.versions.toml."""
    out = {}
    gradle_dir = os.path.join(GP, "gradle")
    for entry in sorted(os.listdir(gradle_dir)) if os.path.isdir(gradle_dir) else []:
        if not entry.endswith(".versions.toml"):
            continue
        try:
            import tomllib
        except ImportError:
            return out  # Python < 3.11: aliases stay unresolved, so their modules are UNCLASSIFIED
        with open(os.path.join(gradle_dir, entry), "rb") as f:
            plugins = tomllib.load(f).get("plugins", {})
        catalog = entry[:-len(".versions.toml")]
        for key, spec in plugins.items():
            plugin_id = spec.get("id") if isinstance(spec, dict) else str(spec).split(":")[0]
            out[catalog + ".plugins." + re.sub(r"[-_]", ".", key)] = plugin_id
    return out


def applied_plugins(build_text, catalog):
    """(plugin ids a build file applies, plugin aliases it uses that no catalog defines)."""
    text = _strip_comments(build_text)
    ids = set(_PLUGIN_ID.findall(text))
    unknown = []
    for ref in _PLUGIN_ALIAS.findall(text):
        if ref in catalog:
            ids.add(catalog[ref])
        else:
            unknown.append(ref)
    return ids, unknown


def discover_modules():
    """Every com.android.library project in settings.gradle, minus justified exclusions."""
    text = open(SETTINGS, encoding="utf-8").read()
    explicit = dict(re.findall(
        r"project\('\:([^']+)'\)\.projectDir\s*=\s*new File\('([^']+)'\)", text))
    included = re.findall(r"^\s*include\s+'\:([^']+)'", text, re.MULTILINE)

    # A nested project may have no projectDir line; Gradle derives it from its parent.
    dirs = {}
    for name in included:
        if name in explicit:
            dirs[name] = explicit[name]
            continue
        parent, _, leaf = name.rpartition(":")
        if parent and parent in explicit:
            dirs[name] = explicit[parent] + "/" + leaf
        else:
            dirs[name] = name.replace(":", "/")

    # An include inside `if (...) { }` is conditional: ':car-uxr-flutter' needs -PwithFlutter.
    optional = set()
    depth = 0
    for line in text.splitlines():
        stripped = line.strip()
        m = re.match(r"include\s+'\:([^']+)'", stripped)
        if m and depth > 0:
            optional.add(m.group(1))
        depth += stripped.count("{") - stripped.count("}")
        depth = max(depth, 0)

    catalog = catalog_plugins()
    modules, skipped = [], []
    for name, rel in sorted(dirs.items()):
        short = name.split(":")[-1]
        if short in EXCLUDED:
            continue
        # settings.gradle paths are relative to the gradle project dir.
        gradle_file = os.path.normpath(os.path.join(GP, rel, "build.gradle"))
        try:
            build = open(gradle_file, encoding="utf-8").read()
        except OSError:
            skipped.append((short, "no build.gradle at " + rel))
            continue
        ids, unknown = applied_plugins(build, catalog)
        is_library, is_app = LIBRARY_PLUGIN in ids, APPLICATION_PLUGIN in ids
        if is_library and not is_app:
            modules.append(short)
            MODULE_DIRS[short] = os.path.normpath(os.path.join(GP, rel))
            # The full path: ':car-messenger-common:model' shortens to 'model', not a task path.
            MODULE_PATHS[short] = ":" + name
            if name in optional:
                OPTIONAL_MODULES.add(short)
        elif is_app and not is_library:
            continue  # an APK: nobody links against it
        elif unknown:
            skipped.append((short, "plugin alias not in any gradle/*.versions.toml: "
                            + ", ".join(unknown)))
        elif is_library:
            skipped.append((short, "applies both library and application plugins"))
        else:
            skipped.append((short, "neither library nor application in build.gradle"))
    return modules, skipped


GENERATED = re.compile(r"(^|/)(R|R\$[^/]*|BuildConfig)\.class$")


# Only what ends up in the AAR; test sources cannot change a published surface.
NOT_PUBLISHED = ("/androidtest/", "/test/", "/tests/", "/debug/", "/androidtestdebug/")


# Filled in by discover_modules(): real project dirs and conditional includes.
MODULE_DIRS = {}
MODULE_PATHS = {}
OPTIONAL_MODULES = set()


def source_dirs(module):
    """The module's main source roots: declared srcDirs first, then <dir>/src or <dir>/src/main.

    The project dir may not match the module name (':car-uxr-flutter' is car-uxr-flutter/android),
    and ':car-messenger-common:model' compiles ../src. Test source sets are dropped.
    """
    base = MODULE_DIRS.get(module)
    if base is None:
        return []
    roots = []
    try:
        build = open(os.path.join(base, "build.gradle"), encoding="utf-8").read()
    except OSError:
        build = ""
    for group in re.findall(r"java\.srcDirs\s*=\s*\[([^\]]*)\]", build):
        for raw in re.findall(r"'([^']+)'", group):
            if "test" in raw.lower():
                continue
            candidate = os.path.normpath(os.path.join(base, raw))
            if os.path.isdir(candidate):
                roots.append(candidate)
    for candidate in (os.path.join(base, "src", "main"), os.path.join(base, "src")):
        if os.path.isdir(candidate) and candidate not in roots:
            roots.append(candidate)
            break
    return roots


def newest_source(module):
    """(mtime, path) of the most recently modified published source file, or None.

    Over-approximates toward INDETERMINATE for a module that borrows its parent's directory. The
    build file is not walked: an unrelated build.gradle edit would leave the AAR stale forever, so
    the namespace is compared semantically instead (declared_namespace()).
    """
    newest = None
    for root in source_dirs(module):
        for dirpath, _dirs, files in os.walk(root):
            lowered = dirpath.replace(os.sep, "/").lower() + "/"
            if any(part in lowered for part in NOT_PUBLISHED):
                continue
            for name in files:
                if not name.endswith((".java", ".kt", ".xml")):
                    continue
                full = os.path.join(dirpath, name)
                try:
                    mtime = os.path.getmtime(full)
                except OSError:
                    continue
                if newest is None or mtime > newest[0]:
                    newest = (mtime, full)
    return newest


def aar_classes_jar(module):
    aar = os.path.join(GP, "build", module, "outputs", "aar", module + "-debug.aar")
    if not os.path.exists(aar):
        if module in OPTIONAL_MODULES:
            return None, OPTIONAL_ABSENT
        return None, "no AAR at " + os.path.relpath(aar, REPO) + " (run assembleDebug first)"

    # Staleness: this reads a built artifact, so an AAR older than its sources is INDETERMINATE,
    # not a pass. Mtimes over-approximate (a touched but unchanged source); `assembleDebug
    # --rerun-tasks` on the module clears it.
    newest = newest_source(module)
    if newest is not None and newest[0] > os.path.getmtime(aar):
        # Name --rerun-tasks: plain assembleDebug skips when only an mtime moved.
        return None, ("AAR is older than " + os.path.relpath(newest[1], REPO)
                      + " — it predates the published sources, so its surface is not this tree's."
                      + " Run: ./gradlew " + MODULE_PATHS.get(module, ":" + module)
                      + ":assembleDebug --rerun-tasks"
                      + "  (plain assembleDebug will NOT clear this if only the mtime moved:"
                      + " Gradle compares content, this compares mtimes.)")
    return aar, None


def surface_of(module):
    """The normalised public/protected surface of one module's AAR, or (None, reason)."""
    aar, err = aar_classes_jar(module)
    if err:
        return None, err
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(aar) as z:
            if "classes.jar" not in z.namelist():
                return None, "AAR has no classes.jar"
            z.extract("classes.jar", tmp)
        jar = os.path.join(tmp, "classes.jar")
        with zipfile.ZipFile(jar) as z:
            names = [n for n in z.namelist()
                     if n.endswith(".class") and not GENERATED.search(n)]
            z.extractall(tmp, members=names)
        classes = sorted(n[:-len(".class")].replace("/", ".") for n in names)
        if not classes:
            return None, "no classes in the AAR"
        # One javap call. -constants is required: constants are inlined into consumers, so a
        # changed value is a break that javap would otherwise not print.
        out = subprocess.run(
            ["javap", "-constants", "-protected", "-cp", jar] + classes,
            capture_output=True, text=True, cwd=tmp)
        if out.returncode != 0:
            return None, "javap failed: " + out.stderr.strip()[:200]

    annotations = annotations_of(aar)

    package = manifest_package_of(aar)
    declared = declared_namespace(module)
    if declared and package and declared != package:
        # Stale, not changed: recording it would bake the old package into the baseline.
        return None, (f"declared namespace {declared!r} but the AAR's manifest says {package!r} "
                      "-- the AAR predates the namespace change. Run assembleDebug.")
    # First, and on its own line, because it is the artifact's identity rather than a member of it.
    lines = ["# AndroidManifest package", package or "(none declared)", ""]
    current = None
    for raw in out.stdout.splitlines():
        line = raw.rstrip()
        if not line or line.startswith("Compiled from"):
            continue
        if line.endswith("{"):
            # Flush with members sorted: recompiling can permute member order.
            _flush(lines, current)
            # Only types a consumer can reference; package-private and anonymous classes churn.
            current = [line] if _is_api(line) else None
        elif line == "}":
            continue
        elif current is not None:
            current.append("    " + line.strip())
    _flush(lines, current)
    if annotations:
        # A second surface, kept separate. See annotations_of().
        lines.append("")
        lines.append("# annotations.zip")
        lines.extend(annotations)
    return "\n".join(lines) + "\n", None


def declared_namespace(module):
    """The `namespace` this module declares in its build file, or None.

    Compared with manifest_package_of(): a mismatch means the AAR predates a namespace change.
    """
    d = MODULE_DIRS.get(module)
    if not d:
        return None
    for name in ("build.gradle", "build.gradle.kts"):
        f = os.path.join(d, name)
        if not os.path.isfile(f):
            continue
        m = re.search(r"""^\s*namespace\s*=?\s*['"]([^'"]+)['"]""",
                      open(f, encoding="utf-8", errors="replace").read(), re.M)
        if m:
            return m.group(1)
    return None


def manifest_package_of(aar):
    """The ``package=`` attribute of the AAR's own AndroidManifest.xml.

    Consumers generate this library's R class in that package (classes.jar has no R), so a
    namespace change moves every consumer's R.id.* while javap reports no change.
    """
    with zipfile.ZipFile(aar) as z:
        if "AndroidManifest.xml" not in z.namelist():
            return None
        return ET.fromstring(z.read("AndroidManifest.xml")).get("package")


def annotations_of(aar):
    """The external-annotation surface an AAR ships alongside its classes.

    An @IntDef's value set is contract and lives in annotations.zip, which javap cannot see. Read
    from the artifact because metalava is source-based.
    """
    out = []
    with zipfile.ZipFile(aar) as z:
        if "annotations.zip" not in z.namelist():
            return out
        raw = z.read("annotations.zip")
    with zipfile.ZipFile(io.BytesIO(raw)) as az:
        for entry in sorted(az.namelist()):
            if not entry.endswith(".xml"):
                continue
            try:
                root = ET.fromstring(az.read(entry))
            except ET.ParseError as exc:
                out.append("    UNPARSEABLE %s: %s" % (entry, exc))
                continue
            for item in root.findall("item"):
                name = (item.get("name") or "").strip()
                for ann in item.findall("annotation"):
                    vals = sorted(
                        "%s=%s" % (v.get("name"), v.get("val")) for v in ann.findall("val"))
                    out.append("    %s  %s%s" % (
                        name, ann.get("name"), (" " + " ".join(vals)) if vals else ""))
    # Sorted: neither zip entry order nor XML item order is a contract.
    return sorted(set(out))


def _is_api(header):
    """True for a type a consumer outside this library can name."""
    return header.startswith("public ") or header.startswith("protected ")


def _flush(lines, current):
    if current:
        lines.append(current[0])
        lines.extend(sorted(current[1:]))


def baseline_path(module):
    return os.path.join(BASELINE_DIR, module + ".txt")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--record", action="store_true",
                    help="rewrite the baselines: accept the current surface as intended")
    args = ap.parse_args()

    os.makedirs(BASELINE_DIR, exist_ok=True)
    rc = 0
    modules, undetermined = discover_modules()
    if not modules:
        print("INDETERMINATE: discovered no library modules; settings.gradle unreadable?")
        return 2
    for short, reason in undetermined:
        # A module we could not classify is not a module we checked.
        print("UNCLASSIFIED: %-22s %s" % (short, reason))
    skipped = [(m, r) for m, r in undetermined if m not in EXCLUDED]
    for short, reason in EXCLUDED.items():
        print("EXCLUDED: %-22s %s" % (short, reason))
    for module in modules:
        text, err = surface_of(module)
        if err:
            skipped.append((module, err))
            continue
        path = baseline_path(module)
        if args.record:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            print("recorded %-22s %d line(s)" % (module, text.count("\n")))
            continue
        if not os.path.exists(path):
            print("FAIL: %s has no baseline. Run --record to accept the current surface." % module)
            rc = 1
            continue
        with open(path, encoding="utf-8") as f:
            expected = f.read()
        if expected == text:
            print("OK:   %-22s surface unchanged (%d line(s))" % (module, text.count("\n")))
        else:
            rc = 1
            exp, got = expected.splitlines(), text.splitlines()
            removed = [l for l in exp if l not in got]
            added = [l for l in got if l not in exp]
            print("FAIL: %s public surface changed." % module)
            for l in removed[:20]:
                print("  - " + l.strip())
            for l in added[:20]:
                print("  + " + l.strip())
            if len(removed) > 20 or len(added) > 20:
                print("  ... %d removed, %d added in total" % (len(removed), len(added)))

    absent = [(m, e) for m, e in skipped if e == OPTIONAL_ABSENT]
    skipped = [(m, e) for m, e in skipped if e != OPTIONAL_ABSENT]
    for module, _ in absent:
        print("OFF:  %-22s %s" % (module, OPTIONAL_ABSENT))
    for module, err in skipped:
        # Not a pass: an unreadable module's surface is unchecked.
        print("SKIP: %-22s %s" % (module, err))
    if skipped:
        print("\nINDETERMINATE: %d module(s) could not be read; this run did not check them."
              % len(skipped))
        # rc=2 even under --record: never record from an unreadable or stale artifact.
        return 2

    if rc == 0 and not args.record:
        print("\nOK: every published surface matches its baseline.")
    elif rc and not args.record:
        print("\nAn API change is not an error. An UNRECORDED one is: review the diff above, and if"
              "\nthe change is intended run scripts/check-api-surface.py --record and commit the"
              "\nbaseline alongside the code that changed it.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
