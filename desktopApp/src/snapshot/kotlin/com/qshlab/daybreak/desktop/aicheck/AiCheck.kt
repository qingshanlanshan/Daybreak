package com.qshlab.daybreak.desktop.aicheck

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.desktop.MacPlatform
import com.qshlab.daybreak.feed.Ai
import com.qshlab.daybreak.feed.FeedEngine
import kotlinx.coroutines.runBlocking

/**
 * Development: `./gradlew -q :desktopApp:aicheck -Pprovider=openai|deepseek -Pmodel=<id>` against a local stand-in
 * (DAYBREAK_AI_URL): key test, a whole feed update, and a chat message that adds a task.
 */
fun main(args: Array<String>): Unit = runBlocking {
    Daybreak.start(MacPlatform())
    val (provider, model) = args[0] to args[1]
    FeedEngine.saveConfig(FeedEngine.config().copy(provider = provider, model = model, searches = listOf("road works near Clementi"), email = false))
    Ai.setKey(provider, "sk-test-0000")
    println("model in use: ${Ai.model().name}; test: ${runCatching { Ai.test() }.getOrElse { "FAILED " + it.message }}")
    FeedEngine.refresh().onSuccess { println("feed: ${it.brief.replace("\n", " | ")} · ${it.cards.size} card(s) · model ${it.model} · cost $%.4f".format(it.costUsd)) }
        .onFailure { println("REFRESH FAILED: ${it.message}") }
    println("web notes: " + (FeedEngine.collected()?.web?.take(80) ?: "-") + " | errors: " + FeedEngine.collected()?.errors)
    FeedEngine.say("remind me to call mum tomorrow at 6").onSuccess { println("chat: ${it.reply} tasks=${it.add_tasks.map { t -> t.title + "@" + t.due }}") }.onFailure { println("CHAT FAILED: ${it.message}") }
    println("this month: ${Ai.thisMonth()}")
    kotlin.system.exitProcess(0)
}
