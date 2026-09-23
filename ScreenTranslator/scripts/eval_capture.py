#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
EVAL_DIR = REPO / "study" / "evaluation"
CASES_CSV = EVAL_DIR / "cases.csv"
PLAN_CSV = EVAL_DIR / "capture_plan.csv"
RUNS_CSV = EVAL_DIR / "runs.csv"
CAPTURES_DIR = EVAL_DIR / "captures"

APP_PACKAGE = "com.screentranslator.android"
SERVICE = f"{APP_PACKAGE}/{APP_PACKAGE}.service.ScreenTranslatorAccessibilityService"
EVAL_RECEIVER = f"{APP_PACKAGE}/.debug.EvalControlReceiver"
SET_FOCUS = f"{APP_PACKAGE}.debug.SET_FOCUS"
SET_FLAGS = f"{APP_PACKAGE}.debug.SET_FLAGS"
FLAG_NAMES = ("a11y", "multiscale", "tesseract", "focus")
DEVICE_DIR = "/sdcard/Pictures/ScreenTranslatorEval"
LOG_TAGS = ("ScreenTranslatorA11y", "ScreenTranslatorPipe", "ScreenTranslatorBench", "ScreenTranslatorEval")
IMAGE_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
STATUS_BAR_FRACTION = 0.07


class CaptureError(RuntimeError):
    pass


