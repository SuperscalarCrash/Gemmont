#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


FUNCTION_TARGETS = (
    "func/func_lab3",
    "func/func_lab4",
    "func/func_lab6",
    "func/func_lab7",
    "func/func_lab8",
    "func/func_lab9",
    "func/func_lab14",
    "func/func_lab15",
    "func/func_lab19",
    "func/func_advance",
)

NEMU_ENABLED_PATTERN = re.compile(r"\bDifftest enabled\.?")
NEMU_COMPLETION_PATTERN = re.compile(r"^END by Syscall\r?$", re.MULTILINE)
FAILURE_PATTERNS = (
    re.compile(r"difftest.*(?:fail|mismatch)", re.IGNORECASE),
    re.compile(r"\bmismatch\b", re.IGNORECASE),
    re.compile(r"dead[ _-]?clock", re.IGNORECASE),
    re.compile(r"segmentation fault", re.IGNORECASE),
    re.compile(r"timed? out|timeout", re.IGNORECASE),
    re.compile(r"\babort(?:ed)?\b", re.IGNORECASE),
    re.compile(r"\bfatal\b", re.IGNORECASE),
    re.compile(r"\bError\s*\(", re.IGNORECASE),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate the complete Chiplab function suite")
    parser.add_argument("--logs-dir", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-markdown", required=True, type=Path)
    return parser.parse_args()


def target_stem(target: str) -> str:
    return target.replace("/", "__")


def read_exit_status(path: Path) -> tuple[int | None, str | None]:
    if not path.is_file():
        return None, "missing process status"
    raw = path.read_text(encoding="utf-8", errors="replace").strip()
    try:
        return int(raw), None
    except ValueError:
        return None, f"invalid process status: {raw!r}"


def validate_target(logs_dir: Path, target: str) -> dict:
    stem = target_stem(target)
    log_path = logs_dir / f"{stem}.log"
    status_path = logs_dir / f"{stem}.status"
    reasons: list[str] = []

    status, status_error = read_exit_status(status_path)
    if status_error:
        reasons.append(status_error)
    elif status != 0:
        reasons.append(f"process exited with status {status}")

    if not log_path.is_file():
        reasons.append("missing simulation log")
        text = ""
    else:
        text = log_path.read_text(encoding="utf-8", errors="replace")
        if not text.strip():
            reasons.append("simulation log is empty")

    if text and not NEMU_ENABLED_PATTERN.search(text):
        reasons.append("missing NEMU difftest enabled marker")
    if text and not NEMU_COMPLETION_PATTERN.search(text):
        reasons.append("missing NEMU clean completion marker")

    for pattern in FAILURE_PATTERNS:
        if text and pattern.search(text):
            reasons.append(f"failure marker matched: {pattern.pattern}")

    return {
        "target": target,
        "status": "PASS" if not reasons else "FAIL",
        "exit_status": status,
        "log": str(log_path),
        "reasons": reasons,
    }


def write_reports(results: list[dict], output_json: Path, output_markdown: Path) -> bool:
    passed = sum(item["status"] == "PASS" for item in results)
    total = len(results)
    all_passed = passed == total
    payload = {
        "passed": passed,
        "total": total,
        "all_passed": all_passed,
        "results": results,
    }

    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_markdown.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Functional verification",
        "",
        f"**Result: {passed}/{total} {'PASS' if all_passed else 'FAIL'}**",
        "",
        "| Target | Status | Details |",
        "| --- | --- | --- |",
    ]
    for item in results:
        details = "; ".join(item["reasons"]) if item["reasons"] else "complete"
        lines.append(f"| `{item['target']}` | {item['status']} | {details} |")
    output_markdown.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return all_passed


def main() -> int:
    args = parse_args()
    results = [validate_target(args.logs_dir, target) for target in FUNCTION_TARGETS]
    all_passed = write_reports(results, args.output_json, args.output_markdown)
    if not all_passed:
        print("functional verification did not reach 10/10 PASS", file=sys.stderr)
        return 1
    print("functional verification: 10/10 PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
