#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/android/app/src/main/assets/tessdata"
FAST_URL="https://github.com/tesseract-ocr/tessdata_fast/raw/main"
BEST_URL="https://github.com/tesseract-ocr/tessdata_best/raw/main"

mkdir -p "$ASSETS_DIR"

for entry in "eng $FAST_URL" "rus $BEST_URL"; do
    lang="${entry%% *}"
    src_base="${entry#* }"
    dest="$ASSETS_DIR/$lang.traineddata"
    if [[ -f "$dest" ]]; then
        echo "  ✓ $lang.traineddata already present"
        continue
    fi
    echo "  ↓ downloading $lang.traineddata from $src_base"
    curl -sSL -o "$dest" "$src_base/$lang.traineddata"
done

echo ""
echo "Done. Assets in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