def find_adb() -> str:
    candidates = [
        shutil.which("adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
    ]
    for path in candidates:
        if path and os.path.isfile(path):
            return path
    raise CaptureError("adb not found; add platform-tools to PATH or set ANDROID_HOME")


ADB = ""


def adb(*args: str, check: bool = True, binary: bool = False, timeout: float = 60):
    result = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise CaptureError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def shell(command: str, check: bool = True) -> str:
    return adb("shell", command, check=check).strip()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as file:
        return list(csv.DictReader(file))


@dataclass
class Case:
    case_id: str
    source_language: str
    source: str
    focus: str
    launch: str
    package: str
    expected_locale: str
    viewport: str
    setup_notes: str


def load_cases() -> dict[str, Case]:
    plans = {row["case_id"]: row for row in read_csv(PLAN_CSV)}
    cases = {}
    for row in read_csv(CASES_CSV):
        plan = plans.get(row["case_id"])
        if plan is None:
            raise CaptureError(f"{row['case_id']} has no row in {PLAN_CSV.name}")
        cases[row["case_id"]] = Case(
            case_id=row["case_id"],
            source_language=row["source_language"],
            source=row["source"],
            focus=row["focus"],
            launch=plan["launch"],
            package=plan["package"],
            expected_locale=plan["expected_locale"],
            viewport=plan["viewport"],
            setup_notes=plan["setup_notes"],
        )
    return cases


def installed_build() -> str:
    dump = shell(f"dumpsys package {APP_PACKAGE}")
    match = re.search(r"versionName=(\S+)", dump)
    if not match:
        raise CaptureError(f"{APP_PACKAGE} is not installed")
    return match.group(1)


def check_build(version_name: str, allow_mismatch: bool) -> str:
    match = re.fullmatch(r"[^+]+\+([0-9a-f]{7,}|unknown)(\.dirty)?", version_name)
    if not match:
        raise CaptureError(f"versionName {version_name!r} has no git SHA; rebuild the debug APK")
    sha, dirty = match.group(1), bool(match.group(2))
    problems = []
    if dirty:
        problems.append("installed APK was built from uncommitted changes (.dirty)")
    elif sha == "unknown":
        problems.append("installed APK has no git SHA")
    else:
        diff = subprocess.run(
            ["git", "diff", "--quiet", sha, "HEAD", "--", "android"],
            cwd=REPO,
        )
        if diff.returncode != 0:
            problems.append(f"android/ changed between installed build {sha} and HEAD")
    if problems and not allow_mismatch:
        raise CaptureError("; ".join(problems) + " (reinstall, or pass --allow-mismatch)")
    return sha + (".dirty" if dirty else "")


def device_locale() -> str:
    for prop in ("persist.sys.locale", "ro.product.locale"):
        value = shell(f"getprop {prop}", check=False)
        if value:
            return value
    return "unknown"


def translation_models() -> list[str]:
    listing = shell(f"run-as {APP_PACKAGE} ls no_backup/com.google.mlkit.translate.models", check=False)
    return sorted(name for name in listing.split() if re.fullmatch(r"[a-z]{2,3}_[a-z]{2,3}", name))


def model_for(source_language: str) -> str | None:
    if source_language == "en":
        return None
    return "_".join(sorted([source_language, "en"]))


def foreground_package() -> str:
    dump = shell("dumpsys activity activities", check=False)
    match = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ ([^/\s]+)/", dump)
    return match.group(1) if match else "unknown"


def device_epoch_ms() -> int:
    return int(shell("date +%s")) * 1000


def enabled_services() -> list[str]:
    value = shell("settings get secure enabled_accessibility_services", check=False)
    if value in ("", "null"):
        return []
    return [item for item in value.split(":") if item]


def set_enabled_services(services: list[str]) -> None:
    if services:
        shell(f"settings put secure enabled_accessibility_services {':'.join(services)}")
        shell("settings put secure accessibility_enabled 1")
    else:
        shell("settings delete secure enabled_accessibility_services", check=False)


def logcat_contains(text: str) -> bool:
    return text in adb("logcat", "-d", "-s", *(f"{tag}:I" for tag in LOG_TAGS), check=False)


def wait_for_log(text: str, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if logcat_contains(text):
            return True
        time.sleep(0.5)
    return False


def disable_service(others: list[str]) -> None:
    if SERVICE not in enabled_services():
        return
    adb("logcat", "-c", check=False)
    set_enabled_services(others)
    wait_for_log("Accessibility service destroyed", timeout=5)
    time.sleep(0.5)


def enable_service(others: list[str]) -> None:
    set_enabled_services(others + [SERVICE])
    if not wait_for_log("Accessibility service connected", timeout=15):
        raise CaptureError("accessibility service did not connect within 15 s")


def set_flags(spec: str) -> str:
    values = dict.fromkeys(FLAG_NAMES, "true")
    for item in filter(None, (part.strip() for part in spec.split(","))):
        name, _, value = item.partition("=")
        if name not in values or value not in ("0", "1"):
            raise CaptureError(f"bad flag {item!r}; use {', '.join(FLAG_NAMES)} with =0 or =1")
        values[name] = "true" if value == "1" else "false"
    extras = " ".join(f"--ez {name} {value}" for name, value in values.items())
    reply = shell(f"am broadcast -n {EVAL_RECEIVER} -a {SET_FLAGS} {extras}")
    match = re.search(r'data="([^"]*)"', reply)
    if "result=1" not in reply or not match:
        raise CaptureError(f"could not set flags: {reply}")
    return match.group(1)


def screenshot_png() -> bytes:
    data = adb("exec-out", "screencap", "-p", binary=True)
    if not data.startswith(b"\x89PNG"):
        raise CaptureError("screencap did not return a PNG")
    return data


def screen_fingerprint() -> str:
    raw = adb("exec-out", "screencap", binary=True)
    width = int.from_bytes(raw[0:4], "little")
    height = int.from_bytes(raw[4:8], "little")
    pixels = raw[len(raw) - width * height * 4:]
    skip = int(height * STATUS_BAR_FRACTION) * width * 4
    return hashlib.sha1(pixels[skip:]).hexdigest()


def frames_csv() -> str:
    return adb("exec-out", "run-as", APP_PACKAGE, "cat", "files/benchmarks/frames.csv", check=False)


def frames_since(since_ms: int) -> tuple[str, list[str]]:
    lines = frames_csv().splitlines()
    if not lines:
        return "", []
    header, rows = lines[0], []
    ts_index = header.split(",").index("ts_ms") if "ts_ms" in header else 1
    for line in lines[1:]:
        fields = next(csv.reader([line]))
        try:
            if int(fields[ts_index]) >= since_ms:
                rows.append(line)
        except (IndexError, ValueError):
            continue
    return header, rows


def wait_until_settled(since_ms: int, min_wait: float, quiet: float, timeout: float) -> tuple[bool, float]:
    start = time.monotonic()
    last_rows, last_screen = -1, ""
    stable_since = time.monotonic()
    while True:
        elapsed = time.monotonic() - start
        _, rows = frames_since(since_ms)
        screen = screen_fingerprint()
        if len(rows) != last_rows or screen != last_screen:
            last_rows, last_screen = len(rows), screen
            stable_since = time.monotonic()
        quiet_for = time.monotonic() - stable_since
        if elapsed >= min_wait and rows and quiet_for >= quiet:
            return True, elapsed
        if elapsed >= timeout:
            return False, elapsed
        time.sleep(0.7)


def launch_image(case: Case, repo_path: str) -> str:
    source = REPO / repo_path
    if not source.is_file():
        raise CaptureError(f"source image missing: {repo_path}")
    suffix = source.suffix.lower()
    mime = IMAGE_TYPES.get(suffix)
    if mime is None:
        raise CaptureError(f"unsupported image type {suffix}")
    name = f"{case.case_id}{suffix}"
    remote = f"{DEVICE_DIR}/{name}"
    shell(f"mkdir -p {DEVICE_DIR}")
    adb("push", str(source), remote)
    shell(f"touch {remote}")
    shell(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{remote}")
    media_id = None
    for _ in range(20):
        rows = shell(
            "content query --uri content://media/external/images/media --projection _id "
            f"--where \"_display_name='{name}'\"",
            check=False,
        )
        ids = re.findall(r"_id=(\d+)", rows)
        if ids:
            media_id = ids[-1]
            break
        time.sleep(0.5)
    if media_id is None:
        raise CaptureError(f"media scanner did not index {remote}")
    uri = f"content://media/external/images/media/{media_id}"
    package = f"-p {case.package} " if case.package else ""
    shell(f"am start -S -W -a android.intent.action.VIEW -d {uri} -t {mime} {package}".strip())
    return f"{repo_path} -> {uri}"


def launch(case: Case, pause: bool) -> str:
    kind, _, target = case.launch.partition(":")
    if kind == "image":
        described = launch_image(case, target)
    elif kind == "intent":
        shell(f"am start -S -W -a {target}")
        described = f"intent {target}"
    elif kind == "url":
        package = f"-p {case.package} " if case.package else ""
        shell(f"am start -S -W -a android.intent.action.VIEW -d '{target}' {package}".strip())
        described = target
    elif kind == "cut":
        raise CaptureError(f"{case.case_id} was cut from protocol v1 ({case.setup_notes})")
    elif kind == "manual":
        print(f"\n[{case.case_id}] Manual setup: {case.source}")
        if case.setup_notes:
            print(f"  Note: {case.setup_notes}")
        print(f"  Viewport: {case.viewport}")
        input("  The translator is OFF. Open the exact source screen, then press Enter… ")
        described = case.source
        pause = False
    else:
        raise CaptureError(f"unknown launch kind {kind!r} for {case.case_id}")
    if pause:
        input("  Adjust the viewport if needed (translator is OFF), then press Enter… ")
    return described


def append_run(row: dict[str, str], runs_csv: Path = RUNS_CSV) -> None:
    if not runs_csv.exists():
        with RUNS_CSV.open(newline="", encoding="utf-8") as file:
            header_line = file.readline()
        runs_csv.parent.mkdir(parents=True, exist_ok=True)
        runs_csv.write_text(header_line, encoding="utf-8")
    with runs_csv.open(newline="", encoding="utf-8") as file:
        header = next(csv.reader(file))
    missing = set(row) - set(header)
    if missing:
        raise CaptureError(f"runs.csv lacks columns: {sorted(missing)}")
    with runs_csv.open("a", newline="", encoding="utf-8") as file:
        csv.DictWriter(file, fieldnames=header, lineterminator="\n").writerow(row)


def list_cases(cases: dict[str, Case]) -> None:
    captured = {row["case_id"] for row in read_csv(RUNS_CSV)}
    print(f"{'case':<16} {'focus':<5} {'locale':<6} {'launch':<55} captured")
    for case in cases.values():
        print(f"{case.case_id:<16} {case.focus:<5} {case.expected_locale:<6} "
              f"{case.launch[:55]:<55} {'yes' if case.case_id in captured else '-'}")


def capture(case: Case, args: argparse.Namespace) -> Path:
    version_name = installed_build()
    build_sha = check_build(version_name, args.allow_mismatch)

    locale = device_locale()
    if case.expected_locale and locale != case.expected_locale and not args.ignore_locale:
        raise CaptureError(
            f"device locale is {locale}, case expects {case.expected_locale}. "
            "Change it in Settings > System > Languages, or pass --ignore-locale."
        )

    models = translation_models()
    needed = model_for(case.source_language)
    if needed and needed not in models and not args.allow_cold:
        raise CaptureError(
            f"translation model {needed} is not on the device, so this would be a cold "
            "run. Warm it by using the app once on this language, or pass --allow-cold."
        )
    model_state = (f"{needed} present" if needed in models else f"{needed} missing (cold)") if needed \
        else "no model needed (English source)"

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = (args.out or CAPTURES_DIR) / case.case_id / stamp
    out.mkdir(parents=True)

    def rel(path: Path) -> str:
        return str(path.relative_to(REPO)) if path.is_relative_to(REPO) else str(path)

    original = enabled_services()
    others = [item for item in original if item != SERVICE]
    try:
        print(f"[{case.case_id}] build {build_sha}, locale {locale}, {model_state}")
        disable_service(others)
        described = launch(case, args.pause)
        time.sleep(args.launch_wait)
        (out / "source.png").write_bytes(screenshot_png())
        package = foreground_package()
        if case.package and package != case.package:
            print(f"  warning: foreground is {package}, plan expects {case.package}")

        adb("logcat", "-b", "main,system,crash", "-c", check=False)
        since_ms = device_epoch_ms()
        flags = set_flags(args.flags)
        enable_service(others)
        if case.focus not in ("", "auto"):
            reply = shell(f"am broadcast -n {EVAL_RECEIVER} -a {SET_FOCUS} --es lang {case.focus}")
            if "result=1" not in reply:
                raise CaptureError(f"could not set focus {case.focus}: {reply}")

        settled, waited = wait_until_settled(since_ms, args.min_wait, args.quiet, args.timeout)
        (out / "output.png").write_bytes(screenshot_png())
        header, rows = frames_since(since_ms)
        (out / "frames.csv").write_text("\n".join([header, *rows]) + "\n", encoding="utf-8")
        log = adb("logcat", "-d", "-v", "threadtime", "-s", *(f"{tag}:V" for tag in LOG_TAGS), check=False)
        (out / "pipeline.log").write_text(log, encoding="utf-8")
        crashed = f"Process: {APP_PACKAGE}" in adb("logcat", "-d", "-b", "crash", check=False)
    finally:
        if args.flags:
            set_flags("")
        set_enabled_services(original)

    display = shell("wm size").split(":")[-1].strip()
    notes = [
        f"settled={'yes' if settled else 'NO (timeout)'} after {waited:.1f}s",
        f"{len(rows)} pipeline pass(es)",
        "service reconnected before capture: warm model, empty caches",
        f"flags: {flags}",
        f"launch={described}",
    ]
    if crashed:
        notes.append("APP CRASH in logcat")
    if args.notes:
        notes.append(args.notes)
    run = {
        "case_id": case.case_id,
        "build_sha": build_sha,
        "device": shell("getprop ro.product.model"),
        "android_version": shell("getprop ro.build.version.release"),
        "display_px": display,
        "app_package": package,
        "source_uri_or_asset": case.source,
        "locale": locale,
        "viewport_and_zoom": case.viewport,
        "focus": case.focus,
        "model_state": model_state,
        "source_screenshot": rel(out / "source.png"),
        "output_screenshot": rel(out / "output.png"),
        "trace_csv": rel(out / "frames.csv"),
        "notes": "; ".join(notes),
    }
    if args.no_record:
        print("  --no-record: runs.csv left unchanged")
    else:
        append_run(run, args.runs_csv)
    print(f"  {'settled' if settled else 'NOT settled'} after {waited:.1f}s, "
          f"{len(rows)} pass(es) → {rel(out)}")
    return out


def main() -> None:
    global ADB
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("case_ids", nargs="*", help="cases from cases.csv, or 'all-auto' for every non-manual case")
    parser.add_argument("--list", action="store_true", help="list cases and capture status")
    parser.add_argument("--pause", action="store_true", help="pause after launch to adjust the viewport")
    parser.add_argument("--launch-wait", type=float, default=5.0, help="seconds after launch before the source shot")
    parser.add_argument("--min-wait", type=float, default=4.0, help="minimum seconds with the service on")
    parser.add_argument("--quiet", type=float, default=4.0, help="seconds without new passes or pixels")
    parser.add_argument("--timeout", type=float, default=60.0, help="give up waiting to settle after this")
    parser.add_argument("--out", type=Path, help="capture directory (default study/evaluation/captures)")
    parser.add_argument("--no-record", action="store_true", help="do not append to runs.csv (smoke tests)")
    parser.add_argument("--runs-csv", type=Path, default=RUNS_CSV, help="runs file to append to")
    parser.add_argument("--flags", default="", help='ablation switches, e.g. "tesseract=0" (see set_flags)')
    parser.add_argument("--notes", default="", help="free text appended to the runs.csv notes")
    parser.add_argument("--allow-mismatch", action="store_true", help="accept a dirty or out-of-date build")
    parser.add_argument("--allow-cold", action="store_true", help="accept a missing translation model")
    parser.add_argument("--ignore-locale", action="store_true", help="accept a locale other than the plan's")
    args = parser.parse_args()
    try:
        ADB = find_adb()
        cases = load_cases()
        if args.list or not args.case_ids:
            list_cases(cases)
            return
        ids = args.case_ids
        if ids == ["all-auto"]:
            ids = [c.case_id for c in cases.values() if c.launch.split(":")[0] not in ("manual", "cut")]
        unknown = [case_id for case_id in ids if case_id not in cases]
        if unknown:
            raise CaptureError(f"unknown case(s): {', '.join(unknown)}")
        for case_id in ids:
            capture(cases[case_id], args)
    except CaptureError as exc:
        sys.exit(f"eval_capture: {exc}")


if __name__ == "__main__":
    main()
