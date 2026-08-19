#!/usr/bin/env python3
"""PreToolUse gate: a Fly.io deploy always asks first.

Deploying is a merge to master (.github/workflows/deploy.yml). A manual `fly deploy` is the
emergency path — it ships whatever is in the working tree, tested or not — so it never runs
without the user saying so in the moment.

A permission rule alone would not hold: `Bash(fly deploy:*)` matches by prefix, so
`~/.fly/bin/flyctl deploy`, `cd backend && fly deploy` or `PATH=... flyctl deploy` slip past it.
This reads the whole command line instead.
"""
import json
import re
import sys

PATTERN = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?fly(?:ctl)?\s+[^;&|]*\bdeploy\b")

REASON = (
    "This runs a Fly.io deploy, which ships to production immediately. Deploys normally happen "
    "by merging to master (.github/workflows/deploy.yml), which tests first; a manual deploy is "
    "the emergency path and can ship an untested tree."
)

def main() -> None:
    try:
        command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
    except (json.JSONDecodeError, AttributeError):
        return
    if PATTERN.search(command):
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": REASON,
        }}, sys.stdout)

main()
