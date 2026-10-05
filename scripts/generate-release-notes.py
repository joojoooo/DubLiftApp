#!/usr/bin/env python3
"""Combine installation guidance and app commit titles for a tagged release."""

import argparse
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"v([0-9]+)\.([0-9]+)\.([0-9]+)")


def version(tag):
    match = VERSION.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def generate(tag, published_tags):
    current = version(tag)
    if current is None:
        raise ValueError("Release tags must have the form vMAJOR.MINOR.PATCH.")
    previous = max(
        (candidate for candidate in published_tags
         if version(candidate) is not None and version(candidate) < current),
        key=version, default=None,
    )
    revision = f"refs/tags/{previous}..refs/tags/{tag}" if previous else f"refs/tags/{tag}"
    titles = subprocess.check_output(
        ["git", "log", "--format=%s", revision, "--"], cwd=ROOT, text=True,
    ).splitlines()
    notes = (ROOT / ".github/release-notes.md").read_text().rstrip()
    notes += "\n\n## Changelog\n\n"
    notes += "\n".join(f"- {title}" for title in titles) if titles else "No new commits since the previous release."
    if previous:
        notes += ("\n\n## Full changelog\n\n"
                  f"https://github.com/joojoooo/DubLiftApp/compare/{previous}...{tag}")
    return notes + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--release-tags", required=True, type=Path,
                        help="File listing published, non-prerelease tags, one per line.")
    args = parser.parse_args()
    print(generate(args.tag, args.release_tags.read_text().splitlines()), end="")


if __name__ == "__main__":
    main()
