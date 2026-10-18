#!/usr/bin/env python3
"""Classify the checked-out change range; uncertainty always selects full checks."""

import os
import re
import subprocess
import sys

DOCS = {"README.md", "AGENTS.md", "CLAUDE.md"}


def valid_revisions(*shas):
    return all(re.fullmatch(r"[0-9a-fA-F]{40}", sha) and sha != "0" * 40 for sha in shas)


def classify(event, ref, base, head):
    if event not in {"push", "pull_request"} or (event == "push" and ref != "refs/heads/master"):
        return False, True
    if not valid_revisions(base, head):
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


def can_deploy(ref, tested, latest):
    if ref != "refs/heads/master" or not valid_revisions(tested, latest):
        return False
    ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", tested, latest],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return ancestry.returncode == 0 and (tested == latest or classify("push", ref, tested, latest)[0])


if __name__ == "__main__":
    event, ref, base, head = sys.argv[1:]
    if event == "deployment":
        print(f"deploy_allowed={str(can_deploy(ref, base, head)).lower()}")
    else:
        docs, frontend = classify(event, ref, base, head)
        print(f"docs_only={str(docs).lower()}")
        print(f"frontend_changed={str(frontend).lower()}")
