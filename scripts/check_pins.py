#!/usr/bin/env python3
"""Every pin this project's supply chain rests on, checked before a build.

    python3 -I scripts/check_pins.py

- every action in .github/workflows/ and .github/actions/ is named by a
  full commit SHA with its version in a comment
  (`uses: owner/repo@<40 hex> # vX.Y.Z`); local actions (`./...`) are this
  repository's own;
- the Gradle build names exact versions: no `+`, no `latest.release`, no
  ranges, no SNAPSHOT, no repository besides Google's Maven, Maven Central
  and the Gradle plugin portal;
- the app has no library dependency (only `testImplementation` of JUnit):
  what is in the APK is this repository's code.

Exits 1 with one line per finding.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
USES = re.compile(r"^\s*(?:-\s*)?uses:\s*(\S+)(.*)$")
PINNED = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
VERSION = re.compile(r"#\s*v\d+(\.\d+)*\b")
DEPENDENCY = re.compile(r'^\s*(\w+)\(\s*"([^"]+)"\s*\)', re.M)
ALLOWED_DEPENDENCIES = {("testImplementation", "junit:junit:4.13.2")}
REPOSITORIES = {"google", "mavenCentral", "gradlePluginPortal"}


def workflows(problems):
    github = ROOT / ".github"
    files = sorted(github.glob("workflows/*.y*ml")) + sorted(github.glob("actions/*/action.y*ml"))
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            found = USES.match(line)
            if not found:
                continue
            target, rest = found.group(1), found.group(2)
            where = f"{path.relative_to(ROOT)}:{number}"
            if target.startswith("./"):
                continue
            if not PINNED.match(target):
                problems.append(f"{where}: {target} is not pinned by a commit SHA")
            elif not VERSION.search(rest):
                problems.append(f"{where}: {target} has no version comment (# vX.Y.Z)")


def gradle(problems):
    for path in sorted((ROOT / "android").rglob("*.gradle.kts")):
        text = path.read_text(encoding="utf-8")
        where = path.relative_to(ROOT)
        for bad in ("+\"", "latest.", "SNAPSHOT", "[", "mavenLocal", "jcenter", "maven(", "maven {"):
            if bad in text:
                problems.append(f"{where}: {bad!r}: versions must be exact, repositories the known three")
        for v in re.findall(r'version\s+"([^"]+)"', text):
            if not re.fullmatch(r"\d+\.\d+\.\d+", v):
                problems.append(f"{where}: plugin version {v} is not exact")
        block = re.search(r"^dependencies\s*\{(.*?)^\}", text, re.M | re.S)
        if block:
            for conf, coord in DEPENDENCY.findall(block.group(1)):
                if (conf, coord) not in ALLOWED_DEPENDENCIES:
                    problems.append(f"{where}: dependency {conf}({coord}) is not on the list in scripts/check_pins.py")
        for repo in re.findall(r"^\s*(\w+)\s*(?:\{|\(\))", text, re.M):
            if repo.endswith(("Central", "Portal")) or repo == "google":
                if repo not in REPOSITORIES:
                    problems.append(f"{where}: repository {repo}")


def main():
    problems = []
    workflows(problems)
    gradle(problems)
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print("pins: the actions and the Gradle build are pinned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
