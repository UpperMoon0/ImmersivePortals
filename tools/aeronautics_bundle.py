"""Pinned official Aeronautics runtime for the opt-in ship verification lane.

The downloaded bundle is cached locally, never included in evidence artifacts.
Only the exact owned staging filenames are created or removed.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request


BUNDLE = {
    "filename": "create-aeronautics-bundled-1.21.1-1.3.1.jar",
    "version": "1.3.1",
    "url": "https://cdn.modrinth.com/data/oWaK0Q19/versions/Vzp221Un/create-aeronautics-bundled-1.21.1-1.3.1.jar",
    "size": 33121810,
    "sha256": "b531dbe1081ee354f6e47ba4e0443b9caa648dfb8c82013a591a20836245bd84",
    "mods": {"aeronautics": "1.3.1", "simulated": "1.3.1", "offroad": "1.3.1"},
}


def require_clean_runs(run_dirs: tuple[Path, Path]) -> None:
    """A killed addon run must not contaminate later baseline acceptance."""
    for run_dir in run_dirs:
        owned = run_dir / "mods" / ("__ip_verify__" + BUNDLE["filename"])
        if owned.exists() or owned.is_symlink():
            raise RuntimeError(f"stale Aeronautics verification mod must be removed before a new run: {owned}")


def fetch(directory: Path) -> Path:
    """Return only exact pinned bytes, whether downloaded now or already cached."""
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / BUNDLE["filename"]
    if destination.is_symlink():
        raise RuntimeError("refusing redirected Aeronautics download target")
    if destination.exists():
        if not destination.is_file():
            raise RuntimeError("Aeronautics cache target is not a file")
        if (destination.stat().st_size == BUNDLE["size"]
                and hashlib.sha256(destination.read_bytes()).hexdigest() == BUNDLE["sha256"]):
            return destination
    with urllib.request.urlopen(BUNDLE["url"], timeout=120) as response:
        data = response.read(BUNDLE["size"] + 1)
    if len(data) != BUNDLE["size"]:
        raise RuntimeError("official Aeronautics bundle size mismatch")
    if hashlib.sha256(data).hexdigest() != BUNDLE["sha256"]:
        raise RuntimeError("official Aeronautics bundle SHA-256 mismatch")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, prefix="aeronautics-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
        temporary.replace(destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return destination


@contextmanager
def stage(root: Path, result_dir: Path, run_dirs: tuple[Path, Path]):
    """Stage both sides atomically enough to clean up every partial/failed run."""
    root = root.resolve()
    cache = root / "build/verification-downloads"
    name = "__ip_verify__" + BUNDLE["filename"]
    targets = [run_dir / "mods" / name for run_dir in run_dirs]
    if len(set(targets)) != 2:
        raise RuntimeError("Aeronautics requires distinct server and client staging targets")
    for path in (cache, result_dir, *targets):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise RuntimeError(f"unsafe Aeronautics staging path: {path}")
    if any(target.exists() for target in targets):
        raise RuntimeError("refusing to overwrite an existing Aeronautics verification mod")
    source = fetch(cache)
    created = []
    try:
        for target in targets:
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as output:
                created.append(target)
                with source.open("rb") as input_file:
                    shutil.copyfileobj(input_file, output)
            if (target.stat().st_size != BUNDLE["size"]
                    or hashlib.sha256(target.read_bytes()).hexdigest() != BUNDLE["sha256"]):
                raise RuntimeError("staged Aeronautics bundle differs from pinned bytes")
        evidence = dict(schema_version=1, **BUNDLE,
                        staged=[str(target.relative_to(root)) for target in targets])
        (result_dir / "aeronautics-provenance.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        yield evidence
    finally:
        for target in created:
            target.unlink(missing_ok=True)
