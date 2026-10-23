package com.qshlab.daybreak.feed

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.data.ownerOnly
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeAtomic
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class AiError(message: String) : Exception(message)

/** Who writes the feed. [webSearch]: whether it can research the web (Settings → web searches). */
data class Provider(val id: String, val name: String, val company: String, val keyPrefix: String, val keyUrl: String, val webSearch: Boolean)

val PROVIDERS = listOf(
    Provider("anthropic", "Claude", "Anthropic", "sk-ant-", "https://console.anthropic.com/settings/keys", webSearch = true),
    Provider("openai", "GPT", "OpenAI", "sk-", "https://platform.openai.com/api-keys", webSearch = true),
    Provider("deepseek", "DeepSeek", "DeepSeek", "sk-", "https://platform.deepseek.com/api_keys", webSearch = false),
)

/** Prices in USD per million tokens (DeepSeek: the peak price; it's half off-peak). */
data class AiModel(val provider: String, val id: String, val name: String, val inPrice: Double, val cachedPrice: Double, val outPrice: Double, val note: String)

val AI_MODELS = listOf(
    AiModel("anthropic", "claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 0.20, 10.00, "Good and affordable"),
    AiModel("anthropic", "claude-opus-5-5", "Claude Opus 5.5", 4.00, 0.40, 20.00, "The most thoughtful, twice the cost"),
    AiModel("anthropic", "claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.01, 0.50, "Very cheap, simpler"),
    AiModel("openai", "gpt-6.1-sol", "GPT-6.1 Sol", 2.00, 0.10, 10.00, "Good and affordable"),
    AiModel("openai", "gpt-6-astra", "GPT-6 Astra", 10.00, 1.00, 50.00, "The most capable, five times the cost"),
    AiModel("openai", "gpt-6-luna", "GPT-6 Luna", 0.10, 0.01, 0.50, "Very cheap, simpler"),
    AiModel("deepseek", "deepseek-flash", "DeepSeek V4.1 Flash", 0.30, 0.006, 1.20, "Very cheap"),
    AiModel("deepseek", "deepseek-v4-pro", "DeepSeek V4 Pro", 1.32, 0.044, 3.96, "Stronger, still cheap"),
)

@Serializable
data class Month(val requests: Int = 0, val input: Long = 0, val output: Long = 0, val searches: Int = 0, val usd: Double = 0.0)

@Serializable
data class Usage(val months: Map<String, Month> = emptyMap())

data class Reply(val text: String, val costUsd: Double)

/**
 * The AI that writes the feed and answers you: Claude, GPT or DeepSeek, chosen in Settings → AI. Each has its
 * own key in secrets/ (synced to your devices, encrypted). Requests go straight from the device to that company.
 */
object Ai {
    private val home get() = Daybreak.platform.home

    fun provider(id: String = FeedEngine.config().provider) = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]
    fun models(provider: String) = AI_MODELS.filter { it.provider == provider }
    /** The model in use: the configured one if it belongs to the chosen provider, else that provider's first. */
    fun model(cfg: FeedConfig = FeedEngine.config()): AiModel =
        AI_MODELS.firstOrNull { it.id == cfg.model && it.provider == provider(cfg.provider).id } ?: models(provider(cfg.provider).id).first()

    private fun keyFile(provider: String) = File(home, "secrets/$provider-key")
    fun key(provider: String = provider().id): String? = keyFile(provider).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    fun setKey(provider: String, v: String?) {
        val f = keyFile(provider)
        if (v.isNullOrBlank()) f.delete() else { f.writeAtomic(v.trim()); f.ownerOnly() }
        Changes.bump(); Daybreak.sync.kickSoon()
    }
    fun keyHint(provider: String = provider().id) = key(provider)?.let { "…" + it.takeLast(4) }
    /** Whether the chosen provider has a key. */
    val ready: Boolean get() = key() != null
    /** "Add a … key" message for the chosen provider. */
    fun noKey() = AiError("Add your ${provider().name} API key in Settings → AI to get your feed.")

    // --- costs, one file per device (so devices never overwrite each other)

    private val usageFile get() = File(home, "feed/usage/${Daybreak.sync.config.value?.device ?: "this"}.json")

    fun thisMonth(): Month {
        val m = YearMonth.now().toString()
        return File(home, "feed/usage").listFiles().orEmpty().mapNotNull { it.readJson<Usage>()?.months?.get(m) }
            .fold(Month()) { a, b -> Month(a.requests + b.requests, a.input + b.input, a.output + b.output, a.searches + b.searches, a.usd + b.usd) }
    }

    @Synchronized
    fun record(input: Long, output: Long, searches: Int, cost: Double): Double {
        val month = YearMonth.now().toString()
        val u = usageFile.readJson<Usage>() ?: Usage()
        val cur = u.months[month] ?: Month()
        usageFile.writeJson(u.copy(months = u.months + (month to Month(cur.requests + 1, cur.input + input, cur.output + output,
            cur.searches + searches, Math.round((cur.usd + cost) * 10000) / 10000.0))))
        return cost
    }

    /** Rough cost of one feed update (reading ~15k tokens, writing ~2.5k). */
    fun estimate(m: AiModel): Double = 15_000 / 1e6 * m.inPrice * (if (m.provider == "deepseek") 0.75 else 1.0) + 2_500 / 1e6 * m.outPrice

    /**
     * One request to the chosen AI. [stable]: instructions that stay the same between requests (cached where the
     * provider can); [user]: this request's material. With [schema], the answer is JSON in that shape. With
     * [webSearches] > 0, it may search the web (Claude and GPT).
     */
    fun ask(stable: String, user: String, schema: Map<String, Any>? = null, effort: String = "low", maxTokens: Long = 16000, webSearches: Int = 0): Reply {
        val cfg = FeedEngine.config()
        val m = model(cfg)
        return when (m.provider) {
            "anthropic" -> Claude.ask(m, key("anthropic") ?: throw noKey(), stable, user, schema, effort, maxTokens, webSearches)
            else -> OpenAiCompatible.ask(m, key(m.provider) ?: throw noKey(), stable, user, schema, effort, maxTokens, webSearches)
        }
    }

    /** A tiny request to check the key. */
    fun test(): String = ask("Reply with the single word: ready", "Are you there?", maxTokens = 2000).text.trim()
}

/** GPT (OpenAI) and DeepSeek: both speak OpenAI's chat completions format. */
internal object OpenAiCompatible {
    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build() }
    private fun base(provider: String) = System.getenv("DAYBREAK_AI_URL")       // development: a local stand-in
        ?: if (provider == "deepseek") "https://api.deepseek.com" else "https://api.openai.com/v1"

    fun ask(m: AiModel, key: String, stable: String, user: String, schema: Map<String, Any>?, effort: String, maxTokens: Long, webSearches: Int): Reply {
        if (webSearches > 0) {
            if (m.provider != "openai") throw AiError("${Ai.provider(m.provider).name} can't search the web. Web searches need Claude or GPT.")
            return openAiWebSearch(m, key, stable, user, maxTokens)
        }
        val deepseek = m.provider == "deepseek"
        // DeepSeek's JSON mode doesn't take a schema: it's described in the prompt instead (which must say "json").
        val system = if (schema != null && deepseek)
            stable + "\n\nAnswer with a single json object only, no other text, matching this JSON schema:\n" + toJson(schema) else stable
        fun body(withEffort: Boolean) = buildJsonObject {
            put("model", m.id)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            if (deepseek) put("max_tokens", maxTokens.coerceAtMost(32_000)) else put("max_completion_tokens", maxTokens)
            if (withEffort && !deepseek) put("reasoning_effort", if (effort == "low") "low" else "medium")
            if (schema != null) put("response_format", if (deepseek) buildJsonObject { put("type", "json_object") } else buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", "answer"); put("strict", true); put("schema", toJson(schema)) })
            })
        }
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val r = try { post(m, key, "${base(m.provider)}/chat/completions", body(withEffort = true)) }
                catch (e: AiError) {
                    // Some models don't take reasoning_effort: ask again without it.
                    if ("reasoning_effort" in (e.message ?: "")) post(m, key, "${base(m.provider)}/chat/completions", body(withEffort = false)) else throw e
                }
            val text = r["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val cost = recordUsage(m, r["usage"]?.jsonObject, 0)
            if (r["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "content_filter")
                throw AiError("${Ai.provider(m.provider).name} declined this request.")
            // JSON that doesn't parse (DeepSeek occasionally returns nothing): one more try.
            if (schema == null || runCatching { json.parseToJsonElement(text).jsonObject }.isSuccess) return Reply(text, cost)
            lastError = AiError("${Ai.provider(m.provider).name} returned an answer Daybreak couldn't read.")
            if (attempt == 1) throw lastError!!
        }
        throw lastError ?: AiError("No answer.")
    }

    /** GPT with web search: the Responses API and its web_search tool. */
    private fun openAiWebSearch(m: AiModel, key: String, stable: String, user: String, maxTokens: Long): Reply {
        val r = post(m, key, "${base(m.provider)}/responses", buildJsonObject {
            put("model", m.id); put("instructions", stable); put("input", user); put("max_output_tokens", maxTokens)
            put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        })
        val out = r["output"]?.jsonArray.orEmpty()
        val searches = out.count { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "web_search_call" }
        val text = out.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
            .mapNotNull { c -> c.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        return Reply(text, recordUsage(m, r["usage"]?.jsonObject, searches))
    }

    private fun post(m: AiModel, key: String, url: String, body: JsonObject): JsonObject {
        val p = Ai.provider(m.provider)
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val (code, text) = try { client.newCall(req).execute().use { it.code to it.body.string() } }
            catch (e: java.io.IOException) { throw AiError("Can't reach ${p.name} (offline?). Try again when connected.") }
        if (code in 200..299) return json.parseToJsonElement(text).jsonObject
        val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        throw AiError(when (code) {
            401 -> "${p.name} rejected the API key. Check it in Settings."
            402 -> "Your ${p.company} account is out of credit."
            403 -> "This API key isn't allowed to use ${m.name}."
            404 -> "${p.name} doesn't know the model ${m.id}: $msg"
            429 -> if ("quota" in msg.lowercase() || "balance" in msg.lowercase()) "Your ${p.company} account is out of credit." else "${p.name} is rate-limiting this key right now. Try again in a minute."
            else -> "${p.name} API error $code" + (if (msg.isNotBlank()) ": ${msg.take(200)}" else "")
        })
    }

    /** Costs: OpenAI bills cached input at its cached price; DeepSeek splits cache hits and misses, and halves off-peak. */
    private fun recordUsage(m: AiModel, u: JsonObject?, searches: Int): Double {
        fun n(vararg path: String): Long {
            var e: JsonElement? = u
            for (k in path) e = (e as? JsonObject)?.get(k)
            return (e as? JsonPrimitive)?.longOrNull ?: 0
        }
        val output = n("completion_tokens").takeIf { it > 0 } ?: n("output_tokens")
        val (fresh, cached) = if (m.provider == "deepseek") n("prompt_cache_miss_tokens") to n("prompt_cache_hit_tokens") else {
            val total = n("prompt_tokens").takeIf { it > 0 } ?: n("input_tokens")
            val c = n("prompt_tokens_details", "cached_tokens").takeIf { it > 0 } ?: n("input_tokens_details", "cached_tokens")
            (total - c) to c
        }
        val factor = if (m.provider == "deepseek" && !deepSeekPeak()) 0.5 else 1.0
        val cost = ((fresh * m.inPrice + cached * m.cachedPrice + output * m.outPrice) / 1e6) * factor + searches * 0.01
        return Ai.record(fresh + cached, output, searches, cost)
    }

    /** DeepSeek's peak hours: 01:00–04:00 and 06:00–10:00 UTC on weekdays. */
    private fun deepSeekPeak(): Boolean {
        val now = java.time.ZonedDateTime.now(ZoneOffset.UTC)
        if (now.dayOfWeek.value >= 6) return false
        val t = now.toLocalTime()
        return (t >= LocalTime.of(1, 0) && t < LocalTime.of(4, 0)) || (t >= LocalTime.of(6, 0) && t < LocalTime.of(10, 0))
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
