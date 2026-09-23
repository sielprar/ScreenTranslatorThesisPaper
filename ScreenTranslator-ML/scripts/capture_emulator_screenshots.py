"""Capture emulator/device screenshots for the real-eval corpus.

Usage:
    python scripts/capture_emulator_screenshots.py --category settings --out data/real_screenshots

Requires `adb` on PATH. The user should have a connected emulator or device
with the target app open before running.
"""
from __future__ import annotations
import argparse
import subprocess
import sys
import time
from pathlib import Path


def check_adb_available() -> None:
    try:
        subprocess.run(["adb", "version"], check=True, capture_output=True, timeout=5)
    except FileNotFoundError:
        print("ERROR: `adb` not on PATH. Install Android platform-tools first.", file=sys.stderr)
        sys.exit(1)
    except subprocess.SubprocessError as e:
        print(f"ERROR: `adb version` failed: {e}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--category", choices=["settings", "chat", "maps", "pdf", "game"], required=True)
    p.add_argument("--out", type=Path, default=Path("data/real_screenshots"))
    p.add_argument("--count", type=int, default=25)
    p.add_argument("--interval", type=float, default=2.5)
    args = p.parse_args()

    check_adb_available()

    (args.out / args.category).mkdir(parents=True, exist_ok=True)
    print(f"Capturing {args.count} screenshots for category={args.category} to {args.out / args.category}")
    print(f"Interval: {args.interval}s between shots. Navigate the app between shots.")

    for i in range(args.count):
        stamp = int(time.time() * 1000)
        target = args.out / args.category / f"{args.category}_{stamp}.png"
        with target.open("wb") as fp:
            result = subprocess.run(
                ["adb", "exec-out", "screencap", "-p"],
                stdout=fp,
                check=False,
                capture_output=False,
            )
        if result.returncode != 0:
            print(f"WARN capture {i+1} failed with returncode {result.returncode}", file=sys.stderr)
            target.unlink(missing_ok=True)
            continue
        print(f"[{i+1}/{args.count}] saved {target}")
        if i < args.count - 1:
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
