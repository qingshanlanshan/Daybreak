package com.qshlab.daybreak.desktop

import com.qshlab.daybreak.Daybreak
import kotlin.system.exitProcess

/** Development: drive sync from the command line against a local S3 server (see the synccheck task). */
fun main(args: Array<String>) {
    Daybreak.start(MacPlatform())
    val s = Daybreak.sync
    when (args[0]) {
        "connect" -> s.connect("http://127.0.0.1:9100", "daybreak-test", "test", "test")
        "join" -> s.join(args[1])
        "code" -> println(s.code())
        "off" -> s.disconnect()
        "run" -> { s.runOnce(); val st = s.status.value; println("error=${st.error} sent=${st.sent} received=${st.received} devices=${s.devices().map { it.name }}") }
    }
    exitProcess(0)
}
