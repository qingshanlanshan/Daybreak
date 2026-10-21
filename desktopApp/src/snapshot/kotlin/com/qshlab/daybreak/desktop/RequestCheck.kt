package com.qshlab.daybreak.desktop.requestcheck

import com.qshlab.daybreak.desktop.MacPlatform

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.Platform
import com.qshlab.daybreak.feed.FeedEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Stands in for a phone in tests: the Mac platform, but saying it's Android. */
private class PhoneLike(p: Platform) : Platform by p { override val kind = "android"; override val deviceName = "Test phone" }

/**
 * Development: `./gradlew -q :desktopApp:requestcheck -Phome=<dir> -Pmode=mac|phone` (with a local S3 server and the
 * stand-in Claude). mac: answers feed requests for 4 minutes. phone: presses the update button and reports who wrote it.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val mac = args[0] == "mac"
    Daybreak.start(if (mac) MacPlatform() else PhoneLike(MacPlatform()))
    if (mac) {
        Daybreak.sync.runOnce()
        val job = launch { FeedEngine.answerRequests() }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 240_000) { Daybreak.sync.runOnce(); kotlinx.coroutines.delay(10_000) }
        job.cancel()
    } else {
        val steps = mutableListOf<String>()
        val watch = launch { FeedEngine.state.collect { if (it.step.isNotBlank() && steps.lastOrNull() != it.step) steps += it.step } }
        val t0 = System.currentTimeMillis()
        val r = FeedEngine.update()
        watch.cancel()
        println("steps=$steps")
        r.onSuccess { println("written by ${if (it.device == Daybreak.sync.deviceId) "this phone" else "the other device"} after ${(System.currentTimeMillis() - t0) / 1000}s") }
            .onFailure { println("FAILED: ${it.message}") }
    }
    exitProcess(0)
}
