"""Owned real shadow-map/receiver oracle. No callback count alone can satisfy this check."""
import json
import math
from pathlib import Path


def validate_receiver_depth(check: dict) -> None:
    frame = check.get("render_frame")
    depth = check.get("receiver_depth", {})
    if (type(frame) is not int or frame <= 0 or depth.get("frame") != frame
            or depth.get("observation") != f"{check.get('phase')}:{check.get('scene')}"
            or depth.get("sampleCount") != 81 or len(depth.get("depthSamples", [])) != 81
            or depth.get("observationCount", 0) <= 0):
        raise RuntimeError("missing current scene/frame shadow receiver depth")


def validate_probe(check: dict, result_dir: Path, *, expect_caster: bool) -> None:
    validate_receiver_depth(check)
    shadow = check.get("shadow", {})
    observation = f"{check.get('phase')}:{check.get('scene')}"
    if shadow.get("observation") != observation or shadow.get("observations", 0) < 1:
        raise RuntimeError("missing fresh real shadow-pass observation")
    draw_states = shadow.get("terrain_draw_states", {})
    if shadow.get("terrain_region_setups", 0) < 1 or not draw_states or any(
        state.get("clipDistanceEnabled") is not False or state.get("probeOutput") != 1 for state in draw_states.values()
    ):
        raise RuntimeError("actual shadow terrain draws did not observe suspended clipping")
    samples = shadow.get("samples", [])
    if shadow.get("resolution") != 256 or shadow.get("sample_count") != 25 or len(samples) != 25:
        raise RuntimeError("missing actual shadow depth pixels")
    if any(not isinstance(x, (int, float)) or not math.isfinite(x) or not 0 <= x <= 1 for x in samples):
        raise RuntimeError("invalid shadow depth pixels")
    expected = 0.5 - 2 / 8 if expect_caster else 0.5 + 3 / 8
    if any(abs(value - expected) >= 0.004 for value in (min(samples), sorted(samples)[12], max(samples))):
        raise RuntimeError("shadow map did not contain the expected caster/receiver geometry")
    if shadow.get("inherited_clipping_restored") is not True:
        raise RuntimeError("inherited portal clip state was not restored after the actual shadow pass")
    for key in ("center_green", "center_blue", "center_red", "side_green"):
        value = check.get(key, -1)
        if not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
            raise RuntimeError("invalid shadow receiver pixels")
    distance = check.get("receiver_distance", float("nan"))
    if not math.isfinite(distance) or abs(distance - 7) >= 0.5:
        raise RuntimeError("missing retained receiver depth")
    primary, absent = ("center_blue", "center_green") if expect_caster else ("center_green", "center_blue")
    if check[primary] <= 0.80 or check[absent] >= 0.02 or check["center_red"] >= 0.01 or check["side_green"] <= 0.80:
        raise RuntimeError("shadow receiver did not show the expected lit/shadowed pixels")
    if check.get("accepted") is not True or not (result_dir / check.get("screenshot", "missing")).is_file():
        raise RuntimeError("missing accepted shadow screenshot")


def validate_shadow_evidence(result_dir: Path, renderer: str) -> None:
    report = json.loads((result_dir / "shadow-evidence.json").read_text())
    if report.get("renderer") != renderer or report.get("pack") != "ip-shadow-fixture-v1":
        raise RuntimeError("wrong shadow renderer or pack")
    checks = report.get("checks", [])
    required = {(phase, scene) for phase in ("before-reload", "after-reload") for scene in ("lit", "caster", "restored")}
    if {(check.get("phase"), check.get("scene")) for check in checks} != required or len(checks) != 6:
        raise RuntimeError("missing fresh shadow controls before/after reload")
    for check in checks:
        validate_probe(check, result_dir, expect_caster=check["scene"] == "caster")
    sizes = {(check.get("width"), check.get("height")) for check in checks}
    if len(sizes) < 2:
        raise RuntimeError("shadow acceptance did not resize/reload its targets")


def validate_shadow_negative(result_dir: Path) -> None:
    report = json.loads((result_dir / "shadow-evidence.json").read_text())
    lit = [check for check in report.get("checks", []) if check.get("phase") == "before-reload" and check.get("scene") == "lit"]
    if len(lit) != 1:
        raise RuntimeError("shadow negative never passed its real lit control")
    validate_probe(lit[0], result_dir, expect_caster=False)
    failed = report.get("last_probe", {})
    validate_receiver_depth(failed)
    state = failed.get("shadow", {})
    if failed.get("phase") != "before-reload" or failed.get("scene") != "caster" or failed.get("accepted") is not False:
        raise RuntimeError("shadow negative did not fail its exact caster scene")
    draw_states = state.get("terrain_draw_states", {})
    if state.get("terrain_region_setups", 0) < 1 or not draw_states or any(
        value.get("clipDistanceEnabled") is not True or value.get("probeOutput") != -1 for value in draw_states.values()
    ):
        raise RuntimeError("shadow negative did not re-enable clipping at actual terrain draws")
    samples = state.get("samples", [])
    if state.get("observation") != "before-reload:caster" or state.get("observations", 0) < 1 or state.get("negative_control") is not True:
        raise RuntimeError("shadow negative did not execute inside the actual shadow pass")
    if len(samples) != 25 or any(not isinstance(x, (int, float)) or not math.isfinite(x) or x < 0.999 for x in samples):
        raise RuntimeError("shadow negative did not actually remove caster depth pixels")
    if failed.get("center_green", 0) <= 0.80 or failed.get("center_blue", 1) >= 0.02 or abs(failed.get("receiver_distance", -1) - 7) >= 0.5:
        raise RuntimeError("shadow negative lost its receiver rather than only the shadow")
