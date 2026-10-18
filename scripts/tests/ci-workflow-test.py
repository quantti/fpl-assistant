#!/usr/bin/env python3
"""Offline routing regressions; no Actions runs or application builds."""

import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCOPE = ROOT / "scripts/ci-scope.py"


class CiWorkflowTest(unittest.TestCase):
    def test_release_and_pr_job_contracts(self):
        workflow = (ROOT / ".github/workflows/deploy.yml").read_text()

        def job(name):
            match = re.search(rf"^  {name}:\n(.*?)(?=^  [\w-]+:|\Z)", workflow, re.M | re.S)
            self.assertIsNotNone(match, name)
            return match.group(1)

        pr = job("pr-checks")
        self.assertIn("if: github.event_name == 'pull_request'", pr)
        self.assertIn("cancel-in-progress: true", pr)
        self.assertNotIn("./gradlew", pr)
        self.assertNotIn("services:", pr)
        self.assertNotIn("setup-java", pr)
        self.assertIn("steps.scope.outputs.frontend_changed == 'true'", pr)
        for command in ("npm run build", "npm run lint", "npm run format:check"):
            self.assertIn(command, pr)

        test = job("test")
        self.assertIn("if: github.event_name != 'pull_request' && github.ref == 'refs/heads/master'", test)
        self.assertNotIn("concurrency:", test)  # Shared groups can replace pending master jobs.
        self.assertIn("./gradlew test", test)
        self.assertIn("docs_only: ${{ steps.scope.outputs.docs_only }}", test)
        self.assertIn("steps.scope.outputs.docs_only != 'true'", test)
        self.assertIn("python3 scripts/tests/ci-workflow-test.py", test)

        deploy = job("deploy")
        self.assertIn("needs: test", deploy)
        self.assertIn("github.ref == 'refs/heads/master'", deploy)
        self.assertIn("needs.test.result == 'success'", deploy)
        self.assertIn("needs.test.outputs.docs_only == 'false'", deploy)
        self.assertIn("cancel-in-progress: false", deploy)
        self.assertIn("https://fpl-sidekick.com/actuator/health", deploy)

    def test_scopes(self):
        with tempfile.TemporaryDirectory() as directory:
            cwd = Path(directory)
            env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=cwd, env=env, text=True).strip()

            def commit(files):
                for name in files:
                    path = cwd / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(path.read_text() + "\nchanged\n" if path.exists() else "initial\n")
                git("add", "--all")
                git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
                    "commit", "--quiet", "-m", "fixture")
                return git("rev-parse", "HEAD")

            def scope(event, base, head):
                result = subprocess.check_output(
                    [sys.executable, str(SCOPE), event, "refs/heads/master", base, head],
                    cwd=cwd, env=env, text=True)
                return dict(line.split("=", 1) for line in result.splitlines())

            git("init", "--quiet")
            base = commit(["docs/old.md"])
            cases = [
                (["docs/new.md"], "true", "false"),
                (["AGENTS.md"], "true", "false"),
                (["backend/App.java"], "false", "false"),
                (["frontend/src/App.tsx"], "false", "true"),
                (["backend/src/main/resources/docs/strategy.md"], "false", "false"),
                ([".github/workflows/deploy.yml"], "false", "true"),
                (["scripts/check.sh"], "false", "true"),
                (["unknown-file"], "false", "true"),
                (["docs/mixed.md", "backend/Other.java"], "false", "false"),
            ]
            for files, docs_only, frontend in cases:
                with self.subTest(files=files):
                    head = commit(files)
                    self.assertEqual(scope("push", base, head),
                                     {"docs_only": docs_only, "frontend_changed": frontend})
                    base = head

            docs_head = commit(["docs/manual.md"])
            self.assertEqual(scope("workflow_dispatch", base, docs_head),
                             {"docs_only": "false", "frontend_changed": "true"})
            self.assertEqual(scope("pull_request", base, docs_head)["docs_only"], "true")
            for missing in ("0" * 40, "1" * 40, "--bad-ref"):
                with self.subTest(missing=missing):
                    self.assertEqual(scope("push", missing, docs_head),
                                     {"docs_only": "false", "frontend_changed": "true"})
            self.assertEqual(scope("push", docs_head, docs_head)["docs_only"], "false")

            (cwd / "backend").mkdir(exist_ok=True)
            git("mv", "docs/old.md", "backend/renamed.md")
            renamed = commit([])
            self.assertEqual(scope("push", docs_head, renamed)["docs_only"], "false")
            git("rm", "docs/new.md")
            deleted = commit([])
            self.assertEqual(scope("push", renamed, deleted)["docs_only"], "true")

            # Do not truncate classification at GitHub's usual 300-file listing boundary.
            many = commit([f"docs/bulk/{n}.md" for n in range(301)] + ["backend/Bulk.java"])
            self.assertEqual(scope("push", deleted, many)["docs_only"], "false")


if __name__ == "__main__":
    unittest.main()
