package com.qshlab.daybreak.desktop.locatecheck

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.desktop.MacPlatform
import com.qshlab.daybreak.sources.Places
import kotlinx.coroutines.runBlocking

/** Development: `./gradlew -q :desktopApp:locatecheck` — where the Mac thinks it is (the city only). */
fun main(): Unit = runBlocking {
    Daybreak.start(MacPlatform())
    Places.locate().onSuccess { println("located: ${it.name}, ${it.country} (approximate=${it.approx})") }.onFailure { println("FAILED: ${it.message}") }
}
