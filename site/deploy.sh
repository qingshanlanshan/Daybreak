#!/usr/bin/env bash
# Publishes the download page, the latest Android release and the latest Mac app to https://daybreak.qshlab.com,
# where the apps check for updates (android.json, mac.json).
#   ./gradlew :androidApp:assembleRelease && NOTES="…" sh mac/build.sh && site/deploy.sh
# Release notes for both come from site/notes.txt. Needs a one-time `npx wrangler@3 login` (done on this Mac).
set -euo pipefail
cd "$(dirname "$0")"
export npm_config_cache="${npm_config_cache:-$PWD/.npm-cache}"
LIMIT=26214400   # Cloudflare's 25 MiB per-file limit
PART=16777216    # parts are 16 MiB
SITE=https://daybreak.qshlab.com
NOTES=$(cat notes.txt 2>/dev/null || true)

# Android
APK=../androidApp/build/outputs/apk/release/androidApp-release.apk
VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' ../androidApp/build.gradle.kts | head -1)
CODE=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' ../androidApp/build.gradle.kts | head -1)
FILE="Daybreak-$VERSION.apk"
SIZE=$(stat -f%z "$APK")
if [ "$SIZE" -gt "$LIMIT" ]; then echo "APK is over 25 MiB" >&2; exit 1; fi
rm -f public/Daybreak-*.apk
cp "$APK" "public/$FILE"
python3 - "$VERSION" "$CODE" "$SITE/$FILE" "$SIZE" "$(shasum -a 256 "$APK" | cut -d' ' -f1)" "$NOTES" > public/android.json <<'PY'
import json, sys
v, code, url, size, sha, notes = sys.argv[1:7]
print(json.dumps({"version": v, "versionCode": int(code), "url": url, "size": int(size), "sha256": sha, "notes": notes}, ensure_ascii=False))
PY

# Mac (built by mac/build.sh into dist/). Big files go up in parts; worker.js joins them on download.
MAC=../dist
rm -f public/Daybreak-mac*
publish_mac() {   # file, content type, download name, cache-control
  local f="$1" name; name=$(basename "$1")
  if [ "$(stat -f%z "$f")" -le "$LIMIT" ]; then cp "$f" public/; return; fi
  split -b "$PART" -a 1 "$f" "public/$name.part"
  python3 - "$name" "$(stat -f%z "$f")" "$2" "$3" "$4" > "public/$name.parts.json" <<'PY'
import glob, json, os, sys
name, size, ctype, download, cache = sys.argv[1:6]
parts = sorted(os.path.basename(p) for p in glob.glob(f"public/{name}.part?"))
print(json.dumps({"parts": parts, "size": int(size), "type": ctype, "download": download, "cache": cache}))
PY
}
V=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MAC/mac.json")
publish_mac "$MAC/Daybreak-mac-$V.zip" application/zip "" "public, max-age=31536000, immutable"
publish_mac "$MAC/Daybreak-mac.dmg" application/x-apple-diskimage Daybreak.dmg "no-cache"
python3 - "$MAC/mac.json" "$NOTES" > public/mac.json <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["notes"] = sys.argv[2]
print(json.dumps(d, ensure_ascii=False))
PY

{ echo "/download /$FILE 302"; echo "/mac /Daybreak-mac.dmg 302"; } > public/_redirects
if find public -mindepth 2 -type f | grep -q .; then echo "public/ must not contain subfolders" >&2; exit 1; fi

npx --yes wrangler@3 deploy
echo "Published Android $VERSION and Mac $V to $SITE"
