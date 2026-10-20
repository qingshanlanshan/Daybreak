package com.qshlab.daybreak.feed

import com.qshlab.daybreak.sources.Mail
import com.qshlab.daybreak.sources.NewsItem
import com.qshlab.daybreak.sources.WeatherReport
import kotlinx.serialization.Serializable

/*
 * The feed's files (all synced, except local/):
 *   feed/config.json        what the feed reads and when it updates (FeedConfig)
 *   feed/profile.md         "About me": what matters to you, what to skip (you and the AI both edit it)
 *   feed/current.json       the latest feed (FeedResult); feed/history/<time>.json the earlier ones
 *   feed/feedback.json      👍/👎 on cards
 *   feed/chat.json          the recent conversation with the assistant
 *   feed/usage/<device>.json   Claude costs, per device (so devices never overwrite each other)
 *   secrets/anthropic-key, secrets/mail/<id>.json   keys and mail accounts
 *   local/collected.json    what this device read last time (raw sources)
 */

@Serializable
data class RssSource(val name: String, val url: String)

@Serializable
data class Schedule(
    val mode: String = "daily",                     // "manual" | "daily" (at times) | "interval"
    val times: List<String> = listOf("06:00"),      // daily: when the feed should be ready
    val everyHours: Int = 4,                        // interval mode
    val quietStart: String = "22:00", val quietEnd: String = "07:00",   // interval mode: no updates in between
    val notify: Boolean = true,
    val quietDevices: List<String> = emptyList(),   // device ids that don't notify (each device has its own switch)
)

@Serializable
data class FeedConfig(
    val weather: Boolean = true,
    val localNews: Boolean = true,
    val email: Boolean = true,
    val calendar: Boolean = true,
    val topics: List<String> = emptyList(),         // news searches (Google News)
    val feeds: List<RssSource> = emptyList(),       // RSS/Atom feeds
    val searches: List<String> = emptyList(),       // asked to Claude's web search each update (costs more)
    val model: String = "claude-sonnet-5-5",
    val schedule: Schedule = Schedule(),
    val outlookClientId: String = "",
)

@Serializable
data class Card(
    val kind: String,           // email | weather | local | news | calendar | task | other
    val title: String,
    val body: String,
    val why: String = "",
    val refs: List<String> = emptyList(),   // ids of the items it's based on (M3, N12, W1, E2, T4, S1)
    val priority: Int = 2,                  // 1 = important
    val links: List<String> = emptyList(),  // filled in from refs: the original articles / emails
)

@Serializable
data class FeedResult(
    val brief: String,
    val cards: List<Card>,
    val generatedAt: Long,
    val device: String = "",
    val model: String = "",
    val costUsd: Double = 0.0,
    val focus: String? = null,      // a one-off focus this version was written with
    val sourcesAt: Long = 0,
)

@Serializable
data class Feedback(val title: String, val kind: String, val verdict: String, val at: Long, val note: String = "")

@Serializable
data class ChatMsg(val role: String, val text: String, val at: Long)

/** What one update read. */
@Serializable
data class Collected(
    val at: Long,
    val weather: List<WeatherReport> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val mail: List<Mail> = emptyList(),
    val events: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val web: String = "",
    val errors: List<String> = emptyList(),
)
