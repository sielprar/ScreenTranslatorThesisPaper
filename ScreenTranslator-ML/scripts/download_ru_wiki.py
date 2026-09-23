"""Download the Russian Wikipedia snapshot via HuggingFace Datasets, filter
to short OCR-realistic lines (5-80 chars, all characters in our recognizer's
Cyrillic vocab, no wiki markup), save to data/corpora/ru_wiki_full.txt.
Sample of 5000 lines committed to ru_wiki_sample.txt for repo browsing
without pulling the full corpus.

The vocab-membership check is critical: without it, ~28% of raw Wikipedia
lines contain Latin loanwords, non-breaking spaces, em-dashes, etc. that
would KeyError inside Vocab.encode() at training time (Task 5's dataloader).
"""
from __future__ import annotations
import re
import sys
from pathlib import Path
from datasets import load_dataset

OUT = Path("data/corpora/ru_wiki_full.txt")
SAMPLE = Path("data/corpora/ru_wiki_sample.txt")
VOCAB_FILE = Path("data/corpora/vocab_cyrillic.txt")
TARGET_LINES = 1_000_000
MIN_LEN, MAX_LEN = 5, 80
CYRILLIC_RE = re.compile(r"[А-Яа-яЁё]")
MARKUP_RE = re.compile(r"[|{}\[\]<>]")


def _load_vocab_chars() -> set[str]:
    """All allowed single characters (i.e., not the '<blank>' sentinel)."""
    return {c for c in VOCAB_FILE.read_text(encoding="utf-8").splitlines() if c != "<blank>"}


def looks_useful(line: str, vocab: set[str]) -> bool:
    line = line.strip()
    if not (MIN_LEN <= len(line) <= MAX_LEN):
        return False
    if not CYRILLIC_RE.search(line):
        return False
    if MARKUP_RE.search(line):
        return False
    if any(c not in vocab for c in line):
        return False
    return True


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    vocab = _load_vocab_chars()
    print(f"streaming wikimedia/wikipedia 20231101.ru; target {TARGET_LINES} lines", file=sys.stderr)
    ds = load_dataset("wikimedia/wikipedia", "20231101.ru", split="train", streaming=True)
    written = 0
    with OUT.open("w", encoding="utf-8") as f:
        for row in ds:
            for line in row["text"].splitlines():
                if not looks_useful(line, vocab):
                    continue
                f.write(line.strip() + "\n")
                written += 1
                if written % 10000 == 0:
                    print(f"  {written} lines", file=sys.stderr)
                if written >= TARGET_LINES:
                    break
            if written >= TARGET_LINES:
                break
    lines = OUT.read_text(encoding="utf-8").splitlines()
    SAMPLE.write_text("\n".join(lines[:5000]), encoding="utf-8")
    print(f"wrote {written} lines to {OUT}; committed sample = {len(lines[:5000])}")


if __name__ == "__main__":
    main()
