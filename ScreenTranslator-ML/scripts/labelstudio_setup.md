# Label Studio setup for the real-screenshot corpus

Two evenings of manual work: (1) get Label Studio running locally, (2) label ~500 polygons + transcriptions.

## 1. Install and start

```bash
pip install label-studio    # already in pyproject.toml deps
label-studio start
```

Opens at http://localhost:8080. Sign up on first run (any email/pass, stored locally).

## 2. Create the project

- **Project name:** `ScreenTranslator real corpus`
- **Data type:** images
- **Label config** (paste in the "Labeling Setup" step):

```xml
<View>
  <Image name="image" value="$image"/>
  <PolygonLabels name="labels" toName="image">
    <Label value="text" background="#ff0000"/>
  </PolygonLabels>
  <TextArea name="transcription" toName="image"
            perRegion="true"
            required="true"
            placeholder="Enter the text shown in this polygon"
            editable="true"
            maxSubmissions="1"/>
</View>
```

The `perRegion="true"` on `TextArea` is what links each transcription to its polygon by shared `id` — the parser at `src/screentranslator_ml/data/real.py` relies on that.

## 3. Import images

Drag the contents of `data/real_screenshots/*/` into the Label Studio import area, or use its bulk-import URL. All ~500 images across the 5 category subdirs.

## 4. Label

For each image, for each Cyrillic text region:
1. Click the **Polygon** tool → draw 4-8 points tracing the text bounding box (word or line — pick one convention and stick with it; recommend **word-level** to match ML Kit's default).
2. In the region's inspector panel, type the transcription in the `TextArea`.
3. Ctrl+Enter to submit the region.

Skip logo images, emoji, and non-Cyrillic text (routed to ML Kit at runtime).

## 5. Export

- Projects → your project → **Export** → **JSON** format.
- Save to `data/real_screenshots/labels.json`.

Verify:

```bash
python -c "from screentranslator_ml.data.real import parse_labelstudio_export; \
  from pathlib import Path; \
  recs = parse_labelstudio_export(Path('data/real_screenshots/labels.json')); \
  print(f'{len(recs)} images, {sum(len(r.polygons) for r in recs)} polygons')"
```

Expected: 400-500 images, 5000+ polygons.

## 6. Optional: scripted re-export

Once you have an API token (Account & Settings → Access Token), `scripts/labelstudio_export.py` re-dumps the export without going through the UI each time — useful if you label in several sittings and want a fresh `labels.json` without re-clicking through Export each time. See that script's docstring for usage.
