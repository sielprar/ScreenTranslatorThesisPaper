import json
from pathlib import Path

from screentranslator_ml.data.real import parse_labelstudio_export


def test_real_dataset_parses_labelstudio_export(tmp_path: Path):
    export = tmp_path / "export.json"
    export.write_text(json.dumps([{
        "data": {"image": "/data/upload/settings/foo.png"},
        "annotations": [{
            "result": [
                {
                    "value": {
                        "points": [[10, 20], [110, 20], [110, 40], [10, 40]],
                        "polygonlabels": ["text"],
                    },
                    "type": "polygonlabels",
                    "id": "r1",
                },
                {
                    "value": {"text": ["Настройки"]},
                    "type": "textarea",
                    "id": "r1",
                },
            ],
        }],
    }]))
    records = parse_labelstudio_export(export)
    assert len(records) == 1
    r = records[0]
    assert r.image_name.endswith("foo.png")
    assert r.polygons[0].text == "Настройки"
    assert r.polygons[0].points[0] == (10, 20)


def test_real_dataset_drops_unpaired_results(tmp_path: Path):
    """A polygon with no matching text, or a text with no matching polygon,
    can't produce a valid training pair — the parser drops it silently
    rather than crashing on a partial annotation."""
    export = tmp_path / "export.json"
    export.write_text(json.dumps([{
        "data": {"image": "/data/upload/settings/bar.png"},
        "annotations": [{
            "result": [
                {
                    "value": {
                        "points": [[0, 0], [10, 0], [10, 10], [0, 10]],
                        "polygonlabels": ["text"],
                    },
                    "type": "polygonlabels",
                    "id": "unpaired-polygon",
                },
                {
                    "value": {"text": ["orphan text"]},
                    "type": "textarea",
                    "id": "unpaired-text",
                },
                {
                    "value": {
                        "points": [[20, 20], [30, 20], [30, 30], [20, 30]],
                        "polygonlabels": ["text"],
                    },
                    "type": "polygonlabels",
                    "id": "r2",
                },
                {
                    "value": {"text": ["paired"]},
                    "type": "textarea",
                    "id": "r2",
                },
            ],
        }],
    }]))
    records = parse_labelstudio_export(export)
    assert len(records) == 1
    assert len(records[0].polygons) == 1
    assert records[0].polygons[0].text == "paired"
