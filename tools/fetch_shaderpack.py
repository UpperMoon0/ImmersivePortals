#!/usr/bin/env python3
"""Fetch one pinned official acceptance pack; never republish pack bytes as CI artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def fetch(name: str, directory: Path) -> tuple[Path, str]:
    manifest = json.loads((ROOT / "tools/shaderpacks/real-packs.json").read_text())
    if name not in manifest:
        raise ValueError(f"unknown pinned shaderpack: {name}")
    pack = manifest[name]
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / pack["filename"]
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == pack["sha256"]:
        return destination, pack["profile"]
    with urllib.request.urlopen(pack["url"], timeout=120) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024:
        raise ValueError("shaderpack exceeds download size limit")
    if hashlib.sha256(data).hexdigest() != pack["sha256"]:
        raise ValueError(f"SHA-256 mismatch for pinned {name} shaderpack")
    destination.write_bytes(data)
    return destination, pack["profile"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", choices=("makeup", "complementary"))
    parser.add_argument("--destination", type=Path, default=ROOT / "build/verification-shaderpacks")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    path, profile = fetch(args.name, args.destination)
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"file={path.resolve()}\nprofile={profile}\n")
    print(f"Verified {args.name}: {path.name}, profile={profile}")
