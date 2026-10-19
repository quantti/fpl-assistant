"""Offline regressions for the bundle checker, not agent-behavior evaluations."""

import ast
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BUNDLE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("check_bundle", BUNDLE / "scripts/check_bundle.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class BundleChecks(unittest.TestCase):
    def make_bundle(self, root):
        root.mkdir()
        for name in ("SKILL.md", "README.md", "domain.md", "references/records.md", "references/walkthroughs.md"):
            path = root / name
            path.parent.mkdir(exist_ok=True)
            path.write_text("# Document\n", encoding="utf-8")

    def test_link_cannot_escape_the_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            (Path(directory) / "outside.md").write_text("outside\n")
            (root / "SKILL.md").write_text("[outside](../outside.md)\n")
            self.assertTrue(
                any("escapes bundle" in error for error in CHECKER.check_bundle(root)),
                "An existing file outside the bundle must not satisfy a local link.",
            )

    def test_missing_link_in_a_supporting_document_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            (root / "references/records.md").write_text("[missing](../absent.md)\n")
            self.assertTrue(
                any("missing local link" in error for error in CHECKER.check_bundle(root)),
                "Supporting documents must be checked, not just the entry point.",
            )

    def test_source_symlink_cannot_read_an_outside_document(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            outside = Path(directory) / "outside.md"
            outside.write_text("# Outside\n")
            (root / "SKILL.md").unlink()
            (root / "SKILL.md").symlink_to(outside)
            real_open = Path.open
            opened = []

            def guarded_open(path, *args, **kwargs):
                self.assertNotEqual(path.resolve(), outside.resolve(), "Reject outside sources before opening them.")
                opened.append(path.resolve())
                return real_open(path, *args, **kwargs)

            with patch.object(Path, "open", guarded_open):
                errors = CHECKER.check_bundle(root)
            self.assertIn((root / "README.md").resolve(), opened, "The read guard must observe real checker IO.")
            self.assertTrue(
                any("document escapes bundle" in error for error in errors),
                "The checker must bound source files as well as their links.",
            )

    def test_cli_reports_a_broken_bundle_without_executing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            (root / "README.md").write_text("[missing](missing.md)\n")
            result = subprocess.run(
                [sys.executable, "-B", str(BUNDLE / "scripts/check_bundle.py"), str(root)],
                cwd=directory, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 1, "A broken bundle must not exit successfully.")
            self.assertIn("missing local link", result.stdout)
            self.assertNotIn("Traceback", result.stderr)

    def test_unsupported_and_malformed_documents_fail_cleanly(self):
        for content in (b"[bad](file:/outside.md)\n", b"[bad](http://[)\n", b"[bad](bad%00path)\n", b"\xff", b"x" * (1024 * 1024 + 1)):
            with self.subTest(content_length=len(content)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "bundle"
                self.make_bundle(root)
                (root / "SKILL.md").write_bytes(content)
                result = subprocess.run(
                    [sys.executable, "-B", str(BUNDLE / "scripts/check_bundle.py"), str(root)],
                    cwd=directory, capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, 1)
                self.assertTrue(result.stdout.strip(), "Return a bounded diagnostic, not a traceback.")
                self.assertNotIn("Traceback", result.stderr)

    def test_valid_local_links_and_source_citations_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            (root / "SKILL.md").write_text(
                "[records](references/records.md#brief)\n[source](https://example.org/policy)\n"
            )
            self.assertEqual(CHECKER.check_bundle(root), [])
            result = subprocess.run(
                [sys.executable, "-B", str(BUNDLE / "scripts/check_bundle.py"), str(root)],
                cwd=directory, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0)
            self.assertIn("not task or runtime acceptance", result.stdout)

    def test_prefix_sibling_and_symlink_targets_do_not_count_as_local(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            sibling = Path(directory) / "bundle-other"
            sibling.mkdir()
            (sibling / "page.md").write_text("# Outside\n")
            (root / "link.md").symlink_to(sibling / "page.md")
            for target in ("../bundle-other/page.md", "link.md", "%2e%2e/bundle-other/page.md"):
                with self.subTest(target=target):
                    (root / "SKILL.md").write_text(f"[outside]({target})\n")
                    self.assertTrue(any("escapes bundle" in error for error in CHECKER.check_bundle(root)))

    def test_unclosed_markup_has_bounded_processing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            self.make_bundle(root)
            (root / "SKILL.md").write_text("[](" * 200_000)
            try:
                result = subprocess.run(
                    [sys.executable, "-B", str(BUNDLE / "scripts/check_bundle.py"), str(root)],
                    cwd=directory, capture_output=True, text=True, check=False, timeout=10,
                )
            except subprocess.TimeoutExpired:
                self.fail("A 600 KiB document must not trigger quadratic link matching.")
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_bundle_does_not_report_success(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(CHECKER.check_bundle(Path(directory) / "absent"))


class PortableBundle(unittest.TestCase):
    def test_entire_bundle_checks_from_an_isolated_copy_and_foreign_cwd(self):
        with tempfile.TemporaryDirectory() as directory:
            copied = Path(directory) / "software-factory"
            shutil.copytree(BUNDLE, copied, ignore=shutil.ignore_patterns("__pycache__"))
            elsewhere = Path(directory) / "unrelated-workspace"
            elsewhere.mkdir()
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(copied / "scripts/check_bundle.py")],
                cwd=elsewhere, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("not task or runtime acceptance", result.stdout)
            entry = (copied / "SKILL.md").read_text()
            self.assertTrue(entry.startswith("---\n"))
            self.assertRegex(entry, r"(?m)^name: software-factory$")
            self.assertRegex(entry, r"(?m)^description: .+$")
            self.assertRegex(entry, r"(?m)^disable-model-invocation: true$")

    def test_admitted_domain_specialization_preserves_the_generic_core(self):
        with tempfile.TemporaryDirectory() as directory:
            copied = Path(directory) / "software-factory"
            shutil.copytree(BUNDLE, copied, ignore=shutil.ignore_patterns("__pycache__"))
            with (copied / "domain.md").open("a") as stream:
                stream.write("\nAdmitted project: versioning-service. Policy owner: RELEX.\n")
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(copied / "tests/test_bundle.py"),
                 "PortableBundle.test_generic_core_has_no_repository_or_company_dependency"],
                cwd=directory, capture_output=True, text=True, check=False, timeout=10,
            )
            self.assertEqual(result.returncode, 0, "A domain supplement is not a core dependency: " + result.stderr)
            self.assertEqual(CHECKER.check_bundle(copied), [])
            with (copied / "SKILL.md").open("a") as stream:
                stream.write("\nRequires a RELEX-specific companion.\n")
            invalid = subprocess.run(
                [sys.executable, "-I", "-B", str(copied / "tests/test_bundle.py"),
                 "PortableBundle.test_generic_core_has_no_repository_or_company_dependency"],
                cwd=directory, capture_output=True, text=True, check=False, timeout=10,
            )
            self.assertEqual(invalid.returncode, 1, "Domain exemption must not weaken the core guard.")
            self.assertIn("SKILL.md binds an external dependency", invalid.stderr)

    def test_generic_core_has_no_repository_or_company_dependency(self):
        for name in ("SKILL.md", "README.md", "references/records.md", "references/walkthroughs.md"):
            content = (BUNDLE / name).read_text().lower()
            for forbidden in ("relex", "pi-config", "versioning-service", "/home/", "../implement/", "../verification-contract/"):
                self.assertNotIn(forbidden, content, f"{name} binds an external dependency")
        for path in BUNDLE.rglob("*.md"):
            self.assertNotRegex(path.read_text(), r"[\u200b\u200c\u200d\u2060\u202a-\u202e\u2066-\u2069]")
        module = ast.parse((BUNDLE / "scripts/check_bundle.py").read_text())
        for node in ast.walk(module):
            names = [alias.name for alias in node.names] if isinstance(node, ast.Import) else []
            if isinstance(node, ast.ImportFrom):
                names.append(node.module)
            for name in names:
                self.assertIn(name.split(".")[0], sys.stdlib_module_names)


if __name__ == "__main__":
    unittest.main()
