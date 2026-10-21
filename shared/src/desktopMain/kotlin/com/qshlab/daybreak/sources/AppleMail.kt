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

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun running() = run("pgrep", "-x", "Mail").first == 0

    override suspend fun refresh(since: Instant, wake: Boolean) = withContext(Dispatchers.IO) {
        val wasRunning = running()
        if (!wasRunning && !wake) return@withContext
        if (!wasRunning) {
            run("open", "-g", "-j", "-a", "Mail")
            var n = 0
            while (!running() && n++ < 40) Thread.sleep(500)
        }
        try {
            val script = File(work, "applemail.js").apply { parentFile.mkdirs(); writeText(SCRIPT) }
            // Keep the last three days (the feed reads at most that far back), so other devices have them too.
            val from = minOf(since, Instant.now().minusSeconds(3 * 86400))
            val (code, out) = run("osascript", "-l", "JavaScript", script.path, from.toEpochMilli().toString(), "80", if (wasRunning) "4" else "20")
            if (code != 0) SharedMail.save(kind, true, emptyList(), explain(out))
            else SharedMail.save(kind, true, json.decodeFromString<List<Mail>>(out.trim().ifEmpty { "[]" }))
        } finally {
            if (!wasRunning) run("osascript", "-e", "tell application \"Mail\" to quit")
        }
    }

    private fun explain(out: String) = when {
        "-1743" in out || "Not authorized" in out ->
            "Daybreak isn't allowed to read Apple Mail. Allow it in System Settings → Privacy & Security → Automation → Daybreak → Mail."
        "-600" in out || "isn't running" in out -> "Apple Mail didn't open."
        else -> "Couldn't read Apple Mail: " + out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200)
    }

    private companion object {
        /** JavaScript for Automation: newest inbox messages since argv[0] (ms), at most argv[1], after waiting argv[2] s for new mail. */
        val SCRIPT = """
            function run(argv) {
              const since = new Date(Number(argv[0])), limit = Number(argv[1]), wait = Number(argv[2]);
              const Mail = Application("Mail");
              try { Mail.checkForNewMail(); } catch (e) {}
              if (wait > 0) delay(wait);
              const spec = Mail.inbox.messages.whose({ dateReceived: { _greaterThan: since } });
              const dates = spec.dateReceived();
              if (!dates.length) return "[]";
              const subjects = spec.subject(), senders = spec.sender(), ids = spec.messageId();
              let accounts = []; try { accounts = spec.mailbox.account.name(); } catch (e) {}
              let junk = []; try { junk = spec.junkMailStatus(); } catch (e) {}
              const order = dates.map((d, i) => [d.getTime(), i]).sort((a, b) => b[0] - a[0]).slice(0, limit);
              const out = [];
              for (const [t, i] of order) {
                const m = spec[i];
                let body = ""; try { body = String(m.content() || "").replace(/\s+/g, " ").trim().slice(0, 1200); } catch (e) {}
                let bulk = junk[i] === true;
                try { if (!bulk) bulk = m.headers.whose({ name: { _equals: "list-unsubscribe" } })().length > 0; } catch (e) {}
                out.push({ account: String(accounts[i] || ""), from: String(senders[i] || "?"), subject: String(subjects[i] || "(no subject)"),
                           received: t, snippet: bulk ? "" : body, bulk: bulk,
                           url: ids[i] ? "message://%3C" + encodeURIComponent(ids[i]) + "%3E" : "" });
              }
              return JSON.stringify(out);
            }
        """.trimIndent()
    }
}
