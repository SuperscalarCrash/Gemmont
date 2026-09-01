#!/usr/bin/env python3
"""Validate and expose the frozen release configuration.

The release workflow and its local wrappers deliberately read the same JSON
file.  Keeping the validation here prevents a pin or frequency from being
silently duplicated in shell or YAML.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import NoReturn, TextIO


def fail(message: str) -> NoReturn:
    raise ValueError(message)


def validate(config_path: Path) -> dict:
    try:
        with config_path.open(encoding="utf-8") as stream:
            config = json.load(stream)
    except FileNotFoundError as exc:
        raise ValueError(f"release configuration is missing: {config_path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON in {config_path}: {exc}") from exc

    if not isinstance(config, dict) or set(config) != {
        "chiplab",
        "frequencies_mhz",
        "mill",
    }:
        fail("release config must contain chiplab, frequencies_mhz, and mill only")

    chiplab = config["chiplab"]
    if not isinstance(chiplab, dict) or set(chiplab) != {"url", "pins"}:
        fail("chiplab must contain url and pins only")
    url = chiplab["url"]
    if not isinstance(url, str) or not url.startswith("https://"):
        fail("chiplab.url must be an HTTPS URL")
    pins = chiplab["pins"]
    expected_roles = {"functional", "fpga", "uboot"}
    if not isinstance(pins, dict) or set(pins) != expected_roles:
        fail("chiplab.pins must contain functional, fpga, and uboot only")
    for role in sorted(expected_roles):
        pin = pins[role]
        if not isinstance(pin, dict) or set(pin) != {"ref", "sha"}:
            fail(f"chiplab.pins.{role} must contain ref and sha only")
        ref = pin["ref"]
        sha = pin["sha"]
        if not isinstance(ref, str) or not ref or any(
            character not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._/-"
            for character in ref
        ):
            fail(f"chiplab.pins.{role}.ref contains unsupported characters")
        if not isinstance(sha, str) or len(sha) != 40 or any(
            character not in "0123456789abcdef" for character in sha
        ):
            fail(f"chiplab.pins.{role}.sha must be a lowercase 40-character SHA")

    frequencies = config["frequencies_mhz"]
    expected_frequencies = {"func", "perf", "uboot"}
    if not isinstance(frequencies, dict) or set(frequencies) != expected_frequencies:
        fail("frequencies_mhz must contain func, perf, and uboot only")
    for name in sorted(expected_frequencies):
        value = frequencies[name]
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            fail(f"frequencies_mhz.{name} must be numeric")
        if not math.isfinite(float(value)) or float(value) <= 0:
            fail(f"frequencies_mhz.{name} must be positive and finite")

    mill = config["mill"]
    if not isinstance(mill, dict) or set(mill) != {"version", "launcher_sha256"}:
        fail("mill must contain version and launcher_sha256 only")
    version = mill["version"]
    launcher_sha = mill["launcher_sha256"]
    if not isinstance(version, str) or not version:
        fail("mill.version must be a non-empty string")
    if not isinstance(launcher_sha, str) or len(launcher_sha) != 64 or any(
        character not in "0123456789abcdef" for character in launcher_sha
    ):
        fail("mill.launcher_sha256 must be a lowercase 64-character SHA")

    return config


def number_text(value: int | float) -> str:
    """Use a stable, compact representation for shell and GitHub outputs."""

    if isinstance(value, int):
        return str(value)
    return format(value, ".15g")


def values(config: dict) -> dict[str, str]:
    pins = config["chiplab"]["pins"]
    frequencies = config["frequencies_mhz"]
    return {
        "CHIPLAB_URL": config["chiplab"]["url"],
        "FUNCTION_CHIPLAB_REF": pins["functional"]["ref"],
        "FUNCTION_CHIPLAB_SHA": pins["functional"]["sha"],
        "FPGA_CHIPLAB_REF": pins["fpga"]["ref"],
        "FPGA_CHIPLAB_SHA": pins["fpga"]["sha"],
        "UBOOT_CHIPLAB_REF": pins["uboot"]["ref"],
        "UBOOT_CHIPLAB_SHA": pins["uboot"]["sha"],
        "FUNC_REQUESTED_MHZ": number_text(frequencies["func"]),
        "PERF_REQUESTED_MHZ": number_text(frequencies["perf"]),
        "UBOOT_REQUESTED_MHZ": number_text(frequencies["uboot"]),
        "MILL_VERSION": config["mill"]["version"],
        "MILL_LAUNCHER_SHA256": config["mill"]["launcher_sha256"],
    }


def write_github_output(output: TextIO, data: dict[str, str]) -> None:
    names = {
        "CHIPLAB_URL": "chiplab_url",
        "FUNCTION_CHIPLAB_REF": "functional_ref",
        "FUNCTION_CHIPLAB_SHA": "functional_sha",
        "FPGA_CHIPLAB_REF": "fpga_ref",
        "FPGA_CHIPLAB_SHA": "fpga_sha",
        "UBOOT_CHIPLAB_REF": "uboot_ref",
        "UBOOT_CHIPLAB_SHA": "uboot_sha",
        "FUNC_REQUESTED_MHZ": "func_mhz",
        "PERF_REQUESTED_MHZ": "perf_mhz",
        "UBOOT_REQUESTED_MHZ": "uboot_mhz",
        "MILL_VERSION": "mill_version",
        "MILL_LAUNCHER_SHA256": "mill_launcher_sha256",
    }
    for source_name, output_name in names.items():
        output.write(f"{output_name}={data[source_name]}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    default_path = Path(__file__).resolve().parents[2] / "ci" / "release-config.json"
    parser.add_argument("--config", type=Path, default=default_path)
    parser.add_argument("--shell", action="store_true", help="print shell assignments")
    parser.add_argument(
        "--github-output",
        type=Path,
        help="write GitHub Actions step outputs to this file",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        data = values(validate(args.config))
        if args.shell:
            for name, value in data.items():
                print(f"{name}={value}")
        if args.github_output is not None:
            args.github_output.parent.mkdir(parents=True, exist_ok=True)
            with args.github_output.open("a", encoding="utf-8") as output:
                write_github_output(output, data)
        if not args.shell and args.github_output is None:
            json.dump(data, sys.stdout, indent=2, sort_keys=True)
            sys.stdout.write("\n")
    except (OSError, TypeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
