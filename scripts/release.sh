#!/usr/bin/env bash
# Publishes a DCrux release on GitHub. This is the whole update flow: the app checks
# the latest GitHub release, so publishing here is what puts an update on every phone.
# The APK lives only on GitHub; nothing is stored on any server of ours.
#
#   1. Bump versionCode and versionName in app/build.gradle.kts, commit and push.
#   2. Write release-notes/<version>.md (shown on GitHub and inside the app).
#   3. GITHUB_PAT=... scripts/release.sh
#
# The public APK is built with no key inside (-PnoBakedKey=true). If a Claude key is
# found in it anyway, raw or masked, the script stops before anything is uploaded.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="thegeekygamechanger/DCrux"
: "${GITHUB_PAT:?Set GITHUB_PAT to a token that can create releases on $REPO}"
VERSION=$(grep -oP 'testVersionName"\) as String\?\) \?: "\K[^"]+' app/build.gradle.kts)
NOTES="release-notes/$VERSION.md"
[ -f "$NOTES" ] || { echo "Write $NOTES first."; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first."; exit 1; }
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{u}')" ] || { echo "Push your commits first."; exit 1; }

echo "Building DCrux $VERSION (public, no key inside)…"
./gradlew :app:assembleRelease -PnoBakedKey=true -q
APK="app/build/outputs/apk/release/app-release.apk"
OUT="${TMPDIR:-/tmp}/DCrux-$VERSION.apk"
cp "$APK" "$OUT"

python3 - "$OUT" <<'PY'
import base64, hashlib, os, sys, zipfile
blob = b"".join(zipfile.ZipFile(sys.argv[1]).read(n) for n in zipfile.ZipFile(sys.argv[1]).namelist())
key = ""
if os.path.exists("secrets.properties"):
    for line in open("secrets.properties"):
        if line.startswith("CLAUDE_API_KEY="):
            key = line.split("=", 1)[1].strip()
if key:
    b = key.encode(); pad = bytearray(len(b)); block = hashlib.sha256(b"dcrux-pad").digest()
    for i in range(len(b)):
        if i % 32 == 0 and i > 0: block = hashlib.sha256(block).digest()
        pad[i] = block[i % 32]
    masked = base64.b64encode(bytes(x ^ y for x, y in zip(b, pad)))
    if b in blob or masked in blob:
        sys.exit("STOP: the API key is inside this APK. Nothing was published.")
if b"sk-ant-api" in blob:
    sys.exit("STOP: something that looks like an API key is inside this APK. Nothing was published.")
print("Key check passed: no API key inside.")
PY

api() { curl -sS -H "Authorization: Bearer $GITHUB_PAT" -H "Accept: application/vnd.github+json" "$@"; }
BODY=$(python3 -c 'import json,sys; print(json.dumps({"tag_name": "v"+sys.argv[1], "target_commitish": sys.argv[2], "name": "DCrux "+sys.argv[1], "body": open(sys.argv[3]).read()}))' "$VERSION" "$(git rev-parse HEAD)" "$NOTES")
ID=$(api -X POST "https://api.github.com/repos/$REPO/releases" -d "$BODY" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("id") or sys.exit(d.get("message") or "could not create the release"))')
api -X POST -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$OUT" \
  "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=DCrux-$VERSION.apk" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Published:", d.get("browser_download_url") or d.get("message"))'
rm -f "$OUT"
