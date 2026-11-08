#!/usr/bin/env python3
"""Fail-closed evidence validator. This is NOT a game driver or a certification.

The VS/Clockwork acceptance driver must produce receipts from real dedicated
Minecraft server and graphical client instances, with independent identity and
ownership witnesses. This validator never generates or fabricates evidence.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

REQUIRED_CASES = {
    ("control", "ordinary"),
    ("control", "rotated"),
    ("control", "dimension-stack"),
    ("control", "broadside"),
    ("large", "ordinary"),
    ("large", "rotated"),
    ("large", "dimension-stack"),
    ("large", "broadside"),
}
REQUIRED_CLOCKWORK = {
    "propeller", "balloon", "gas", "bearing", "create-kinetics",
}
REQUIRED_FLAGS = (
    "native_physics_ticks", "native_handoff", "source_owner_retired",
    "destination_owner_unique", "same_ship_id", "blocks_identical",
    "cargo_identical", "block_entities_identical", "passengers_preserved",
    "angular_velocity_preserved", "linear_velocity_preserved",
    "client_chunks_complete", "client_mesh_complete",
    "client_post_handoff_frames", "restart_persistence", "clean_shutdown",
)


def verify_receipts(server: dict, client: dict) -> list[str]:
    errors: list[str] = []
    if server.get("schema") != 1 or client.get("schema") != 1:
        errors.append("Unsupported or absent evidence schema")
    if server.get("run_id") != client.get("run_id") or not server.get("run_id"):
        errors.append("Server/client run identity mismatch")
    if server.get("commit") != client.get("commit") or not server.get("commit"):
        errors.append("Server/client commit identity mismatch")
    if not isinstance(server.get("dependencies"), dict) or server.get("dependencies") != client.get("dependencies"):
        errors.append("Dependency hashes or versions differ")
    else:
        for mod in ("immersive_portals", "valkyrienskies", "clockwork", "create"):
            item = server["dependencies"].get(mod)
            if not isinstance(item, dict) or not item.get("version") or not isinstance(item.get("sha256"), str) or len(item["sha256"]) != 64:
                errors.append(f"Missing pinned version/SHA-256 for {mod}")
    observed: set[tuple[str, str]] = set()
    for i, case in enumerate(server.get("cases", [])):
        if not isinstance(case, dict):
            errors.append(f"Invalid case #{i}")
            continue
        key = (case.get("size"), case.get("route"))
        if key in observed:
            errors.append(f"Duplicate case {key}")
        observed.add(key)
        client_case = next((c for c in client.get("cases", []) if isinstance(c, dict) and
                            (c.get("size"), c.get("route")) == key), None)
        if not client_case:
            errors.append(f"Missing client case {key}")
            continue
        if case.get("body_id") != client_case.get("body_id") or not case.get("body_id"):
            errors.append(f"Body identity mismatch for {key}")
        for field in REQUIRED_FLAGS:
            receipt = client_case if field.startswith("client_") else case
            value = receipt.get(field)
            if field == "native_physics_ticks":
                if type(value) is not int or value < 10:
                    errors.append(f"{key}: fewer than 10 native physics ticks")
            elif field == "client_post_handoff_frames":
                if type(value) is not int or value < 30:
                    errors.append(f"{key}: fewer than 30 rendered post-handoff frames")
            elif value is not True:
                errors.append(f"{key}: missing affirmative {field} witness")
        if case.get("size") == "large":
            if type(case.get("blocks")) is not int or case["blocks"] < 3500:
                errors.append(f"{key}: large fixture is too small")
            if type(case.get("plot_chunks")) is not int or case["plot_chunks"] < 12:
                errors.append(f"{key}: large fixture spans too few chunks")
        if key[1] == "rotated" and case.get("yaw_degrees") != 90:
            errors.append(f"{key}: rotation case is not a 90-degree portal")
        if case.get("crossings", 0) < 2:
            errors.append(f"{key}: no return crossing")
    if not REQUIRED_CASES.issubset(observed):
        errors.append(f"Missing scenario set: {sorted(REQUIRED_CASES - observed)}")
    if len(observed) != len(REQUIRED_CASES):
        errors.append("Unexpected or excess scenario set")
    if set(server.get("clockwork_mechanics", [])) != REQUIRED_CLOCKWORK:
        errors.append("Incomplete Clockwork functional-mechanics set")
    if client.get("graphical_client") is not True:
        errors.append("No real graphical Minecraft client receipt")
    if server.get("dedicated_server") is not True:
        errors.append("No real dedicated server receipt")
    if client.get("screenshots_sha256") is None or not isinstance(client["screenshots_sha256"], dict):
        errors.append("No graphical screenshot digest manifest")
    elif not client["screenshots_sha256"] or any(
        not isinstance(v, str) or len(v) != 64
        for v in client["screenshots_sha256"].values()
    ):
        errors.append("Missing screenshot digest(s)")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("client", type=Path)
    args = parser.parse_args()
    if not args.server.is_file() or not args.client.is_file():
        parser.error("Missing genuine server and/or graphical-client receipts")
    errors = verify_receipts(json.loads(args.server.read_text()), json.loads(args.client.read_text()))
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    print("Evidence contract satisfied; this does not independently attest its provenance.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
