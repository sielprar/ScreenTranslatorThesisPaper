"""Home-grown Pillow-based synthetic text-crop generator for the recognizer.

Skips trdg (yanked on PyPI, unfixed upstream) since Cyrillic is LTR-only and
we do not need arabic-reshaper / python-bidi. Renders each line at a random
font + size onto a 32-tall crop, applies mild Gaussian noise + optional blur,
and optionally composites over a random UI-screenshot patch for background
variety.
"""
from __future__ import annotations
import random
from pathlib import Path
from typing import Iterable
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

TARGET_H = 32
MARGIN_X = 6
MARGIN_Y = 3


def _list_fonts(fonts_dir: Path) -> list[str]:
    return sorted(str(p) for p in fonts_dir.glob("*.ttf")) + sorted(str(p) for p in fonts_dir.glob("*.otf"))


def _sample_strings(corpus: Path, n: int, rng: random.Random) -> Iterable[str]:
    lines = [line for line in corpus.read_text(encoding="utf-8").splitlines() if line.strip()]
    for _ in range(n):
        yield rng.choice(lines)


def _render_one(text: str, font_path: str, rng: random.Random) -> Image.Image:
    # Pick a text pixel size that renders to ~TARGET_H after margins.
    size = rng.choice([22, 26, 30, 34])
    font = ImageFont.truetype(font_path, size)
    bbox = font.getbbox(text)
    w = int(bbox[2] - bbox[0] + MARGIN_X * 2)
    h = int(bbox[3] - bbox[1] + MARGIN_Y * 2)
    bg_gray = rng.randint(220, 255)
    img = Image.new("L", (w, h), color=bg_gray)
    draw = ImageDraw.Draw(img)
    text_gray = rng.randint(0, 60)
    draw.text((MARGIN_X - bbox[0], MARGIN_Y - bbox[1]), text, fill=text_gray, font=font)
    # Resize to TARGET_H preserving aspect.
    new_w = max(TARGET_H, int(w * TARGET_H / h))
    img = img.resize((new_w, TARGET_H), Image.Resampling.BILINEAR)
    # Light Gaussian noise.
    arr = np.array(img, dtype=np.int16)
    noise = np.random.RandomState(rng.randint(0, 2**31 - 1)).normal(0, 4, arr.shape).astype(np.int16)
    arr = np.clip(arr + noise, 0, 255).astype(np.uint8)
    img = Image.fromarray(arr, mode="L")
    if rng.random() < 0.35:
        img = img.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.3, 0.8)))
    return img


def generate_recognizer_batch(
    out_dir: Path,
    n: int,
    corpus: Path,
    fonts_dir: Path,
    seed: int = 0,
) -> None:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    fonts = _list_fonts(fonts_dir)
    if not fonts:
        raise RuntimeError(f"No fonts found in {fonts_dir}; run scripts/download_fonts.py")

    labels = []
    for i, text in enumerate(_sample_strings(corpus, n, rng)):
        font_path = rng.choice(fonts)
        img = _render_one(text, font_path, rng)
        img.save(out_dir / f"{i:07d}.png")
        labels.append(f"{i:07d}.png\t{text}")
    (out_dir / "labels.tsv").write_text("\n".join(labels), encoding="utf-8")


def generate_detector_batch(
    out_dir: Path,
    n: int,
    corpus: Path,
    fonts_dir: Path,
    canvas_size: tuple[int, int],
    seed: int = 0,
) -> None:
    """Render N page-shaped images (canvas_size wide × tall) with 5-25 text
    lines at varying font/size/position, plus a JSONL labels file recording
    each line's quadrilateral in pixel coordinates.

    Used by the DBNet detector to learn text-region localization. Backgrounds
    are plain light-gray canvases for now; the plan flags UI-screenshot
    compositing as a follow-up in Phase A.
    """
    import json
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    lines = [line for line in corpus.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines:
        raise RuntimeError(f"Empty corpus {corpus}")
    fonts = _list_fonts(fonts_dir)
    if not fonts:
        raise RuntimeError(f"No fonts in {fonts_dir}")
    W, H = canvas_size
    labels_path = out_dir / "labels.jsonl"
    with labels_path.open("w", encoding="utf-8") as fp:
        for i in range(n):
            bg = (rng.randint(220, 255),) * 3
            img = Image.new("RGB", (W, H), color=bg)
            draw = ImageDraw.Draw(img)
            polygons: list[dict] = []
            n_lines = rng.randint(5, 25)
            y = rng.randint(20, 80)
            for _ in range(n_lines):
                text = rng.choice(lines)
                font_path = rng.choice(fonts)
                size = rng.choice([18, 22, 28, 36])
                font = ImageFont.truetype(font_path, size)
                bbox = draw.textbbox((0, 0), text, font=font)
                w = int(bbox[2] - bbox[0])
                h = int(bbox[3] - bbox[1])
                x = rng.randint(20, max(21, W - w - 20))
                if y + h > H - 20:
                    break
                draw.text((x, y), text, fill=(30, 30, 30), font=font)
                polygons.append({
                    "text": text,
                    "quad": [[x, y], [x + w, y], [x + w, y + h], [x, y + h]],
                })
                y += h + rng.randint(4, 24)
            name = f"{i:07d}.png"
            img.save(out_dir / name)
            fp.write(json.dumps({"image": name, "polygons": polygons}, ensure_ascii=False) + "\n")
