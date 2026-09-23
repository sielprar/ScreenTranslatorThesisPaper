from pathlib import Path
from screentranslator_ml.data.synthetic import generate_recognizer_batch


def test_generator_produces_expected_batch(tmp_path: Path) -> None:
    out_dir = tmp_path / "batch"
    generate_recognizer_batch(
        out_dir=out_dir,
        n=10,
        corpus=Path("data/corpora/ui_phrases_ru.txt"),
        fonts_dir=Path("data/fonts"),
        seed=0,
    )
    images = list(out_dir.glob("*.png"))
    labels = out_dir / "labels.tsv"
    assert len(images) == 10
    assert labels.exists()
    entries = labels.read_text(encoding="utf-8").strip().splitlines()
    assert len(entries) == 10


def test_detector_generator_writes_polygons(tmp_path: Path) -> None:
    from screentranslator_ml.data.synthetic import generate_detector_batch
    generate_detector_batch(
        out_dir=tmp_path / "det",
        n=5,
        corpus=Path("data/corpora/ui_phrases_ru.txt"),
        fonts_dir=Path("data/fonts"),
        canvas_size=(640, 640),
        seed=0,
    )
    images = list((tmp_path / "det").glob("*.png"))
    labels_path = tmp_path / "det" / "labels.jsonl"
    assert len(images) == 5
    assert labels_path.exists()
    import json
    for line in labels_path.read_text().splitlines():
        rec = json.loads(line)
        assert "image" in rec and "polygons" in rec
        assert len(rec["polygons"]) > 0
        assert all(len(p["quad"]) == 4 for p in rec["polygons"])
