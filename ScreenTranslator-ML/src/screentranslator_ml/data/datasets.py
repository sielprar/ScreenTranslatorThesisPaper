"""PyTorch datasets for the recognizer + detector.

RecognizerDataset yields (image_tensor, ids_tensor) pairs suitable for CTC
training: image is (1, H, W) grayscale, ids is a 1-D int64 tensor of length
== len(text).

DetectorDataset yields (image_tensor, prob_map, thresh_map, mask) tuples for
DBNet training. prob_map / thresh_map / mask live at input_size / 4 spatial
resolution. See docstring on `_dbnet_targets` for the current simplified
target-generation and the follow-up needed in Task 21.
"""
from __future__ import annotations
import json
from pathlib import Path
from PIL import Image
import numpy as np
import torch
from torch.utils.data import Dataset

from .vocab import Vocab


class RecognizerDataset(Dataset):
    def __init__(
        self,
        shard_dirs: list[Path],
        vocab: Vocab,
        image_height: int = 32,
        max_width: int = 320,
    ):
        self.vocab = vocab
        self.h = image_height
        self.max_w = max_width
        self.entries: list[tuple[Path, str]] = []
        for shard in shard_dirs:
            labels = (shard / "labels.tsv").read_text(encoding="utf-8").splitlines()
            for line in labels:
                name, text = line.split("\t", 1)
                self.entries.append((shard / name, text))

    def __len__(self) -> int:
        return len(self.entries)

    def __getitem__(self, i: int) -> tuple[torch.Tensor, torch.Tensor]:
        path, text = self.entries[i]
        img = Image.open(path).convert("L")
        w, h = img.size
        new_w = min(self.max_w, max(self.h, int(w * self.h / h)))
        img = img.resize((new_w, self.h))
        arr = np.array(img, dtype=np.float32) / 255.0
        tensor = torch.from_numpy(arr).unsqueeze(0)  # (1, H, W)
        ids = torch.tensor(self.vocab.encode(text), dtype=torch.long)
        return tensor, ids


class DetectorDataset(Dataset):
    def __init__(self, dirs: list[Path], input_size: int = 640):
        self.input_size = input_size
        self.records: list[tuple[Path, list[dict]]] = []
        for d in dirs:
            for line in (d / "labels.jsonl").read_text(encoding="utf-8").splitlines():
                rec = json.loads(line)
                self.records.append((d / rec["image"], rec["polygons"]))

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, i: int):
        path, polys = self.records[i]
        img = Image.open(path).convert("RGB").resize((self.input_size, self.input_size))
        arr = np.array(img, dtype=np.float32) / 255.0
        img_t = torch.from_numpy(arr).permute(2, 0, 1)  # (3, H, W)
        prob, thresh, mask = _dbnet_targets(polys, self.input_size, out=self.input_size // 4)
        return img_t, prob, thresh, mask


def _dbnet_targets(polygons: list[dict], input_size: int, out: int):
    """Placeholder target generation for the DBNet detector.

    Fills polygon interiors as the probability map; sets threshold map to a
    scaled copy of the probability. Task 21 replaces this with proper Vatti
    clipper shrink (for the tight probability region) + expand (for the
    threshold ring) per the original DBNet paper. Current implementation is
    sufficient for the Task 9 dataset test only.
    """
    from PIL import ImageDraw
    scale = out / input_size
    prob_img = Image.new("L", (out, out), 0)
    d = ImageDraw.Draw(prob_img)
    for p in polygons:
        pts = [(x * scale, y * scale) for (x, y) in p["quad"]]
        d.polygon(pts, fill=255)
    prob = np.array(prob_img, dtype=np.float32) / 255.0
    thresh = prob * 0.7
    mask = np.ones_like(prob)
    return torch.from_numpy(prob), torch.from_numpy(thresh), torch.from_numpy(mask)
