package com.qshlab.daybreak.desktop

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.Event
import com.qshlab.daybreak.cal.Task
import com.qshlab.daybreak.cal.newId
import com.qshlab.daybreak.cal.str
import com.qshlab.daybreak.feed.Ai
import com.qshlab.daybreak.feed.FeedEngine
import com.qshlab.daybreak.sources.MailAccount
import com.qshlab.daybreak.sources.MailAccounts
import com.qshlab.daybreak.sources.Places
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.system.exitProcess

/** Development: the whole feed flow against real weather/news and local stand-ins for mail and Claude. */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>): Unit = runBlocking {
    Daybreak.start(MacPlatform())
    Ai.setKey("anthropic", "sk-ant-test")
    Places.save(Places.search("Singapore").take(1))
    if (MailAccounts.list().isEmpty()) MailAccounts.save(MailAccount("test", "imap", "me@example.com", host = "127.0.0.1", port = 3143, password = "apppass"))
    val today = LocalDate.now()
    if (Cal.events(Cal.DEFAULT_CALENDAR).isEmpty()) {
        Cal.saveEvent(Cal.DEFAULT_CALENDAR, newId(), Event("Dentist", today.atTime(16, 30).str(), today.atTime(17, 15).str(), tz = "Asia/Singapore", location = "Clementi"))
        Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Submit conference paper", due = today.plusDays(2).str()))
        Cal.saveTask(Cal.DEFAULT_LIST, newId(), Task("Buy milk"))
    }
    val r = FeedEngine.refresh()
    r.onFailure { println("REFRESH FAILED: ${it.message}"); it.printStackTrace() }
    val c = FeedEngine.collected()!!
    println("collected: weather=${c.weather.map { it.place + " " + it.now }} news=${c.news.size} mail=${c.mail.map { (if (it.bulk) "[bulk] " else "") + it.subject }}")
    println("events=${c.events} tasks=${c.tasks}")
    println("errors=${c.errors}")
    r.onSuccess { f -> println("feed: ${f.brief}\n  cards=${f.cards.map { it.title + " refs=" + it.refs + " place=" + it.place + " task=" + it.task_title + "@" + it.task_due + " sources=" + it.sources.map { s -> s.label } }}\n  cost=$${"%.4f".format(f.costUsd)}") }
    val say = FeedEngine.say("stop showing sports and follow AI research news")
    say.onFailure { println("SAY FAILED: ${it.message}") }
    say.onSuccess { out ->
        println("reply: ${out.reply}")
        println("topics now: ${FeedEngine.config().topics}; profile: ${FeedEngine.profile().trim().replace("\n", " | ")}")
        println("tasks now: ${com.qshlab.daybreak.cal.Cal.tasks().map { it.task.title + "@" + it.task.due }}")
        if (out.rewrite) FeedEngine.refresh(out.focus, reuse = true).onSuccess { println("rewritten: ${it.brief}") }.onFailure { println("REWRITE FAILED: ${it.message}") }
    }
    println("usage this month: ${Ai.thisMonth()}")
    println("next update: ${FeedEngine.nextSlot()}")
    exitProcess(0)
}
