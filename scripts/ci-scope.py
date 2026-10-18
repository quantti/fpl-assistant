#!/usr/bin/env python3
"""Classify the checked-out change range; uncertainty always selects full checks."""

import os
import re
import subprocess
import sys

DOCS = {"README.md", "AGENTS.md", "CLAUDE.md"}


def classify(event, ref, base, head):
    if event not in {"push", "pull_request"} or (event == "push" and ref != "refs/heads/master"):
        return False, True
    if not all(re.fullmatch(r"[0-9a-fA-F]{40}", sha) and sha != "0" * 40 for sha in (base, head)):
        return False, True
    try:
        if event == "push":
            subprocess.run(["git", "merge-base", "--is-ancestor", base, head],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        revision = [f"{base}...{head}"] if event == "pull_request" else [base, head]
        result = subprocess.run(
            ["git", "diff", "--no-ext-diff", "--no-textconv", "--name-only", "--no-renames", "-z",
             *revision, "--"], check=True, capture_output=True)
    except subprocess.CalledProcessError:
        return False, True
    paths = [os.fsdecode(path) for path in result.stdout.split(b"\0") if path]
    if not paths:
        return False, True
    docs_only = all(path.startswith("docs/") or path in DOCS for path in paths)
    frontend = any(path.startswith(("frontend/", ".github/", "scripts/"))
                   or not (path.startswith(("backend/", "docs/")) or path in DOCS)
                   for path in paths)
    return docs_only, frontend


if __name__ == "__main__":
    docs, frontend = classify(*sys.argv[1:])
    print(f"docs_only={str(docs).lower()}")
    print(f"frontend_changed={str(frontend).lower()}")
