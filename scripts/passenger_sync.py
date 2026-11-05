# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Line the passenger stream up with the driver's from the content both displays show.

The two displays are recorded by separate screenrecord processes. The host knows only when each
`adb shell screenrecord` was launched, not when its first frame (PTS 0) was taken, so the
host-side start is off by the difference in the two start-up latencies (take 5: the passenger
was placed 172 ms early, a constant shift with no drift). When the scenario plays the same clip
on both displays side by side (Big Buck Bunny, parked), its scene cuts give the exact shift:
both streams are decoded with their real (variable-rate) frame times, reduced to tiny grayscale
frames, and the cuts (isolated spikes of frame-to-frame change) are matched, backed by a
cross-correlation of the luma steps. The correction is applied only when the two agree; the
evidence goes into the metadata either way.
"""

from __future__ import annotations

import re
import subprocess
import sys

W, H, MAXLAG, STEP = 48, 27, 2.0, 0.040
SETTLE_S = 2.4        # from the driver's tap until its player shows moving picture
MIN_WINDOW_S = 4.0    # shorter windows of this clip correlate on noise
MAX_WINDOW_S = 12.0
MIN_CONFIDENCE = 0.8
MAX_CORRECTION_MS = 1000.0
SHARED_TAP = "Big Buck Bunny"


def _numpy():
    try:
        import numpy as np
    except ImportError:  # python3 -I hides the user site, where numpy may live
        import site
        sys.path.append(site.getusersitepackages())
        import numpy as np
    return np


def shared_window(scenario: dict, driver_seconds: float) -> tuple[float, float] | None:
    """Driver-raw seconds where both displays show the shared clip, or None: from the driver's
    tap on it (the passenger already plays it) to the end of the driver recording."""
    actions = scenario.get("actions", [])
    if not any(a.get("type") == "passenger" and a.get("media") == "video" for a in actions):
        return None
    taps = [int(a["atMs"]) / 1000 for a in actions
            if a.get("type") == "tapText" and a.get("text") == SHARED_TAP]
    if not taps:
        return None
    t0 = taps[-1] + SETTLE_S
    t1 = min(t0 + MAX_WINDOW_S, driver_seconds - 0.1)
    return (t0, t1) if t1 - t0 >= MIN_WINDOW_S else None


def _frames(np, path, t0, t1):
    vf = f"scale={W}:{H}:flags=area,format=gray,showinfo"
    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-copyts", "-ss", f"{max(t0, 0):.3f}",
           "-to", f"{t1:.3f}", "-i", str(path), "-an", "-vf", vf, "-fps_mode", "passthrough",
           "-f", "rawvideo", "pipe:1"]
    p = subprocess.run(cmd, capture_output=True, check=True)
    ts = np.array([float(x) for x in re.findall(rb"pts_time:\s*([-\d.e]+)", p.stderr)])
    img = np.frombuffer(p.stdout, np.uint8).reshape(-1, H, W).astype(np.float32)
    n = min(len(ts), len(img))
    return ts[:n], img[:n]


def _cuts(np, ts, motion, rel=2.0, floor=4.0):
    return np.array([ts[i] for i in range(2, len(motion) - 2)
                     if motion[i] >= floor and motion[i] >= rel * max(
                         motion[i - 2], motion[i - 1], motion[i + 1], motion[i + 2])])


def _hold(np, ts, v, grid):
    return v[np.clip(np.searchsorted(ts, grid, side="right") - 1, 0, len(v) - 1)]


def measure(driver_raw, passenger_raw, passenger_start: float,
            window: tuple[float, float]) -> dict:
    """How far the passenger content lags the driver's on the composed timeline (lag_ms > 0:
    later), by matched cuts (median) and by luma-step cross-correlation."""
    np = _numpy()
    t0, t1 = window
    td, imd = _frames(np, driver_raw, t0 - 0.5, t1 + 0.5)
    tp, imp = _frames(np, passenger_raw, t0 - passenger_start - MAXLAG - 0.5,
                      t1 - passenger_start + MAXLAG + 0.5)
    tp = tp + passenger_start
    luma_d, luma_p = imd.mean(axis=(1, 2)), imp.mean(axis=(1, 2))
    move_d = np.r_[0.0, np.abs(np.diff(imd, axis=0)).mean(axis=(1, 2))]
    move_p = np.r_[0.0, np.abs(np.diff(imp, axis=0)).mean(axis=(1, 2))]
    dt, k = 0.001, int(round(STEP / 0.001))
    grid = np.arange(t0, t1, dt)
    wide = np.arange(t0 - MAXLAG, t1 + MAXLAG, dt)
    sd = _hold(np, td, luma_d, grid)
    sd = sd[k:] - sd[:-k]
    sp = _hold(np, tp, luma_p, wide)
    sp = sp[k:] - sp[:-k]
    x = (sd - sd.mean()) / (sd.std() + 1e-9)
    best = int(np.argmax(np.correlate(sp - sp.mean(), x, mode="valid")))
    r = float(np.corrcoef(x, sp[best:best + len(x)])[0, 1])
    lag_x = -MAXLAG + best * dt
    cd = _cuts(np, td, move_d)
    cd = cd[(cd >= t0) & (cd <= t1)]
    cp = _cuts(np, tp, move_p)
    pairs = []
    for c in cd:
        if len(cp):
            j = int(np.argmin(np.abs(cp - (c + lag_x))))
            if abs(cp[j] - (c + lag_x)) <= 0.15:
                pairs.append((float(c), float(cp[j] - c)))
    diffs = np.array([d for _, d in pairs])
    lag_ms = 1000 * float(np.median(diffs)) if len(diffs) >= 3 else 1000 * lag_x
    agree = float(np.mean(np.abs(1000 * diffs - lag_ms) <= 40)) if len(diffs) >= 3 else 0.5
    return {"windowSeconds": [round(t0, 3), round(t1, 3)],
            "xcorrLagMs": round(1000 * lag_x, 1), "xcorrR": round(r, 3),
            "cutLagsMs": [[round(c, 3), round(1000 * d, 1)] for c, d in pairs],
            "lagMs": round(lag_ms, 1), "confidence": round(max(0.0, r) * agree, 3)}


def align(scenario: dict, driver_raw, passenger_raw, host_start: float,
          driver_seconds: float) -> tuple[float, dict]:
    """The passenger stream's start on the driver timeline, corrected from the shared clip when
    the measurement is confident, and the evidence. Never raises: a failed or unconfident
    measurement keeps the host-side start and says why."""
    evidence = {"method": "shared-clip cut matching + luma cross-correlation",
                "hostStartSeconds": host_start, "correctionMs": 0.0, "applied": False}
    window = shared_window(scenario, driver_seconds)
    if window is None:
        evidence["reason"] = "no shared clip window in this scenario"
        return host_start, evidence
    try:
        result = measure(driver_raw, passenger_raw, host_start, window)
    except Exception as error:  # noqa: BLE001 — evidence, never a failed take
        evidence["reason"] = f"measurement failed: {type(error).__name__}: {error}"[:300]
        return host_start, evidence
    evidence.update(result)
    correction = -result["lagMs"]
    if result["confidence"] < MIN_CONFIDENCE or abs(correction) > MAX_CORRECTION_MS:
        evidence["reason"] = "not confident; host-side start kept"
        return host_start, evidence
    evidence.update(correctionMs=round(correction, 1), applied=True)
    return host_start + correction / 1000, evidence


if __name__ == "__main__":
    import json
    import pathlib
    if len(sys.argv) != 5:
        sys.exit("usage: passenger_sync.py SCENARIO_ID DRIVER_RAW PASSENGER_RAW HOST_START_S")
    root = pathlib.Path(__file__).resolve().parents[1]
    doc = json.loads((root / "a2ui-automotive/demo/nyc-scenarios-v1.json").read_text())
    found = [s for s in doc["scenarios"] if s.get("id") == sys.argv[1]]
    seconds = float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
         sys.argv[2]], capture_output=True, text=True, check=True).stdout)
    start, info = align(found[0], sys.argv[2], sys.argv[3], float(sys.argv[4]), seconds)
    print(json.dumps({"startSeconds": round(start, 4), **info}, indent=1))
