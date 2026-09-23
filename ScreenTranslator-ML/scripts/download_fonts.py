"""Download all fonts listed in data/corpora/fonts_list.txt into data/fonts/.

Skips already-downloaded files. Verifies each font has Cyrillic coverage by
checking that the codepoints for 'А' and 'я' are present in the font's cmap.
"""
from __future__ import annotations
import sys
from pathlib import Path
from urllib.parse import urlparse
import requests
from fontTools.ttLib import TTFont


FONTS_DIR = Path("data/fonts")
LIST_FILE = Path("data/corpora/fonts_list.txt")


def has_cyrillic(path: Path) -> bool:
    try:
        f = TTFont(str(path))
        cmap = f.getBestCmap()
        return ord("А") in cmap and ord("я") in cmap
    except Exception:
        return False


def download(url: str) -> Path | None:
    name = Path(urlparse(url).path).name.replace("%5B", "[").replace("%5D", "]").replace("%2C", ",")
    out = FONTS_DIR / name
    if out.exists():
        return out
    r = requests.get(url, timeout=60)
    if r.status_code != 200:
        print(f"WARN {url} → {r.status_code}", file=sys.stderr)
        return None
    out.write_bytes(r.content)
    return out


def main() -> None:
    FONTS_DIR.mkdir(parents=True, exist_ok=True)
    urls = [
        line.strip() for line in LIST_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    ok, skipped, bad = 0, 0, 0
    for url in urls:
        path = download(url)
        if path is None:
            bad += 1
            continue
        if has_cyrillic(path):
            ok += 1
        else:
            print(f"WARN {path.name} has no Cyrillic coverage; deleting", file=sys.stderr)
            path.unlink()
            skipped += 1
    print(f"ok={ok} skipped={skipped} bad={bad}")


if __name__ == "__main__":
    main()
