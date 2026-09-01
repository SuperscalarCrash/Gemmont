#!/usr/bin/env python3
"""Render release notes from the reproducible FPGA and func reports."""

from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path


REQUIRED_BUILD_INFO = {
    "release_version",
    "source_sha",
    "chiplab_url",
    "functional_chiplab_ref",
    "functional_chiplab_sha",
    "fpga_chiplab_ref",
    "fpga_chiplab_sha",
    "uboot_chiplab_ref",
    "uboot_chiplab_sha",
    "vivado_runtime",
    "vivado_version",
    "fpga_part",
    "fpga_targets",
    "func_requested_mhz",
    "perf_requested_mhz",
    "uboot_requested_mhz",
    "timing_met",
    "uboot_binary_url",
    "uboot_binary_sha256",
    "programmer_by_uart_url",
    "programmer_by_uart_sha256",
    "linux_releases_url",
    "build_time_utc",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-info", required=True, type=Path)
    parser.add_argument("--func-timing", required=True, type=Path)
    parser.add_argument("--perf-timing", required=True, type=Path)
    parser.add_argument("--uboot-timing", type=Path)
    parser.add_argument("--functional-results", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def fail(message: str) -> int:
    print(f"error: {message}", file=sys.stderr)
    return 1


def load_json(path: Path, description: str) -> dict:
    if not path.is_file():
        raise ValueError(f"{description} is missing: {path}")
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"{description} must contain a JSON object")
    return value


def load_build_info(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError(f"build information is missing: {path}")
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not raw_line:
            continue
        key, separator, value = raw_line.partition("=")
        if not separator or not key or key in result:
            raise ValueError(f"invalid BUILD_INFO.txt line {line_number}")
        result[key] = value
    missing = REQUIRED_BUILD_INFO - result.keys()
    if missing:
        raise ValueError(f"BUILD_INFO.txt is missing: {', '.join(sorted(missing))}")
    if result["timing_met"] not in {"true", "false"}:
        raise ValueError("BUILD_INFO.txt has an invalid timing_met value")
    return result


def decimal_value(value: object, description: str) -> Decimal:
    if isinstance(value, bool):
        raise ValueError(f"{description} must be numeric")
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"{description} must be numeric") from exc
    if not number.is_finite():
        raise ValueError(f"{description} must be finite")
    return number


def format_decimal(value: object, places: int = 3) -> str:
    return f"{decimal_value(value, 'metric'):.{places}f}"


def escape_table(value: object) -> str:
    return str(value).replace("|", r"\|").replace("\n", " ")


def validate_timing(metrics: dict, variant: str) -> None:
    if metrics.get("variant") != variant:
        raise ValueError(f"{variant} timing metrics have the wrong variant")
    if not isinstance(metrics.get("timing_met"), bool):
        raise ValueError(f"{variant} timing metrics have an invalid timing_met value")
    if not isinstance(metrics.get("clock_name"), str) or not metrics["clock_name"]:
        raise ValueError(f"{variant} timing metrics have an invalid clock_name value")
    for key in ("cpu_mhz", "period_ns", "wns_ns", "tns_ns", "whs_ns", "ths_ns"):
        decimal_value(metrics.get(key), f"{variant} {key}")
    if not isinstance(metrics.get("violations"), list):
        raise ValueError(f"{variant} timing metrics have invalid violations")


def render_notes(
    build_info: dict[str, str], timings: list[dict], functional: dict, repository: str
) -> str:
    aggregate_timing_met = all(item["timing_met"] for item in timings)
    if aggregate_timing_met != (build_info["timing_met"] == "true"):
        raise ValueError("aggregate timing status does not match BUILD_INFO.txt")

    passed = functional.get("passed")
    total = functional.get("total")
    if not isinstance(passed, int) or not isinstance(total, int) or total <= 0:
        raise ValueError("functional verification summary is invalid")
    results = functional.get("results")
    if not isinstance(results, list) or len(results) != total:
        raise ValueError("functional verification results are missing")
    if functional.get("all_passed") is not True or passed != total:
        raise ValueError("functional verification did not pass completely")

    source_sha = build_info["source_sha"]
    source_url = f"https://github.com/{repository}/commit/{source_sha}"
    lines = [f"# Gemmont {build_info['release_version']}", ""]
    if aggregate_timing_met:
        lines.extend(
            [
                "> [!NOTE]",
                "> All packaged FPGA targets meet the reported timing constraints.",
            ]
        )
    else:
        failed = ", ".join(
            item["variant"] for item in timings if not item["timing_met"]
        )
        lines.extend(
            [
                "> [!WARNING]",
                f"> Bitstreams were generated, but timing failed for: **{failed}**.",
            ]
        )

    lines.extend(
        [
            "",
            "## FPGA targets",
            "",
            "The release contains the func, perf, and U-Boot FPGA targets. The perf target is provided as a board image and RAM package; no perf RTL simulation is part of this workflow.",
            "",
            f"- FPGA part: `{build_info['fpga_part']}`",
            f"- Vivado: `{build_info['vivado_version']}`",
            f"- Build runtime: `{build_info['vivado_runtime']}`",
            f"- Requested func frequency: **{build_info['func_requested_mhz']} MHz**",
            f"- Requested perf frequency: **{build_info['perf_requested_mhz']} MHz**",
            f"- Requested U-Boot frequency: **{build_info['uboot_requested_mhz']} MHz**",
            f"- Build time: `{build_info['build_time_utc']}`",
            "",
            "## Frequency and timing",
            "",
            "| Target | Result | Clock | Frequency | Period | WNS | TNS | WHS | THS |",
            "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for timing in timings:
        result = "PASS" if timing["timing_met"] else "FAIL"
        lines.append(
            f"| `{timing['variant']}` | **{result}** | "
            f"`{escape_table(timing['clock_name'])}` | "
            f"{format_decimal(timing['cpu_mhz'])} MHz | "
            f"{format_decimal(timing['period_ns'])} ns | "
            f"{format_decimal(timing['wns_ns'])} ns | "
            f"{format_decimal(timing['tns_ns'])} ns | "
            f"{format_decimal(timing['whs_ns'])} ns | "
            f"{format_decimal(timing['ths_ns'])} ns |"
        )
    violations = [
        f"- `{timing['variant']}`: {escape_table(violation)}"
        for timing in timings
        for violation in timing["violations"]
    ]
    if violations:
        lines.extend(["", "Timing violations:", "", *violations])

    lines.extend(["", "## U-Boot materials", ""])
    lines.extend(
        [
            "The ZIP includes the U-Boot-capable SoC bitstream, the pinned bootloader binary, and the UART programmer helper.",
            "",
            "```bash",
            "./boot/tools/openfpgaloader.sh flash-uboot",
            "./boot/tools/openfpgaloader.sh sram",
            "```",
            "",
            f"- U-Boot source: {build_info['uboot_binary_url']}",
            f"- U-Boot SHA-256: `{build_info['uboot_binary_sha256']}`",
            f"- Programmer bitstream: {build_info['programmer_by_uart_url']}",
            f"- Programmer SHA-256: `{build_info['programmer_by_uart_sha256']}`",
            f"- Linux releases: {build_info['linux_releases_url']}",
        ]
    )

    lines.extend(
        [
            "",
            "## Functional verification",
            "",
            f"**{passed}/{total} tests passed.**",
            "",
            "| Target | Status | Details |",
            "| --- | --- | --- |",
        ]
    )
    for result in results:
        reasons = result.get("reasons", [])
        details = "; ".join(str(reason) for reason in reasons) or "complete"
        lines.append(
            f"| `{escape_table(result['target'])}` | {escape_table(result['status'])} | "
            f"{escape_table(details)} |"
        )

    lines.extend(
        [
            "",
            "## Reproducibility",
            "",
            f"- Gemmont source: [`{source_sha}`]({source_url})",
            f"- Chiplab functional: `{build_info['functional_chiplab_ref']}` at `{build_info['functional_chiplab_sha']}`",
            f"- Chiplab FPGA: `{build_info['fpga_chiplab_ref']}` at `{build_info['fpga_chiplab_sha']}`",
            f"- Chiplab U-Boot: `{build_info['uboot_chiplab_ref']}` at `{build_info['uboot_chiplab_sha']}`",
            f"- Chiplab remote: {build_info['chiplab_url']}",
            "- The ZIP contains the exact generated RTL, RAM images, checksums, and implementation reports.",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    try:
        build_info = load_build_info(args.build_info)
        timings = [
            load_json(args.func_timing, "func timing metrics"),
            load_json(args.perf_timing, "perf timing metrics"),
        ]
        validate_timing(timings[0], "func")
        validate_timing(timings[1], "perf")
        if args.uboot_timing is not None:
            timings.append(load_json(args.uboot_timing, "U-Boot timing metrics"))
            validate_timing(timings[-1], "uboot")
        functional = load_json(args.functional_results, "functional results")
        notes = render_notes(build_info, timings, functional, args.repository)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(notes, encoding="utf-8")
    except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as exc:
        return fail(str(exc))
    print(f"Release notes: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
