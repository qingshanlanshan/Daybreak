package com.qshlab.daybreak.update

import java.io.File
import kotlin.system.exitProcess

/**
 * Mac: download the new Daybreak.app (zip), check it's signed by the same certificate as this one (so a
 * tampered download is refused and macOS keeps the app's permissions), swap it in once this app has quit,
 * and reopen it. [app] is this Daybreak.app, or null when running from source (no updates then).
 */
class MacUpdater(private val app: File?, private val version: String, private val work: File) : Updater {
    override val feed = "$SITE/mac.json"
    override fun isNewer(r: Release) = app != null && versionNewer(r.version, version)

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    override suspend fun install(r: Release, progress: (Float) -> Unit) {
        val app = app ?: throw IllegalStateException("Running from source: update with git pull.")
        if (!app.parentFile.canWrite()) throw IllegalStateException("Daybreak can't replace itself in ${app.parent}. Move it to Applications.")
        val stage = File(work, "update").apply { deleteRecursively(); mkdirs() }
        val zip = download(r, File(stage, "Daybreak.zip"), progress)
        check(run("ditto", "-x", "-k", zip.path, stage.path).first == 0) { "Couldn't unpack the update." }
        val new = File(stage, "Daybreak.app")
        val req = run("codesign", "-d", "-r-", app.path).second.lineSequence().firstOrNull { it.startsWith("designated => ") }
            ?.removePrefix("designated => ") ?: throw IllegalStateException("This copy of Daybreak isn't signed, so it can't update itself.")
        if (run("codesign", "--verify", "--deep", "--strict", "-R=$req", new.path).first != 0)
            throw IllegalStateException("The update isn't signed by Daybreak's publisher, so it wasn't installed.")
        fun q(f: File) = "'" + f.path.replace("'", "'\\''") + "'"
        val pid = ProcessHandle.current().pid()
        val script = File(stage, "swap.sh")
        script.writeText("""
            #!/bin/sh
            # Wait for Daybreak to quit, put the new app in place (keeping the old one if anything fails), reopen.
            while kill -0 $pid 2>/dev/null; do sleep 0.2; done
            APP=${q(app)}; NEW=${q(new)}; OLD=${q(File(stage, "Daybreak-old.app"))}
            if mv "${'$'}APP" "${'$'}OLD" && mv "${'$'}NEW" "${'$'}APP"; then rm -rf "${'$'}OLD"; else [ -d "${'$'}APP" ] || mv "${'$'}OLD" "${'$'}APP"; fi
            open "${'$'}APP"
        """.trimIndent() + "\n")
        ProcessBuilder("/bin/sh", script.path).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        exitProcess(0)
    }
}
