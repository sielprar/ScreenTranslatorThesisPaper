#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import statistics
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import eval_capture as ec
from analyze_bench import percentile

RERUN = f"{ec.APP_PACKAGE}.debug.RERUN"
OUT_ROOT = ec.EVAL_DIR / "latency"


@dataclass(frozen=True)
class Scenario:
    case_id: str
    mode: str
    paths: tuple[str, ...]
    n: int


SCENARIOS = [
    Scenario("menu_fr", "first_view", ("ocr_full",), 20),
    Scenario("menu_ru", "first_view", ("ocr_full",), 20),
    Scenario("menu_de", "first_view", ("ocr_full",), 20),
    Scenario("menu_fr", "cached", ("hash_hit",), 30),
    Scenario("browser_es", "first_view", ("ocr_full",), 10),
    Scenario("browser_ru", "first_view", ("a11y_full",), 10),
    Scenario("browser_ru", "cached", ("a11y_hit",), 30),
    Scenario("menu_fr", "cold", ("ocr_full",), 5),
    Scenario("settings_ru", "first_view", ("a11y_full",), 20),
    Scenario("settings_ru", "cached", ("a11y_hit",), 30),
    Scenario("settings_ru", "cold", ("a11y_full",), 5),
]


def frame_lines() -> list[str]:
    return ec.frames_csv().splitlines()


def wait_for_row(before: int, paths: tuple[str, ...], timeout: float) -> tuple[dict | None, int]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        lines = frame_lines()
        if len(lines) < before:
            before = 1
        if len(lines) > before:
            header = next(csv.reader([lines[0]]))
            for line in lines[before:]:
                row = dict(zip(header, next(csv.reader([line]))))
                if row.get("path") in paths:
                    return row, len(lines)
        time.sleep(0.4)
    return None, len(frame_lines())


def prepare(case: ec.Case, others: list[str], launch_wait: float) -> None:
    ec.disable_service(others)
    ec.launch(case, pause=False)
    time.sleep(launch_wait)
    since = ec.device_epoch_ms()
    ec.enable_service(others)
    if case.focus not in ("", "auto"):
        ec.shell(f"am broadcast -n {ec.EVAL_RECEIVER} -a {ec.SET_FOCUS} --es lang {case.focus}")
    ec.wait_until_settled(since, min_wait=4, quiet=4, timeout=60)


def sample_rerun(scenario: Scenario) -> list[dict]:
    rows = []
    clear = "true" if scenario.mode == "first_view" else "false"
    for index in range(scenario.n):
        before = len(frame_lines())
        ec.shell(f"am broadcast -n {ec.EVAL_RECEIVER} -a {RERUN} --ez clear {clear}")
        row, _ = wait_for_row(before, scenario.paths, timeout=90)
        if row is None:
            print(f"    sample {index}: no {scenario.paths} row within 90 s")
            continue
        rows.append(row)
        time.sleep(1.0)
    return rows


def sample_cold(scenario: Scenario, others: list[str]) -> list[dict]:
    rows = []
    for index in range(scenario.n):
        ec.set_enabled_services(others)
        time.sleep(1)
        ec.shell(f"am force-stop {ec.APP_PACKAGE}")
        time.sleep(1)
        before = len(frame_lines())
        ec.adb("logcat", "-c", check=False)
        ec.enable_service(others)
        row, _ = wait_for_row(before, scenario.paths, timeout=120)
        if row is None:
            print(f"    sample {index}: no first pass within 120 s")
            continue
        connected = [
            line for line in ec.adb("logcat", "-d", "-v", "epoch", "-s", "ScreenTranslatorA11y:I", check=False).splitlines()
            if "Accessibility service connected" in line
        ]
        if connected:
            connect_ms = int(float(connected[-1].split()[0]) * 1000)
            row["connect_to_cards_ms"] = str(int(row["ts_ms"]) + int(row["total_ms"]) - connect_ms)
        rows.append(row)
        time.sleep(2)
    return rows


