"""Reuse only complete main CI evidence for the exact release commit and jar.

An unavailable or ineligible run falls back to fresh release verification. Once
selected, a missing/corrupt download fails publication instead of accepting it.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ARTIFACT = "verified-main-release-bundle"
MANIFEST = "ci-release-evidence.json"


def git_commit() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def mod_version() -> str:
    match = re.search(r"^mod_version\s*=\s*([0-9A-Za-z][0-9A-Za-z._+-]*)\s*$",
                      (ROOT / "gradle.properties").read_text(), re.MULTILINE)
    if not match:
        raise ValueError("Invalid or missing mod_version")
    return match[1]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_manifest(directory: Path, commit: str, run_id: int, attempt: int, version: str) -> dict:
    jar = directory / f"immersive_portals-{version}.jar"
    manifest = dict(schema=1, commit=commit, run_id=run_id, run_attempt=attempt,
                    version=version, jar=jar.name, sha256=sha256(jar))
    (directory / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def validate_bundle(directory: Path, commit: str, run_id: int, attempt: int, version: str) -> Path:
    manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    expected = dict(schema=1, commit=commit, run_id=run_id, run_attempt=attempt,
                    version=version, jar=f"immersive_portals-{version}.jar")
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("Release bundle provenance does not match the verified run/tag")
    jar = directory / expected["jar"]
    if not re.fullmatch(r"[a-f0-9]{64}", manifest.get("sha256", "")) or sha256(jar) != manifest["sha256"]:
        raise ValueError("Verified release jar checksum mismatch")
    return jar


def required_jobs() -> list[str]:
    return json.loads((ROOT / "tools/ci-coverage.json").read_text())


def validate_run(run: dict, jobs: list[dict], artifacts: list[dict], repository: str, commit: str) -> dict:
    expected = dict(status="completed", conclusion="success", event="push", head_branch="main",
                    head_sha=commit, path=".github/workflows/ci.yml")
    if any(run.get(key) != value for key, value in expected.items()) or run.get("repository", {}).get("full_name") != repository:
        raise ValueError("Only successful main push CI for the exact tagged commit may be reused")
    if run.get("head_repository", {}).get("full_name") != repository:
        raise ValueError("CI head repository does not match the release repository")
    attempt = run["run_attempt"]
    if Counter(job["name"] for job in jobs) != Counter(required_jobs()):
        raise ValueError("CI coverage is missing, duplicated, or differs from the required matrix")
    if any(job.get("status") != "completed" or job.get("conclusion") != "success"
           or job.get("run_attempt") != attempt or job.get("head_sha") != commit for job in jobs):
        raise ValueError("Every required check must pass on the same commit and run attempt")
    matches = [artifact for artifact in artifacts if artifact.get("name") == ARTIFACT]
    if len(matches) != 1 or matches[0].get("expired") is not False:
        raise ValueError("Verified main release bundle is unavailable or expired")
    origin = matches[0].get("workflow_run", {})
    if origin.get("id") != run["id"] or origin.get("head_sha") != commit:
        raise ValueError("Artifact origin does not match the verified CI run")
    return matches[0]


def api(path: str) -> dict:
    return json.loads(subprocess.check_output(["gh", "api", path], text=True, timeout=30))


def pages(path: str, key: str) -> list[dict]:
    records = []
    for page in range(1, 101):
        batch = api(f"{path}?per_page=100&page={page}")[key]
        records.extend(batch)
        if len(batch) < 100:
            return records
    raise ValueError("Too many API pages to validate bounded release evidence")


def resolve(repository: str, commit: str, run_id: str) -> dict:
    fallback = {"reuse": "false"}
    if not run_id:
        print("[release] No source CI run supplied; running full release verification", flush=True)
        return fallback
    try:
        if not re.fullmatch(r"[1-9][0-9]*", run_id):
            raise ValueError("Invalid source CI run ID")
        base = f"repos/{repository}/actions/runs/{run_id}"
        run = api(base)
        jobs = pages(f"{base}/attempts/{run['run_attempt']}/jobs", "jobs")
        artifacts = pages(f"{base}/artifacts", "artifacts")
        artifact = validate_run(run, jobs, artifacts, repository, commit)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        print(f"[release] CI evidence ineligible: {exc}; running full release verification", flush=True)
        return fallback
    print(f"[release] Reusing all {len(jobs)} passing checks and preserved jar from CI {run_id}", flush=True)
    return dict(reuse="true", run_id=run_id, run_attempt=str(run["run_attempt"]), artifact_id=str(artifact["id"]))


def output(values: dict) -> None:
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    record = sub.add_parser("record")
    record.add_argument("--directory", type=Path, default=ROOT / "build/libs")
    select = sub.add_parser("resolve")
    select.add_argument("--run-id", default="")
    check = sub.add_parser("verify-bundle")
    check.add_argument("--directory", type=Path, default=ROOT / "build/libs")
    check.add_argument("--run-id", type=int, required=True)
    check.add_argument("--run-attempt", type=int, required=True)
    args = parser.parse_args()
    if args.mode == "resolve":
        output(resolve(os.environ["GITHUB_REPOSITORY"], git_commit(), args.run_id))
    elif args.mode == "record":
        manifest = write_manifest(args.directory, git_commit(), int(os.environ["GITHUB_RUN_ID"]),
                                  int(os.environ["GITHUB_RUN_ATTEMPT"]), mod_version())
        output({"jar_path": str(args.directory / manifest["jar"])})
    else:
        jar = validate_bundle(args.directory, git_commit(), args.run_id, args.run_attempt, mod_version())
        print(f"[release] Exact verified jar restored: {jar.name}; SHA-256 matches", flush=True)


if __name__ == "__main__":
    main()
