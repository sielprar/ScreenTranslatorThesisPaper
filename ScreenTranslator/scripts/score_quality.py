#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path


FIELDS = {"case_id", "unit_id", "source_text", "eligible", "detected", "correct", "placed"}


def read_units(path: Path) -> dict[str, list[dict[str, str]]]:
    cases: dict[str, list[dict[str, str]]] = defaultdict(list)
    seen: set[tuple[str, str]] = set()
    with path.open(newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)
        missing = FIELDS - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"Missing columns: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            case_id, unit_id = row["case_id"].strip(), row["unit_id"].strip()
            if not case_id or not unit_id or not row["source_text"].strip():
                raise ValueError(f"Line {line}: case_id, unit_id and source_text are required")
            key = case_id, unit_id
            if key in seen:
                raise ValueError(f"Line {line}: duplicate unit {key}")
            seen.add(key)
            for field in ("eligible", "detected", "correct", "placed"):
                if row[field] not in ("0", "1"):
                    raise ValueError(f"Line {line}: {field} must be 0 or 1")
            if row["correct"] == "1" and row["detected"] == "0":
                raise ValueError(f"Line {line}: correct needs detected=1")
            if row["placed"] == "1" and row["detected"] == "0":
                raise ValueError(f"Line {line}: placed needs detected=1")
            cases[case_id].append(row)
    if not cases:
        raise ValueError("No annotated units; refusing to report coverage")
    return cases


def ratio(num: int, den: int) -> str:
    return f"{num}/{den} ({num / den:.1%})" if den else "n/a"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("units", type=Path)
    args = parser.parse_args()
    try:
        cases = read_units(args.units)
    except (OSError, ValueError) as exc:
        parser.exit(2, f"Cannot score annotations: {exc}\n")
    pooled_pass = pooled_eligible = 0
    macro: list[float] = []
    print("| Case | Eligible | Detected | Correct / detected | Usable coverage | Control units with cards |")
    print("|---|---:|---:|---:|---:|---:|")
    for case_id, rows in sorted(cases.items()):
        eligible = [r for r in rows if r["eligible"] == "1"]
        detected = sum(r["detected"] == "1" for r in eligible)
        correct = sum(r["correct"] == "1" for r in eligible)
        passed = sum(all(r[f] == "1" for f in ("detected", "correct", "placed")) for r in eligible)
        false_positives = sum(r["detected"] == "1" for r in rows if r["eligible"] == "0")
        pooled_pass += passed
        pooled_eligible += len(eligible)
        if eligible:
            macro.append(passed / len(eligible))
        print(f"| {case_id} | {len(eligible)} | {ratio(detected, len(eligible))} | "
              f"{ratio(correct, detected)} | {ratio(passed, len(eligible))} | {false_positives} |")
    print(f"\nPooled usable coverage: {ratio(pooled_pass, pooled_eligible)}")
    print(f"Equal-case macro usable coverage: {sum(macro) / len(macro):.1%}" if macro else
          "Equal-case macro usable coverage: n/a")


if __name__ == "__main__":
    main()