def summarise(samples: list[dict]) -> list[str]:
    groups: dict[tuple[str, str], list[dict]] = {}
    for row in samples:
        groups.setdefault((row["scenario"], row["case_id"], row.get("flags", "all")), []).append(row)

    def ms(rows, key):
        return [int(r[key]) for r in rows]

    lines = [
        "| Scenario | Case | Path | n | Total p50 | Total p95 | Max | Harvest p50 | OCR p50 | LID+NMT p50 | Overlay p50 | Cards (median) |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    cold_lines = []
    for (scenario, case_id, flags), rows in groups.items():
        if flags != "all":
            scenario = f"{scenario} [{flags}]"
        total = ms(rows, "total_ms")
        if scenario.startswith("cold") and all(r.get("connect_to_cards_ms") for r in rows):
            ctc = ms(rows, "connect_to_cards_ms")
            cold_lines.append(f"| {case_id} | {len(rows)} | {percentile(ctc, 50)} | {max(ctc)} |")
        lines.append(
            f"| {scenario} | {case_id} | {rows[0]['path']} | {len(rows)} | "
            f"{percentile(total, 50)} | {percentile(total, 95)} | {max(total)} | "
            f"{percentile(ms(rows, 'harvest_ms'), 50)} | {percentile(ms(rows, 'ocr_ms'), 50)} | "
            f"{percentile(ms(rows, 'nmt_ms'), 50)} | {percentile(ms(rows, 'overlay_ms'), 50)} | "
            f"{statistics.median(ms(rows, 'n_cards')):g} |"
        )
    if cold_lines:
        lines += ["", "Cold process start, service connected → first cards shown:", "",
                  "| Case | n | p50 | Max |", "|---|---:|---:|---:|", *cold_lines]
    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", nargs="*", help="case ids to include")
    parser.add_argument("--n", type=int, help="override samples per scenario")
    parser.add_argument("--out", type=Path, help="append to an existing run directory")
    parser.add_argument("--flags", default="", help='ablation switches, e.g. "tesseract=0"')
    parser.add_argument("--modes", nargs="*", help="first_view / cached / cold")
    args = parser.parse_args()
    ec.ADB = ec.find_adb()
    cases = ec.load_cases()
    build = ec.check_build(ec.installed_build(), allow_mismatch=False)
    locale = ec.device_locale()

    out = args.out or OUT_ROOT / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    samples_path = out / "samples.csv"
    samples = list(csv.DictReader(samples_path.open(encoding="utf-8"))) if samples_path.exists() else []

    original = ec.enabled_services()
    others = [s for s in original if s != ec.SERVICE]
    prepared = None
    try:
        for scenario in SCENARIOS:
            if args.only and scenario.case_id not in args.only:
                continue
            if args.modes and scenario.mode not in args.modes:
                continue
            case = cases[scenario.case_id]
            if case.expected_locale and case.expected_locale != locale:
                print(f"[{scenario.case_id} {scenario.mode}] skipped: needs {case.expected_locale}, device is {locale}")
                continue
            n = args.n or scenario.n
            paths = ("hash_hit", "a11y_hit") if scenario.mode == "cached" else ("ocr_full", "a11y_full")
            scenario = Scenario(scenario.case_id, scenario.mode, paths, n)
            print(f"[{scenario.case_id} {scenario.mode}] {n} samples", flush=True)
            if prepared != scenario.case_id:
                ec.set_flags(args.flags)
                prepare(case, others, 15 if case.launch.startswith("url:") else 5)
                prepared = scenario.case_id
            if scenario.mode == "cold":
                rows = sample_cold(scenario, others)
                prepared = None
            else:
                rows = sample_rerun(scenario)
            for row in rows:
                row.update(scenario=scenario.mode, case_id=scenario.case_id, build=build, locale=locale,
                           flags=args.flags or "all")
            samples += rows
            print(f"    kept {len(rows)}", flush=True)
    finally:
        if args.flags:
            ec.set_flags("")
        ec.set_enabled_services(original)

    if samples:
        keys = dict.fromkeys(k for row in samples for k in row)
        header = ["scenario", "case_id", "build", "locale"] + [k for k in keys if k not in ("scenario", "case_id", "build", "locale")]
        with samples_path.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=header, extrasaction="ignore", lineterminator="\n")
            writer.writeheader()
            writer.writerows(samples)
        table = summarise(samples)
        (out / "summary.md").write_text(
            f"# Latency run\n\nBuild `{build}`, emulator (Pixel 7 profile, Android 14, Apple Silicon host), "
            f"warm models unless the scenario is `cold`. Times in ms, nearest-rank percentiles.\n\n"
            + "\n".join(table) + "\n",
            encoding="utf-8",
        )
        print("\n".join(table))
    print(f"→ {out}")


if __name__ == "__main__":
    main()
