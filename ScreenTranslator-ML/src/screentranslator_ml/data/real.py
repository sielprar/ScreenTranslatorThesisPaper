"""Parse a Label Studio JSON export of the real-screenshot corpus into typed
records. Held-out eval data only — never used by training loops."""
from __future__ import annotations
import json
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Polygon:
    text: str
    points: list[tuple[float, float]]


@dataclass
class RealRecord:
    image_name: str
    polygons: list[Polygon]


def parse_labelstudio_export(export_path: Path) -> list[RealRecord]:
    """Read Label Studio's JSON export → list of (image, [polygon, ...]).

    Label Studio pairs each polygon-region + its transcription by shared `id`
    across separate `result` entries (one `polygonlabels` result carries the
    box; one `textarea` result carries the string). We regroup them here.

    Missing halves (a polygon with no text, or a text with no polygon) are
    dropped silently — they can't produce a valid training pair.
    """
    data = json.loads(Path(export_path).read_text(encoding="utf-8"))
    out: list[RealRecord] = []
    for row in data:
        img = row["data"]["image"].rstrip("/")
        img_name = img.rsplit("/", 1)[-1]
        polys_by_id: dict[str, dict] = {}
        for ann in row["annotations"]:
            for r in ann["result"]:
                rid = r["id"]
                slot = polys_by_id.setdefault(rid, {})
                if r["type"] == "polygonlabels":
                    slot["points"] = [(x, y) for x, y in r["value"]["points"]]
                elif r["type"] == "textarea":
                    slot["text"] = r["value"]["text"][0]
        polys = [
            Polygon(text=v["text"], points=v["points"])
            for v in polys_by_id.values()
            if "text" in v and "points" in v
        ]
        out.append(RealRecord(image_name=img_name, polygons=polys))
    return out
