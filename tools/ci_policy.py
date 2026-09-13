"""Decide whether a PR needs the graphical gate; unknown files fail closed."""
from pathlib import PurePosixPath
import argparse
import subprocess


def needs_e2e(paths: list[str]) -> bool:
    def documentation(path: str) -> bool:
        p = PurePosixPath(path)
        return (p.suffix.lower() == ".md" or path in {"LICENSE", "NOTICE"}) and not path.startswith(".github/")
    return not paths or any(not documentation(path) for path in paths)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("head")
    args = parser.parse_args()
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "-z", f"{args.base}...{args.head}"], text=True
    ).split("\0")
    print("true" if needs_e2e([p for p in changed if p]) else "false")
