#!/usr/bin/env python3
"""The public distribution must not silently select an operator's Supabase project."""
from pathlib import Path
import os
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PublicConfigurationTest(unittest.TestCase):
    def test_backend_requires_the_operator_project(self):
        config = (ROOT / "backend/src/main/resources/application.yml").read_text()
        self.assertIn("  url: ${SUPABASE_URL}\n", config)

    def test_frontend_has_no_project_fallback(self):
        client = (ROOT / "frontend/src/auth/supabaseClient.ts").read_text()
        self.assertIn("const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;", client)
        config = (ROOT / "frontend/vite.config.ts").read_text()
        self.assertIn("if (!env.VITE_SUPABASE_URL)", config)

    def test_schema_export_requires_an_explicit_host(self):
        env = {**os.environ, "DB_USER": "test-fixture", "DB_PASSWORD": "test-fixture"}
        env.pop("SHARED_DB_HOST", None)
        result = subprocess.run(
            ["bash", str(ROOT / "scripts/dump-test-schema.sh")],
            env=env, capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Set SHARED_DB_HOST", result.stderr)

    def test_container_accepts_the_operator_project(self):
        dockerfile = (ROOT / "Dockerfile").read_text()
        self.assertIn("ARG VITE_SUPABASE_URL\n", dockerfile)
        self.assertIn("ENV VITE_SUPABASE_URL=$VITE_SUPABASE_URL\n", dockerfile)


if __name__ == "__main__":
    unittest.main()
