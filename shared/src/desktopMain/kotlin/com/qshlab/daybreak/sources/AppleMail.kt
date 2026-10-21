package com.qshlab.daybreak.sources

import com.qshlab.daybreak.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Mac: reads the inbox of Apple Mail (every account set up there, school Outlook included), through Mail's own
 * scripting — read-only, nothing is marked read or changed. macOS asks once to let Daybreak control Mail.
 * Apple Mail only fetches new mail while it runs, so before a feed update Daybreak opens it in the background
 * (and quits it again afterwards if it wasn't open).
 */
class AppleMail(private val work: File) : DeviceMail {
    override val kind = "applemail"
    override val full = true
    override val label = "Apple Mail"
    @Volatile override var readAt = 0L
        private set

    /** Runs [cmd]; gives up after [seconds] (exit code -1). */
    private fun run(vararg cmd: String, seconds: Long = 60): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }.apply { start() }
        if (!p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(1000); return -1 to out.toString() }
        reader.join(1000)
        return p.exitValue() to out.toString()
    }

    private fun running() = run("pgrep", "-x", "Mail").first == 0

    private val reading = kotlinx.coroutines.sync.Mutex()

    override suspend fun refresh(since: Instant, wake: Boolean) {
        // Already reading (in the background): wait for that reading instead of starting a second one.
        if (!reading.tryLock()) { reading.lock(); reading.unlock(); return }
        try { read(since, wake) } finally { reading.unlock() }
    }

    private suspend fun read(since: Instant, wake: Boolean) = withContext(Dispatchers.IO) {
        val wasRunning = running()
        if (!wasRunning && !wake) return@withContext
        if (!wasRunning) {
            run("open", "-g", "-j", "-a", "Mail")
            var n = 0
            while (!running() && n++ < 40) Thread.sleep(500)
        }
        try {
            // A trivial question first: the first time, macOS asks you whether Daybreak may control Mail, and waits.
            val (probe, probeOut) = run("osascript", "-l", "JavaScript", "-e", "Application('Mail').name()", seconds = 90)
            if (probe != 0) {
                SharedMail.save(kind, true, emptyList(), if (probe == -1) "Waiting for your permission: macOS asks whether Daybreak may control Mail. Click OK, then Read now." else explain(probeOut))
                return@withContext
            }
            val script = File(work, "applemail.js").apply { parentFile.mkdirs(); writeText(SCRIPT) }
            // Keep the last three days (the feed reads at most that far back), so other devices have them too.
            val from = minOf(since, Instant.now().minusSeconds(3 * 86400))
            // Emails read before keep their text: Mail is only asked for the new ones' (fetching text is the slow part).
            val known = SharedMail.mine(kind)?.items.orEmpty().filter { it.url.isNotBlank() }.associateBy { it.url }
            val knownFile = File(work, "applemail-known.txt").apply { writeText(known.keys.joinToString("\n")) }
            val (code, out) = run("osascript", "-l", "JavaScript", script.path, from.toEpochMilli().toString(), "80", if (wasRunning) "2" else "20",
                knownFile.path, seconds = 240)
            if (code != 0) SharedMail.save(kind, true, emptyList(), if (code == -1) "Apple Mail took too long to answer. Try Read now again in a moment." else explain(out))
            else {
                val r = json.decodeFromString<Reading>(out.trim())
                lastTiming = r.ms
                SharedMail.save(kind, true, r.items.map { m -> known[m.url]?.let { k -> m.copy(snippet = k.snippet, bulk = k.bulk, account = k.account) } ?: m })
                readAt = System.currentTimeMillis()
            }
        } finally {
            if (!wasRunning) run("osascript", "-e", "tell application \"Mail\" to quit")
        }
    }

    private fun explain(out: String) = when {
        "-1743" in out || "Not authorized" in out ->
            "Daybreak isn't allowed to read Apple Mail. Allow it in System Settings → Privacy & Security → Automation → Daybreak → Mail."
        "-600" in out || "isn't running" in out -> "Apple Mail didn't open."
        "-1712" in out -> "Apple Mail took too long to answer. Try Read now again in a moment."
        else -> "Couldn't read Apple Mail: " + out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200)
    }

    @kotlinx.serialization.Serializable private class Reading(val items: List<Mail> = emptyList(), val ms: Map<String, Long> = emptyMap())

    companion object {
        /** How long the last reading's steps took (diagnostics). */
        var lastTiming: Map<String, Long> = emptyMap()

        /** JavaScript for Automation: newest inbox messages since argv[0] (ms), at most argv[1], after waiting argv[2] s for new mail. */
        val SCRIPT = """
            ObjC.import("Foundation");
            function run(argv) {
              const since = new Date(Number(argv[0])), limit = Number(argv[1]), wait = Number(argv[2]);
              const knownText = $.NSString.stringWithContentsOfFileEncodingError(argv[3], $.NSUTF8StringEncoding, null);
              const known = new Set(knownText.isNil() ? [] : knownText.js.split("\n"));
              const ms = {}; let t = Date.now();
              const lap = (k) => { const n = Date.now(); ms[k] = n - t; t = n; };
              const Mail = Application("Mail");
              try { Mail.checkForNewMail(); } catch (e) {}
              if (wait > 0) delay(wait);
              lap("check");
              const spec = Mail.inbox.messages.whose({ dateReceived: { _greaterThan: since } });
              const dates = spec.dateReceived();
              lap("find");
              if (!dates.length) return JSON.stringify({ items: [], ms: ms });
              const subjects = spec.subject(), senders = spec.sender(), ids = spec.messageId();
              lap("fields");
              const order = dates.map((d, i) => [d.getTime(), i]).sort((a, b) => b[0] - a[0]).slice(0, limit);
              const out = [];
              let fetched = 0;
              for (const [time, i] of order) {
                const url = ids[i] ? "message://%3C" + encodeURIComponent(ids[i]) + "%3E" : "";
                let body = "", bulk = false, account = "";
                if (!url || !known.has(url)) {
                  // Only for emails not read before (the rest keep what was read then): account, spam, newsletter, text.
                  const m = spec[i];
                  try { account = m.mailbox().account().name(); } catch (e) {}
                  try { bulk = m.junkMailStatus() === true; } catch (e) {}
                  try { if (!bulk) bulk = m.headers.whose({ name: { _equals: "list-unsubscribe" } })().length > 0; } catch (e) {}
                  if (!bulk) { try { body = String(m.content() || "").replace(/\s+/g, " ").trim().slice(0, 1200); } catch (e) {} }
                  fetched++;
                }
                out.push({ account: String(account || ""), from: String(senders[i] || "?"), subject: String(subjects[i] || "(no subject)"),
                           received: time, snippet: body, bulk: bulk, url: url });
              }
              lap("text"); ms.fetched = fetched;
              return JSON.stringify({ items: out, ms: ms });
            }
        """.trimIndent()
    }
}
