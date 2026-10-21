package com.qshlab.daybreak.desktop

import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.update.MacUpdater
import com.qshlab.daybreak.update.Release
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URL

/**
 * Development only: `./gradlew -q :desktopApp:updatecheck -Papp=<some copy of Daybreak.app> -Pversion=<its version>`
 * runs the real Mac updater against the live feed: download, signature check, swap, reopen. This JVM stands in for
 * the running app, so the swap happens once it quits.
 */
fun main(args: Array<String>) {
    val app = File(args[0]); val version = args[1]
    val updater = MacUpdater(app, version, File(app.parentFile, "work"))
    val r = json.decodeFromString<Release>(URL(updater.feed).readText())
    println("feed: ${r.version} ${r.url} newer=${updater.isNewer(r)}")
    if (!updater.isNewer(r)) return
    var last = -1
    runBlocking { updater.install(r) { p -> val pct = (p * 100).toInt() / 20 * 20; if (pct != last) { last = pct; println("download $pct%") } } }
}
