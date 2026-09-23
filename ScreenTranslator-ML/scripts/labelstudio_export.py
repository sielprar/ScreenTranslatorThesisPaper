"""Fetch the current export from Label Studio via its SDK.

Usage:
    LABEL_STUDIO_URL=http://localhost:8080 \\
    LABEL_STUDIO_API_KEY=<token from Account & Settings> \\
    python scripts/labelstudio_export.py --project-id 1 --out data/real_screenshots/labels.json

SDK note: the plan sketch for this script guessed at a
`client.projects.exports.create_snapshot(...)` + manual `.download(...)` call.
The installed `label-studio-sdk` (2.1.1) has no such method — its exports
client instead exposes a high-level `as_json(project_id, ...)` helper that
does the right thing for either server edition: Community edition exports
synchronously (one `download_sync` call), Enterprise edition requires
create-snapshot -> poll -> convert -> download, and `as_json` handles both
transparently, returning already-parsed JSON. We use that helper instead of
reimplementing the create/poll/download dance by hand. Verified by reading
`label_studio_sdk/projects/exports/client_ext.py` and `raw_client.py` in the
installed package. If a future SDK version renames this again, re-check with:
    python -c "import label_studio_sdk; help(label_studio_sdk.LabelStudio)"
"""
from __future__ import annotations
import argparse
import json
import os
import sys
from pathlib import Path
import httpx
from label_studio_sdk.client import LabelStudio
from label_studio_sdk.core.api_error import ApiError


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--project-id", type=int, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()

    url = os.environ.get("LABEL_STUDIO_URL")
    key = os.environ.get("LABEL_STUDIO_API_KEY")
    if not url or not key:
        print("Set LABEL_STUDIO_URL and LABEL_STUDIO_API_KEY env vars.", file=sys.stderr)
        sys.exit(2)

    client = LabelStudio(base_url=url, api_key=key)
    try:
        # `finished: "only"` restricts the export to tasks with at least one
        # completed annotation (Label Studio's `is_labeled` flag) — skips
        # any screenshots still mid-labeling.
        parsed = client.projects.exports.as_json(
            project_id=args.project_id,
            create_kwargs={"task_filter_options": {"finished": "only"}},
        )
    except (ApiError, httpx.HTTPError) as e:
        # ApiError: SDK-recognized failure (bad token, unknown project id,
        # server-side error). httpx.HTTPError: transport-level failure — the
        # most likely one on a first run is "forgot to `label-studio start`"
        # (connection refused) or a wrong LABEL_STUDIO_URL.
        print(f"ERROR: export failed for project {args.project_id}: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(parsed, ensure_ascii=False), encoding="utf-8")
    print(f"exported {len(parsed)} rows -> {args.out}")


if __name__ == "__main__":
    main()
