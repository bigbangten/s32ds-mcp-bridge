#!/usr/bin/env python3
"""Append a compact verified lesson to the S32DS skill memory."""

from __future__ import annotations

import argparse
import datetime as _dt
import re
import sys
from pathlib import Path

SECRET_PATTERNS = [
    (re.compile(r"Bearer\s+[A-Za-z0-9._~+\-/]+=*", re.I), "Bearer [REDACTED]"),
    (re.compile(r"(?i)(token\s*[:=]\s*)[^\s,;]+"), r"\1[REDACTED]"),
]


def redact(value: str) -> str:
    text = (value or "").strip()
    for pattern, replacement in SECRET_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


def one_line(value: str) -> str:
    return re.sub(r"\s+", " ", redact(value)).strip()


def notes_path() -> Path:
    return Path(__file__).resolve().parents[1] / "references" / "lessons.md"


def format_entry(args: argparse.Namespace) -> str:
    today = args.date or _dt.date.today().isoformat()
    lines = [
        f"## {today} - {one_line(args.title)}",
        f"Tags: {one_line(args.tags)}",
        f"Context: {one_line(args.context)}",
        f"Failed: {one_line(args.failed)}",
        f"Worked: {one_line(args.worked)}",
        f"Verify: {one_line(args.verify)}",
    ]
    if args.caution:
        lines.append(f"Caution: {one_line(args.caution)}")
    return "\n".join(lines) + "\n"


def append_or_replace(path: Path, entry: str, title: str, replace: bool) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        text = path.read_text(encoding="utf-8")
    else:
        text = "# S32DS Learned Recipes\n\n"

    title_pattern = re.compile(rf"^## \d{{4}}-\d{{2}}-\d{{2}} - {re.escape(one_line(title))}\n", re.M)
    match = title_pattern.search(text)
    if match and not replace:
        return "exists"

    if match and replace:
        next_match = re.search(r"^## \d{4}-\d{2}-\d{2} - ", text[match.end():], re.M)
        end = match.end() + next_match.start() if next_match else len(text)
        text = text[:match.start()] + entry + text[end:]
        if not text.endswith("\n"):
            text += "\n"
    else:
        if not text.endswith("\n"):
            text += "\n"
        text += "\n" + entry

    path.write_text(text, encoding="utf-8")
    return "replaced" if match else "appended"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Record a verified S32DS skill lesson.")
    parser.add_argument("--title", required=True)
    parser.add_argument("--tags", required=True, help="Comma-separated keywords, e.g. dialog,swt,wm-close")
    parser.add_argument("--context", required=True, help="Situation where the issue appeared")
    parser.add_argument("--failed", required=True, help="Approach that failed and why")
    parser.add_argument("--worked", required=True, help="Verified repeatable procedure")
    parser.add_argument("--verify", required=True, help="How to verify success")
    parser.add_argument("--caution", default="", help="Safety boundary or caveat")
    parser.add_argument("--date", default="", help="YYYY-MM-DD; defaults to today")
    parser.add_argument("--replace", action="store_true", help="Replace an existing lesson with the same title")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--mirror", action="append", default=[], help="Additional lessons.md path to mirror")
    args = parser.parse_args(argv)

    entry = format_entry(args)
    if args.dry_run:
        print(entry, end="")
        return 0

    targets = [notes_path()] + [Path(p) for p in args.mirror]
    for target in targets:
        result = append_or_replace(target, entry, args.title, args.replace)
        print(f"{result}: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))