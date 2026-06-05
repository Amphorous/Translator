import hashlib
import json
from pathlib import Path
from urllib.parse import quote

import requests

ASSET_DIR = Path("src/main/resources/assets")
ASSET_DIR.mkdir(parents=True, exist_ok=True)

METADATA_FILE = ASSET_DIR / ".asset_metadata.json"

GITHUB_FILES = {
    "hsr.json":
        "https://raw.githubusercontent.com/EnkaNetwork/API-docs/master/store/hsr/hsr.json",

    "relics.json":
        "https://raw.githubusercontent.com/EnkaNetwork/API-docs/master/store/hsr/relics.json"
}

GITLAB_PROJECT = "Dimbreath%2Fturnbasedgamedata"
GITLAB_BRANCH = "main"

HEADERS = {
    "User-Agent": "Mozilla/5.0"
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_metadata():
    if METADATA_FILE.exists():
        try:
            return json.loads(METADATA_FILE.read_text("utf-8"))
        except Exception:
            return {}
    return {}


def save_metadata(metadata):
    METADATA_FILE.write_text(
        json.dumps(metadata, indent=2),
        encoding="utf-8"
    )


def download_github_file(session, filename, url):
    response = session.get(
        url,
        headers=HEADERS,
        timeout=60
    )

    response.raise_for_status()

    return response.content


def download_gitlab_file(session, repo_path):
    encoded_path = quote(repo_path, safe="")

    url = (
        f"https://gitlab.com/api/v4/projects/"
        f"{GITLAB_PROJECT}/repository/files/"
        f"{encoded_path}/raw"
    )

    response = session.get(
        url,
        params={"ref": GITLAB_BRANCH},
        headers=HEADERS,
        timeout=60
    )

    response.raise_for_status()

    return response.content


def validate_json(content: bytes, filename: str):
    try:
        json.loads(content.decode("utf-8"))
    except Exception as e:
        raise RuntimeError(
            f"{filename} does not contain valid JSON"
        ) from e


metadata = load_metadata()
new_metadata = {}

downloaded = 0
updated = 0
unchanged = 0

session = requests.Session()

print("Syncing asset files...")

#
# GitHub assets
#
for filename, url in GITHUB_FILES.items():

    print(f"Checking {filename}")

    content = download_github_file(
        session,
        filename,
        url
    )

    validate_json(content, filename)

    remote_hash = sha256_bytes(content)
    local_hash = metadata.get(filename)

    if local_hash == remote_hash:
        print(f"Up-to-date: {filename}")
        unchanged += 1
    else:
        (ASSET_DIR / filename).write_bytes(content)

        if local_hash is None:
            print(f"Downloaded: {filename}")
            downloaded += 1
        else:
            print(f"Updated: {filename}")
            updated += 1

    new_metadata[filename] = remote_hash


#
# ItemConfigRelic.json from GitLab
#
gitlab_filename = "ItemConfigRelic.json"

print(f"Checking {gitlab_filename}")

content = download_gitlab_file(
    session,
    "ExcelOutput/ItemConfigRelic.json"
)

validate_json(content, gitlab_filename)

remote_hash = sha256_bytes(content)
local_hash = metadata.get(gitlab_filename)

if local_hash == remote_hash:
    print(f"Up-to-date: {gitlab_filename}")
    unchanged += 1
else:
    (ASSET_DIR / gitlab_filename).write_bytes(content)

    if local_hash is None:
        print(f"Downloaded: {gitlab_filename}")
        downloaded += 1
    else:
        print(f"Updated: {gitlab_filename}")
        updated += 1

new_metadata[gitlab_filename] = remote_hash


#
# Remove obsolete files
#
expected_files = set(new_metadata.keys())

for local_file in ASSET_DIR.glob("*.json"):

    if local_file.name not in expected_files:
        print(f"Removing obsolete file: {local_file.name}")
        local_file.unlink()


save_metadata(new_metadata)

print()
print("Asset sync complete")
print(f"New files: {downloaded}")
print(f"Updated files: {updated}")
print(f"Unchanged files: {unchanged}")