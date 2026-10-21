# Daybreak

A personal assistant for Android and Mac: a calendar with tasks, and an AI feed of what matters to you today
(your email, weather, local news, calendar), personalized by talking to it. Everything syncs between your
devices through your own Cloudflare R2 storage, end-to-end encrypted.

Published at https://daybreak.qshlab.com (Android + Mac, in-app updates).

## Code

Kotlin + Compose Multiplatform, one code base:

- `shared/` everything: data, sync (`sync/`), updates, screens (`ui/`). Code lives in the `jvmShared`
  source set (both targets run on the JVM); `androidMain` / `desktopMain` hold the few platform bits.
- `androidApp/` the Android shell (Application, Activity, manifest, QR scanner).
- `desktopApp/` the Mac shell (`Main.kt`). Dev tools: `./gradlew :desktopApp:snapshot` renders screens to
  PNGs; `:desktopApp:synccheck` drives sync from the command line.
- `mac/build.sh` builds, signs (qshlab certificate in `mac/signing/`, not in git) and packages the Mac app.

```bash
./gradlew :desktopApp:run              # Mac app from source
./gradlew :androidApp:assembleDebug    # Android debug APK
sh mac/build.sh                        # signed Daybreak.app, dmg, update zip, mac.json in dist/
```

Release: bump `versionCode`/`versionName` in `androidApp/build.gradle.kts` and `mac/VERSION`, write `site/notes.txt`,
then `./gradlew :androidApp:assembleRelease && sh mac/build.sh && site/deploy.sh`. The Mac files are over
Cloudflare's 25 MiB per-file limit, so `deploy.sh` uploads them in 16 MiB parts and `site/worker.js` joins them on
download. `./gradlew :desktopApp:updatecheck -Papp=<copy of an older Daybreak.app> -Pversion=<its version>` runs the
real Mac updater against the live site.

Data: `~/Library/Application Support/Daybreak` on the Mac, the app's private storage on Android. Everything
there syncs except `.sync/` and `local/` (this device's own state).

Signing keys are not in git: `keystore/` + `keystore.properties` (Android) and `mac/signing/` (Mac).
Backups in `~/Documents/Signing keys/`.
