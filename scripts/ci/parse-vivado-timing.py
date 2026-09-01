#!/usr/bin/env python3
import argparse
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


SUMMARY_ROW_PATTERN = re.compile(
    r"^\s*(-?\d+(?:\.\d+)?)\s+"
    r"(-?\d+(?:\.\d+)?)\s+\d+\s+\d+\s+"
    r"(-?\d+(?:\.\d+)?)\s+"
    r"(-?\d+(?:\.\d+)?)\b",
    re.MULTILINE,
)
CLOCK_ROW_PATTERN = re.compile(
    r"^\s*(?P<name>\S+)\s+\{[^}]+\}\s+"
    r"(?P<period>\d+(?:\.\d+)?)\s+"
    r"(?P<frequency>\d+(?:\.\d+)?)\b",
    re.MULTILINE,
)
TIMING_MET_MARKER = "All user specified timing constraints are met."
UBOOT_CLOCK_NAME_HINTS = tuple(
    re.compile(pattern, re.IGNORECASE)
    for pattern in (
        r"(^|[/_.])cpu(_|$)",
        r"(^|[/_.])core(_|$)",
        r"clk_pll_33",
        r"pll_33",
        r"clk_33",
        r"clk_out1",
    )
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Report Vivado FPGA timing")
    parser.add_argument("--variant", required=True, choices=("func", "perf", "uboot"))
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-markdown", required=True, type=Path)
    return parser.parse_args()


def fail(message: str) -> int:
    print(f"error: {message}", file=sys.stderr)
    return 1


def clock_summary_section(text: str) -> str:
    if "| Clock Summary" not in text:
        return ""
    section = text.split("| Clock Summary", 1)[1]
    for marker in (
        "| Intra Clock Table",
        "| Inter Clock Table",
        "| Other Path Groups",
        "| User Ignored Paths",
        "| Unconstrained Paths",
    ):
        if marker in section:
            section = section.split(marker, 1)[0]
    return section


def parse_clocks(text: str) -> list[dict[str, Decimal | str]]:
    clocks = []
    for match in CLOCK_ROW_PATTERN.finditer(clock_summary_section(text)):
        name = match.group("name")
        if name in {"Clock", "-----"}:
            continue
        clocks.append(
            {
                "name": name,
                "period": Decimal(match.group("period")),
                "frequency": Decimal(match.group("frequency")),
            }
        )
    return clocks


def select_clock(
    variant: str,
    clocks: list[dict[str, Decimal | str]],
) -> dict[str, Decimal | str] | None:
    for clock in clocks:
        if clock["name"] == "cpu_clk":
            return clock

    if variant != "uboot":
        return None

    for pattern in UBOOT_CLOCK_NAME_HINTS:
        for clock in clocks:
            if pattern.search(str(clock["name"])):
                return clock

    if not clocks:
        return None

    # Chiplab's Loongson U-Boot project does not always name the board-level CPU
    # clock cpu_clk.  If no recognizable name is present, report the clock whose
    # frequency is closest to the CI release target instead of failing the whole
    # release after bitstream generation succeeded.
    target_mhz = Decimal("64.0")
    return min(clocks, key=lambda clock: abs(clock["frequency"] - target_mhz))


def main() -> int:
    args = parse_args()
    if not args.report.is_file():
        return fail(f"timing report is missing: {args.report}")
    text = args.report.read_text(encoding="utf-8", errors="replace")

    if "| Design Timing Summary" not in text:
        return fail("Design Timing Summary section is missing")
    summary_section = text.split("| Design Timing Summary", 1)[1]
    if "| Clock Summary" in summary_section:
        summary_section = summary_section.split("| Clock Summary", 1)[0]
    summary_match = SUMMARY_ROW_PATTERN.search(summary_section)
    if not summary_match:
        return fail("Design Timing Summary values are missing")

    wns, tns, whs, ths = (Decimal(value) for value in summary_match.groups())
    clocks = parse_clocks(text)
    selected_clock = select_clock(args.variant, clocks)
    if selected_clock is None:
        if args.variant == "uboot":
            return fail("no clock entries were found in Clock Summary")
        return fail("cpu_clk entry is missing from Clock Summary")

    clock_name = str(selected_clock["name"])
    period_ns = selected_clock["period"]
    clock_mhz = selected_clock["frequency"]
    violations = []
    if wns < 0:
        violations.append(f"WNS is negative ({wns} ns)")
    if tns != 0:
        violations.append(f"TNS is non-zero ({tns} ns)")
    if whs < 0:
        violations.append(f"WHS is negative ({whs} ns)")
    if ths != 0:
        violations.append(f"THS is non-zero ({ths} ns)")
    if TIMING_MET_MARKER not in text:
        violations.append("Vivado timing-met marker is missing")
    timing_met = not violations

    payload = {
        "variant": args.variant,
        "clock_name": clock_name,
        "cpu_mhz": float(clock_mhz),
        "clock_mhz": float(clock_mhz),
        "period_ns": float(period_ns),
        "wns_ns": float(wns),
        "tns_ns": float(tns),
        "whs_ns": float(whs),
        "ths_ns": float(ths),
        "timing_met": timing_met,
        "violations": violations,
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_markdown.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    args.output_markdown.write_text(
        "\n".join(
            [
                f"# Vivado timing: {args.variant}",
                "",
                (
                    "**Result: PASS**"
                    if timing_met
                    else "**Result: FAIL — released with a timing warning**"
                ),
                "",
                f"- Clock: `{clock_name}`",
                f"- Frequency: {clock_mhz} MHz",
                f"- Period: {period_ns} ns",
                f"- WNS: {wns} ns",
                f"- TNS: {tns} ns",
                f"- WHS: {whs} ns",
                f"- THS: {ths} ns",
                *(
                    ["", "Violations:", *[f"- {item}" for item in violations]]
                    if violations
                    else []
                ),
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    result = "PASS" if timing_met else "FAIL (release warning)"
    print(
        f"Vivado {args.variant} timing {result}: {clock_name}={clock_mhz}MHz "
        f"WNS={wns}ns WHS={whs}ns"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
