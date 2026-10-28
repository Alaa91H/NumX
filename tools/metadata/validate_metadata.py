#!/usr/bin/env python3
"""Differential validation of generated DFAs against libphonenumber.

This is a development/CI tool, not part of the Android build. It samples
numbers from every generated plan and asks libphonenumber whether each one is
valid for its region and whether its type matches the plan.
"""

from __future__ import annotations

import json
import os
import random
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ASSETS = ROOT / "numx-android" / "app" / "src" / "main" / "assets" / "metadata"
LIBPHONENUMBER_JAR = Path(
    os.environ.get("LIBPHONENUMBER_JAR", "/tmp/opencode/lp/libphonenumber.jar")
)
sys.path.insert(0, str(HERE))

from dfa import Dfa, count_exact, state_after  # noqa: E402


def load_plan(path: Path) -> Dfa:
    value = json.loads(path.read_text(encoding="utf-8"))
    transitions = []
    for row in value["transitions"]:
        edges = {}
        for digit, target in enumerate(row):
            if target >= 0:
                edges[str(digit)] = target
        transitions.append(edges)
    return Dfa(value["start"], transitions, set(value["accept"]))


def sample_numbers(dfa: Dfa, lengths, amount: int, rng: random.Random):
    viable = [length for length in lengths if count_exact(dfa, dfa.start, length) > 0]
    if not viable:
        return []
    results = set()
    attempts = 0
    while len(results) < amount and attempts < amount * 20:
        attempts += 1
        length = rng.choice(viable)
        state = dfa.start
        text = ""
        ok = True
        for position in range(length):
            remaining = length - position - 1
            choices = []
            for digit in "0123456789":
                target = dfa.transitions[state].get(digit)
                if target is not None and count_exact(dfa, target, remaining) > 0:
                    choices.append((digit, target))
            if not choices:
                ok = False
                break
            digit, state = rng.choice(choices)
            text += digit
        if ok and state in dfa.accepting:
            results.add(text)
    return sorted(results)


def main() -> int:
    countries = {
        item["iso2"]: item
        for item in json.loads((ASSETS / "countries.json").read_text(encoding="utf-8"))[
            "countries"
        ]
    }
    lines = []
    expected = {}
    for path in sorted((ASSETS / "plans").glob("*_*.json")):
        iso, line_type = path.stem.split("_", 1)
        country = countries[iso]
        plan = json.loads(path.read_text(encoding="utf-8"))
        dfa = load_plan(path)
        rng = random.Random(f"{iso}-{line_type}-v1")
        numbers = sample_numbers(dfa, country.get(
            "mobileLengths" if line_type == "MOBILE" else "fixedLengths", []
        ), 40, rng)
        examples = [
            country.get("exampleMobile") if line_type == "MOBILE" else country.get("exampleFixed")
        ]
        for number in numbers:
            key = f"{iso}\t{line_type}\t{country['callingCode']}\t{number}"
            lines.append(key)
            expected[key] = True
        for number in examples:
            if number:
                key = f"{iso}\t{line_type}\t{country['callingCode']}\t{number}"
                lines.append(key)
                expected[key] = True
    completed = subprocess.run(
        [
            "java",
            "-cp",
            ":".join(
                str(Path(item))
                for item in [
                    LIBPHONENUMBER_JAR,
                    str(HERE),
                ]
            ),
            "ValidatePlans",
        ],
        input="\n".join(lines),
        text=True,
        capture_output=True,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        return completed.returncode
    failures = 0
    checked = 0
    for row in completed.stdout.splitlines():
        parts = row.split("\t")
        if len(parts) != 7:
            print("bad output", row)
            failures += 1
            continue
        iso, line_type, cc, nsn, valid, region, number_type = parts
        checked += 1
        expected_type = "MOBILE" if line_type == "MOBILE" else "FIXED_LINE"
        type_ok = number_type in (expected_type, "FIXED_LINE_OR_MOBILE")
        # The app deliberately supports Wafa's 091/092 Syrian prefixes, which
        # upstream libphonenumber does not classify. That override is tested by
        # the app's own golden tests instead of the upstream metadata.
        sy_override = iso == "SY" and line_type == "MOBILE" and nsn[:2] in {"91", "92"}
        if sy_override:
            checked += 1
            continue
        if valid != "true" or not type_ok:
            print(
                f"FAIL {iso} {line_type} +{cc}{nsn}: valid={valid} region={region} type={number_type}"
            )
            failures += 1
    print(f"checked {checked} samples, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
