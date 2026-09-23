"""Bulk generate synthetic recognizer crops OR detector pages.

Usage:
    python scripts/generate_synthetic.py --mode recognizer --out data/synthetic/recognizer --n 1000000
    python scripts/generate_synthetic.py --mode detector   --out data/synthetic/detector   --n 200000
"""
from __future__ import annotations
import argparse
from pathlib import Path
from multiprocessing import Pool
from screentranslator_ml.data.synthetic import generate_recognizer_batch, generate_detector_batch


def recognizer_worker(args: tuple[int, int, Path]) -> None:
    shard_id, count, out = args
    shard_dir = out / f"shard_{shard_id:04d}"
    generate_recognizer_batch(
        out_dir=shard_dir,
        n=count,
        corpus=Path("data/corpora/ru_wiki_full.txt"),
        fonts_dir=Path("data/fonts"),
        seed=shard_id,
    )


def detector_worker(args: tuple[int, int, Path]) -> None:
    shard_id, count, out = args
    shard_dir = out / f"shard_{shard_id:04d}"
    generate_detector_batch(
        out_dir=shard_dir,
        n=count,
        corpus=Path("data/corpora/ru_wiki_full.txt"),
        fonts_dir=Path("data/fonts"),
        canvas_size=(720, 1280),
        seed=shard_id,
    )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--mode", choices=["recognizer", "detector"], default="recognizer")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--n", type=int, default=1_000_000)
    p.add_argument("--shard-size", type=int, default=10_000)
    p.add_argument("--workers", type=int, default=4)
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    n_shards = args.n // args.shard_size
    jobs = [(i, args.shard_size, args.out) for i in range(n_shards)]

    worker = recognizer_worker if args.mode == "recognizer" else detector_worker
    with Pool(args.workers) as pool:
        for _ in pool.imap_unordered(worker, jobs):
            pass
    print(f"generated {n_shards * args.shard_size} {args.mode} samples across {n_shards} shards")


if __name__ == "__main__":
    main()
