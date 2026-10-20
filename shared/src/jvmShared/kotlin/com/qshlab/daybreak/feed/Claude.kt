package com.qshlab.daybreak.feed

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.ownerOnly
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeAtomic
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.YearMonth

class ClaudeError(message: String) : Exception(message)

data class ClaudeModel(val id: String, val name: String, val inPrice: Double, val outPrice: Double, val note: String)

val CLAUDE_MODELS = listOf(
    ClaudeModel("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 10.00, "Good and affordable"),
    ClaudeModel("claude-opus-5-5", "Claude Opus 5.5", 4.00, 20.00, "The most thoughtful, twice the cost"),
    ClaudeModel("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50, "Very cheap, simpler"),
)

@Serializable
data class Month(val requests: Int = 0, val input: Long = 0, val output: Long = 0, val searches: Int = 0, val usd: Double = 0.0)

@Serializable
data class Usage(val months: Map<String, Month> = emptyMap())

data class Reply(val text: String, val costUsd: Double)

/** Claude for the feed and the assistant. The key lives in secrets/ (synced to your devices, encrypted). */
object Claude {
    private val home get() = Daybreak.platform.home
    private val keyFile get() = File(home, "secrets/anthropic-key")
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    var key: String?
        get() = keyFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        set(v) {
            if (v.isNullOrBlank()) keyFile.delete() else { keyFile.writeAtomic(v.trim()); keyFile.ownerOnly() }
            Changes.bump(); Daybreak.sync.kickSoon()
        }

    val keyHint get() = key?.let { "…" + it.takeLast(4) }

    fun model(id: String) = CLAUDE_MODELS.firstOrNull { it.id == id } ?: CLAUDE_MODELS[0]

    @Synchronized
    private fun client(): AnthropicClient {
        val k = key ?: throw ClaudeError("Add your Claude API key in Settings first.")
        if (client == null || clientKey != k) {
            // DAYBREAK_CLAUDE_URL: development only, to test against a local stand-in.
            client = AnthropicOkHttpClient.builder().apiKey(k).maxRetries(2).apply { System.getenv("DAYBREAK_CLAUDE_URL")?.let { baseUrl(it) } }.build()
            clientKey = k
        }
        return client!!
    }

    // --- costs, one file per device

    private val usageFile get() = File(home, "feed/usage/${Daybreak.sync.config.value?.device ?: "this"}.json")

    fun thisMonth(): Month {
        val m = YearMonth.now().toString()
        return File(home, "feed/usage").listFiles().orEmpty().mapNotNull { it.readJson<Usage>()?.months?.get(m) }
            .fold(Month()) { a, b -> Month(a.requests + b.requests, a.input + b.input, a.output + b.output, a.searches + b.searches, a.usd + b.usd) }
    }

    @Synchronized
    private fun record(model: ClaudeModel, inp: Long, cacheRead: Long, cacheWrite: Long, out: Long, searches: Int): Double {
        // Cached input is billed at 10% to read and 125% to write.
        val cost = (inp + cacheRead * 0.1 + cacheWrite * 1.25) / 1e6 * model.inPrice + out / 1e6 * model.outPrice + searches * 0.01
        val month = YearMonth.now().toString()
        val u = usageFile.readJson<Usage>() ?: Usage()
        val cur = u.months[month] ?: Month()
        usageFile.writeJson(u.copy(months = u.months + (month to Month(cur.requests + 1, cur.input + inp + cacheRead + cacheWrite, cur.output + out,
            cur.searches + searches, Math.round((cur.usd + cost) * 10000) / 10000.0))))
        return cost
    }

    /**
     * One request (streamed, so long answers don't time out). [stable] is cached between updates (instructions,
     * profile); [user] is this request's material. With [schema], the answer is JSON in that shape. With
     * [webSearches] > 0, Claude may search the web that many times.
     */
    fun ask(modelId: String, stable: String, user: String, schema: Map<String, Any>? = null, effort: String = "low",
            maxTokens: Long = 16000, webSearches: Int = 0): Reply {
        val m = model(modelId)
        val out = BetaOutputConfig.builder().effort(when (effort) { "high" -> BetaOutputConfig.Effort.HIGH; "medium" -> BetaOutputConfig.Effort.MEDIUM; else -> BetaOutputConfig.Effort.LOW })
        if (schema != null) out.format(BetaJsonOutputFormat.builder().schema(BetaJsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) }).build()).build())
        val b = MessageCreateParams.builder()
            .model(m.id)
            .maxTokens(maxTokens)
            .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(stable)
                .cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build()).build()))
            .addUserMessage(user)
            .outputConfig(out.build())
        if (webSearches > 0) b.addTool(BetaWebSearchTool20260209.builder().maxUses(webSearches.toLong()).build())
        if (m.id != "claude-haiku-5-5") b.addBeta("server-side-fallback-2026-07-01").fallbacks(BetaFallbacksParam.ofDefault())
        val acc = BetaMessageAccumulator.create()
        try {
            client().beta().messages().createStreaming(b.build()).use { resp -> val it = resp.stream().iterator(); while (it.hasNext()) acc.accumulate(it.next()) }
        } catch (e: UnauthorizedException) { throw ClaudeError("Claude rejected the API key. Check it in Settings.")
        } catch (e: PermissionDeniedException) { throw ClaudeError("This API key isn't allowed to use ${m.name}.")
        } catch (e: RateLimitException) { throw ClaudeError("Claude is rate-limiting this key right now. Try again in a minute.")
        } catch (e: AnthropicIoException) { throw ClaudeError("Can't reach Claude (offline?). Try again when connected.")
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 400 && e.body().toString().contains("credit", ignoreCase = true)) throw ClaudeError("Your Anthropic account is out of credits.")
            throw ClaudeError("Claude API error ${e.statusCode()}.")
        }
        val msg = acc.message()
        val u = msg.usage()
        val searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L).toInt()
        val cost = record(model(msg.model().asString()), u.inputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L), u.outputTokens(), searches)
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw ClaudeError("Claude declined this request.")
        return Reply(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""), cost)
    }

    /** A tiny request to check the key. */
    fun test(modelId: String): String = ask(modelId, "Reply with the single word: ready", "Are you there?", maxTokens = 2000).text.trim()
}
