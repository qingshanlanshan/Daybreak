# Daybreak

A personal assistant for Android and Mac: a calendar with tasks, and an AI feed of what matters to you today
(your email, weather, local news, calendar), personalized by talking to it. Everything syncs between your
devices through your own Cloudflare R2 storage, end-to-end encrypted.

Status: phase 1 (foundations) done. Next: calendar & tasks, sources, AI feed, release.

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

Data: `~/Library/Application Support/Daybreak` on the Mac, the app's private storage on Android. Everything
there syncs except `.sync/` and `local/` (this device's own state).

Signing keys are not in git: `keystore/` + `keystore.properties` (Android) and `mac/signing/` (Mac).
Backups in `~/Documents/Signing keys/`.
