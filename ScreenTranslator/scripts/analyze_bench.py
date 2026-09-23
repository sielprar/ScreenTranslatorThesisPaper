#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


APP_CATEGORY: dict[str, str] = {
    "com.android.settings": "native",
    "com.android.chrome": "browser",
    "com.google.android.googlequicksearchbox": "native",
    "com.google.android.apps.messaging": "chat",
    "com.whatsapp": "chat",
    "org.telegram.messenger": "chat",
    "com.facebook.orca": "chat",
    "com.google.android.gm": "native",
    "com.google.android.apps.docs": "native",
    "com.android.vending": "native",
    "com.supercell.clashofclans": "game",
    "com.mojang.minecraftpe": "game",
}


@dataclass
class Row:
    frame_id: int
    ts_ms: int
    pkg: str
    path: str
    total_ms: int
    harvest_ms: int
    hash_ms: int
    ocr_ms: int
    nmt_ms: int
    overlay_ms: int
    n_regions: int
    n_cards: int
    ocr_w: int
    ocr_h: int
    ocr_scale: float


def parse(csv_path: Path) -> list[Row]:
    rows: list[Row] = []
    with csv_path.open() as fh:
        for r in csv.DictReader(fh):
            rows.append(
                Row(
                    frame_id=int(r["frame_id"]),
                    ts_ms=int(r["ts_ms"]),
                    pkg=r["pkg"],
                    path=r["path"],
                    total_ms=int(r["total_ms"]),
                    harvest_ms=int(r["harvest_ms"]),
                    hash_ms=int(r["hash_ms"]),
                    ocr_ms=int(r["ocr_ms"]),
                    nmt_ms=int(r["nmt_ms"]),
                    overlay_ms=int(r["overlay_ms"]),
                    n_regions=int(r["n_regions"]),
                    n_cards=int(r["n_cards"]),
                    ocr_w=int(r["ocr_w"]),
                    ocr_h=int(r["ocr_h"]),
                    ocr_scale=float(r["ocr_scale"]),
                )
            )
    return rows


def percentile(values: list[int], p: float) -> int:
    if not values:
        return 0
    values = sorted(values)
    k = int(round((p / 100.0) * (len(values) - 1)))
    return values[k]


def bucket(rows: Iterable[Row], by: str) -> dict[str, list[Row]]:
    buckets: dict[str, list[Row]] = defaultdict(list)
    for r in rows:
        if by == "pkg":
            key = r.pkg
        elif by == "category":
            key = APP_CATEGORY.get(r.pkg, "other")
        else:
            raise ValueError(by)
        buckets[key].append(r)
    return buckets


def format_ms(values: list[int]) -> str:
    if not values:
        return "-"
    return f"{percentile(values, 50)} / {percentile(values, 95)}"


def render(buckets: dict[str, list[Row]]) -> str:
    header_key = "Key"
    lines = [
        f"| {header_key:<28} | Total frames | Fast-path | OCR-path | Cache | Fast p50/p95 | OCR p50/p95 |",
        "|" + "-" * 30 + "|" + "-" * 14 + "|" + "-" * 11 + "|" + "-" * 10 + "|" + "-" * 7 + "|" + "-" * 15 + "|" + "-" * 14 + "|",
    ]

    def frames_of(rows: list[Row], paths: set[str]) -> list[int]:
        return [r.total_ms for r in rows if r.path in paths]

    for key in sorted(buckets):
        rows = buckets[key]
        total = len(rows)
        fast_total = sum(1 for r in rows if r.path in ("a11y_full", "a11y_hit"))
        ocr_total = sum(1 for r in rows if r.path == "ocr_full")
        cache = sum(1 for r in rows if r.path in ("a11y_hit", "hash_hit"))
        fast_lat = frames_of(rows, {"a11y_full"})
        ocr_lat = frames_of(rows, {"ocr_full"})
        lines.append(
            f"| {key:<28} | {total:>12} | {fast_total:>9} | {ocr_total:>8} | "
            f"{cache:>5} | {format_ms(fast_lat):>13} | {format_ms(ocr_lat):>12} |"
        )
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path)
    ap.add_argument("--by", choices=("pkg", "category"), default="pkg")
    args = ap.parse_args()

    rows = parse(args.csv)
    if not rows:
        print("No frames recorded.", file=sys.stderr)
        return 1

    print(f"# Latency summary — {len(rows)} frames from {args.csv}")
    print()
    print(f"Grouped by **{args.by}**.  Values are p50 / p95 wall-clock ms.")
    print()
    print(render(bucket(rows, args.by)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
