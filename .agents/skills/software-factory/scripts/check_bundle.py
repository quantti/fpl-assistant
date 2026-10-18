"""Read-only structural checks for the portable skill bundle; not task acceptance."""

import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


def check_bundle(root: Path) -> list[str]:
    root = root.resolve()
    errors = []
    for name in ("SKILL.md", "README.md", "domain.md", "references/records.md", "references/walkthroughs.md"):
        document = root / name
        # SECURITY: resolve symlinks and compare path segments before reading.
        try:
            document.resolve().relative_to(root)
        except ValueError:
            errors.append(f"{name}: document escapes bundle")
            continue
        if not document.is_file():
            errors.append(f"{name}: missing document")
            continue
        # Bound reads; this checker never prints document contents or link values.
        try:
            with document.open("rb") as stream:
                data = stream.read(1024 * 1024 + 1)
            if len(data) > 1024 * 1024:
                errors.append(f"{name}: document exceeds 1 MiB")
                continue
            text = data.decode("utf-8")
        except (OSError, UnicodeError):
            errors.append(f"{name}: cannot read UTF-8 document")
            continue
        for link in re.findall(r"\[[^\[\]\n]*\]\(([^()\n]+)\)", text):
            try:
                parts = urlsplit(link)
            except ValueError:
                errors.append(f"{name}: malformed link")
                continue
            if parts.scheme in {"http", "https"}:
                continue  # A source citation is not fetched or authenticated here.
            if parts.scheme or parts.netloc:
                errors.append(f"{name}: unsupported link scheme")
                continue
            if not parts.path:
                continue
            try:
                target = (document.parent / unquote(parts.path)).resolve()
                target.relative_to(root)
            except (ValueError, OSError):
                errors.append(f"{name}: invalid local link or escapes bundle")
                continue
            if not target.is_file():
                errors.append(f"{name}: missing local link")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = check_bundle(args.bundle)
    for error in errors:
        print(error)
    if not errors:
        print("Bundle checks passed; this is not task or runtime acceptance.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
