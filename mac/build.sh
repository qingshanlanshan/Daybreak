#!/bin/sh
# Builds the Mac app for download and in-app updates:
#   dist/Daybreak-mac.dmg          for the download page (drag to Applications)
#   dist/Daybreak-mac-<v>.zip      what the in-app updater downloads
#   dist/mac.json                  the update feed (version, zip URL, SHA-256)
# Signed with qshlab's own certificate (mac/signing/, not in git; the same one as Folio — back it up), so
# updates keep the app's macOS permissions and the updater can tell a genuine update from a fake one.
# Files over Cloudflare's 25 MiB limit are split into parts by site/deploy.sh and joined again by the site.
set -e
cd "$(dirname "$0")/.."
V=$(cat mac/VERSION)
NOTES=${NOTES:-}
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew -q :desktopApp:createReleaseDistributable
mkdir -p dist && rm -rf dist/Daybreak.app dist/Daybreak-mac*
APP=dist/Daybreak.app
cp -R desktopApp/build/compose/binaries/main-release/app/Daybreak.app "$APP"

P="$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :LSMinimumSystemVersion 13.0" "$P"
/usr/libexec/PlistBuddy -c "Add :NSHumanReadableCopyright string '© 2026 qshlab'" "$P" 2>/dev/null || true

# Sign. codesign only finds identities in the keychain search list, so add the keychain for the build.
. mac/signing/signing.env
KC="$(pwd)/mac/signing/folio-signing.keychain-db"
OLD_LIST=$(security list-keychains -d user | tr -d '"' | xargs)
trap 'security list-keychains -d user -s $OLD_LIST' EXIT
security list-keychains -d user -s $OLD_LIST "$KC"
security unlock-keychain -p "$FOLIO_KEYCHAIN_PASSWORD" "$KC"
ID=$(security find-identity -p codesigning "$KC" | awk '/Folio \(qshlab\)/ {print $2; exit}')
codesign --force --deep --sign "$ID" "$APP" 2>&1 | grep -v "replacing existing signature" || true
codesign --verify --deep --strict "$APP"

ZIP="Daybreak-mac-$V.zip"
ditto -c -k --keepParent "$APP" "dist/$ZIP"
DMG_DIR=$(mktemp -d)
cp -R "$APP" "$DMG_DIR/"
ln -s /Applications "$DMG_DIR/Applications"
hdiutil create -quiet -volname Daybreak -srcfolder "$DMG_DIR" -fs HFS+ -format UDZO "dist/Daybreak-mac.dmg"
rm -rf "$DMG_DIR"
cat > dist/mac.json <<JSON
{
  "version": "$V",
  "url": "${DAYBREAK_DOWNLOAD_BASE:-https://daybreak.qshlab.com}/$ZIP",
  "sha256": "$(shasum -a 256 "dist/$ZIP" | cut -d' ' -f1)",
  "size": $(stat -f %z "dist/$ZIP"),
  "notes": "$NOTES"
}
JSON
ls -lh dist/Daybreak-mac* dist/mac.json | awk '{print $5, $9}'
