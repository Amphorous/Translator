import json
from pathlib import Path

import requests

PROJECT_ID = "Dimbreath%2Fturnbasedgamedata"
BRANCH = "main"
DIRECTORY = "TextMap"

TARGET_DIR = Path("src/main/resources/textMaps")
TARGET_DIR.mkdir(parents=True, exist_ok=True)

METADATA_FILE = TARGET_DIR / ".sync_metadata.json"

TREE_URL = (
    f"https://gitlab.com/api/v4/projects/{PROJECT_ID}/repository/tree"
)

HEADERS = {
    "User-Agent": "Mozilla/5.0"
}


def load_metadata():
    if METADATA_FILE.exists():
        try:
            return json.loads(
                METADATA_FILE.read_text(encoding="utf-8")
            )
        except Exception:
            return {}
    return {}


def save_metadata(metadata):
    METADATA_FILE.write_text(
        json.dumps(metadata, indent=2),
        encoding="utf-8"
    )


metadata = load_metadata()

print("Fetching TextMap file list...")

session = requests.Session()

tree_response = session.get(
    TREE_URL,
    params={
        "path": DIRECTORY,
        "ref": BRANCH,
        "per_page": 100
    },
    headers=HEADERS,
    timeout=60
)

tree_response.raise_for_status()

files = tree_response.json()

new_metadata = {}

downloaded = 0
updated = 0
unchanged = 0

for item in files:

    if item["type"] != "blob":
        continue

    filename = item["name"]

    if "Main" in filename:
        continue

    if not filename.endswith(".json"):
        continue

    # Blob SHA from GitLab tree response
    remote_blob = item["id"]

    new_metadata[filename] = remote_blob

    local_blob = metadata.get(filename)

    if local_blob == remote_blob:
        print(f"Up-to-date: {filename}")
        unchanged += 1
        continue

    raw_url = (
        f"https://gitlab.com/Dimbreath/turnbasedgamedata/-/raw/"
        f"{BRANCH}/{DIRECTORY}/{filename}"
    )

    print(f"Downloading: {filename}")

    response = session.get(
        raw_url,
        headers=HEADERS,
        timeout=60
    )

    response.raise_for_status()

    # Safety check: make sure GitLab didn't return HTML
    content_type = response.headers.get(
        "Content-Type",
        ""
    ).lower()

    if "html" in content_type:
        raise RuntimeError(
            f"GitLab returned HTML instead of JSON for {filename}"
        )

    (TARGET_DIR / filename).write_bytes(
        response.content
    )

    if local_blob is None:
        downloaded += 1
    else:
        updated += 1

# Remove deleted files
for local_file in TARGET_DIR.glob("*.json"):

    if "Main" in local_file.name:
        continue

    if local_file.name not in new_metadata:
        print(f"Removing obsolete file: {local_file.name}")
        local_file.unlink()

save_metadata(new_metadata)

print()
print("Sync complete")
print(f"New files: {downloaded}")
print(f"Updated files: {updated}")
print(f"Unchanged files: {unchanged}")