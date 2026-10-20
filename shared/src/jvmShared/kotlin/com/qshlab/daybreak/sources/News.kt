package com.qshlab.daybreak.sources

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class NewsItem(val title: String, val source: String, val url: String, val published: Long, val summary: String = "", val via: String = "")

/** News from RSS/Atom feeds, including Google News searches (local news for a place, or a topic). */
object News {
    /** Google News search, last day, in the place's country edition when known. */
    fun googleSearch(query: String, countryCode: String = ""): String {
        val cc = countryCode.uppercase().ifBlank { "US" }
        return "https://news.google.com/rss/search?q=${enc("$query when:1d")}&hl=en-$cc&gl=$cc&ceid=$cc:en"
    }

    private fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull() ?: 0
    }

    private fun strip(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()

    /** Items of an RSS or Atom feed. [via]: what this feed is for ("Singapore", "AI research", "The Verge"). */
    fun feed(url: String, via: String, limit: Int = 30): List<NewsItem> {
        val text = Http.text(url)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(text.byteInputStream())
        fun Element.child(tag: String) = getElementsByTagName(tag).let { n -> (0 until n.length).map(n::item).filterIsInstance<Element>().firstOrNull() }
        val items = doc.getElementsByTagName("item").let { n -> (0 until n.length).map(n::item) }.filterIsInstance<Element>()
        val entries = doc.getElementsByTagName("entry").let { n -> (0 until n.length).map(n::item) }.filterIsInstance<Element>()
        val feedTitle = doc.documentElement.child("title")?.textContent?.trim().orEmpty()
        val out = items.map { e ->
            val title = e.child("title")?.textContent?.trim().orEmpty()
            val src = e.child("source")?.textContent?.trim()
            // Google News titles end in " - Source"
            val (t, s) = if (src != null && title.endsWith(" - $src")) title.removeSuffix(" - $src") to src else title to (src ?: feedTitle)
            NewsItem(t, s, e.child("link")?.textContent?.trim().orEmpty(), time(e.child("pubDate")?.textContent ?: e.child("dc:date")?.textContent),
                strip(e.child("description")?.textContent.orEmpty()).take(400).let { d -> if (d.startsWith(t)) "" else d }, via)
        } + entries.map { e ->
            val link = e.getElementsByTagName("link").let { n -> (0 until n.length).map(n::item) }.filterIsInstance<Element>()
                .firstOrNull { it.getAttribute("rel").isBlank() || it.getAttribute("rel") == "alternate" }?.getAttribute("href").orEmpty()
            NewsItem(e.child("title")?.textContent?.trim().orEmpty(), feedTitle, link, time(e.child("updated")?.textContent ?: e.child("published")?.textContent),
                strip((e.child("summary") ?: e.child("content"))?.textContent.orEmpty()).take(400), via)
        }
        return out.filter { it.title.isNotBlank() }.sortedByDescending { it.published }.take(limit)
    }
}
