# Daybreak

A calendar with tasks, and a personal feed: an AI of your choice (Claude, GPT or DeepSeek) reads your weather, local news, unread email and
calendar, and tells you what matters to you today. Tell it what to focus on, and it learns. For Mac and
Android, kept in step through your own storage, end-to-end encrypted.

**Download: <https://daybreak.qshlab.com>** · Mac (Apple Silicon, macOS 13+) and Android (10+). Both apps
update themselves.

## Features

**Calendar and tasks**
- Day, week and month views; repeating events; reminders; import, export and subscribe to `.ics` calendars.
- Tasks in lists, with or without a due date; notes and reminders without a date go to a default list.
- Tap a day in the month to add a task due then. On Android, a home-screen month widget does the same.

**The feed**
- A short brief and a few cards: emails from real people, deadlines, today's events, weather that changes
  plans (rain, haze, heat), city news that affects daily life (transport, road works, health advisories),
  then news on topics you choose. One card per subject; cards about a place are grouped under it.
- Talk to it: "skip promotions", "I care about my supervisor's emails and MRT disruptions", "write in
  Chinese", or "remind me to call mum tomorrow at 6" (adds a task). 👍 / 👎 on any card, with an optional
  reason, steers later feeds.
- A card can suggest a task ("Submit the ethics form · due Fri") to add with one tap.
- Each card names its sources; they open the article, or the email in Apple Mail.
- You choose the AI and model (Claude, GPT or DeepSeek, with your own API key), when it updates (each
  update costs a little) and which devices notify you. Web searches need Claude or GPT.

**Email, without signing in anywhere**
- **Mac**: reads the inbox of every account in Apple Mail (school or work Outlook included), read-only.
- **Android**: reads the new-mail notifications of the Outlook and Gmail apps (sender, subject, preview).
- Only unread email from the last two days is used. Whole emails (from the Mac, through sync) replace
  notification previews of the same email.
- Optional: IMAP with an app password (Gmail and others), or Outlook through Microsoft sign-in.

**Places**: your current location (or, where the device can't say, the city of your internet connection)
plus any cities you add; weather and air quality from Open-Meteo, local news from Google News.

**Sync** (optional): your own Cloudflare R2 bucket (free tier). With it, your Mac writes the scheduled feeds
while it's awake (it has Apple Mail), your phone asks the Mac for a fresh feed when you press update, and
every device gets a notification when a new feed arrives.

## Privacy

- No Daybreak server and no account. Your data lives on your devices, and in your own bucket if you turn
  on sync, encrypted on the device first (AES-256-GCM, key shared only through the pairing code).
- Writing the feed sends text to the AI you chose (Anthropic, OpenAI or DeepSeek), with your own key: weather,
  headlines, your events and tasks, and the subject and first lines of unread emails. Nothing is sent without a key. Anthropic
  doesn't train on API data.
- Email is read-only; Daybreak never sends, deletes or marks anything.

## Code

Kotlin and Compose Multiplatform, one code base for both apps:

```
shared/       almost everything (the jvmShared source set; both targets run on the JVM)
  cal/          calendars, events, recurrence, .ics, tasks
  feed/         the feed: collecting sources, the AI calls (Ai.kt: Claude via the Anthropic Java SDK; GPT and
                DeepSeek over their chat completions API), schedule
  sources/      weather, news, places, mail (IMAP, Outlook, Apple Mail, notifications)
  sync/         encrypted sync over S3 (Cloudflare R2), leases, device heartbeat
  update/       in-app updates (Android installer; Mac: verify signature, swap, relaunch)
  ui/           screens
androidApp/   the Android shell: activity, alarms, background jobs, notification reader, widget (Glance)
desktopApp/   the Mac shell (Main.kt) and dev tools in src/snapshot
mac/          Mac packaging: build.sh (jpackage, signing), a small Swift helper for notifications
site/         the download page and update feeds (Cloudflare Workers; worker.js joins files sent in parts)
```

```bash
./gradlew :desktopApp:run              # Mac app from source (Apple Mail is only read by the packaged app)
./gradlew :androidApp:assembleDebug    # Android debug APK
sh mac/build.sh                        # signed Daybreak.app, dmg, update zip, mac.json in dist/
```

Needs JDK 17 and the Android SDK.

Dev tools (`desktopApp/src/snapshot`), all against throwaway data: `:desktopApp:snapshot` renders screens
to PNGs; `calcheck` tests the calendar; `feedcheck` and `aicheck` run whole feed updates against stand-ins for Claude and for GPT / DeepSeek
(`DAYBREAK_CLAUDE_URL`); `synccheck` and `requestcheck` drive sync between two test devices against a local
S3 server; `mailmergecheck`, `locatecheck` and `updatecheck` test those parts.

### Release (maintainer)

Bump `versionCode` / `versionName` in `androidApp/build.gradle.kts` and `mac/VERSION`, write
`site/notes.txt`, then `./gradlew :androidApp:assembleRelease && sh mac/build.sh && site/deploy.sh`. The Mac
files are over Cloudflare's 25 MiB per-file limit, so `deploy.sh` uploads them in 16 MiB parts and
`site/worker.js` joins them on download. Signing keys (`keystore/`, `keystore.properties`, `mac/signing/`)
are not in git.

## Data

`~/Library/Application Support/Daybreak` on the Mac, the app's own storage on Android: plain JSON
files. Everything there syncs except `.sync/` and `local/` (each device's own state).

## License

MIT ([LICENSE](LICENSE)).

---

More from qshlab: <https://qshlab.com>
