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

/** Claude (Anthropic): the Java SDK, streamed, with the stable instructions cached for an hour. */
internal object Claude {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun client(k: String): AnthropicClient {
        if (client == null || clientKey != k) {
            // DAYBREAK_CLAUDE_URL: development only, to test against a local stand-in.
            client = AnthropicOkHttpClient.builder().apiKey(k).maxRetries(2).apply { System.getenv("DAYBREAK_CLAUDE_URL")?.let { baseUrl(it) } }.build()
            clientKey = k
        }
        return client!!
    }

    /**
     * One request (streamed, so long answers don't time out). [stable] is cached between updates (instructions,
     * profile); [user] is this request's material. With [schema], the answer is JSON in that shape. With
     * [webSearches] > 0, Claude may search the web that many times.
     */
    fun ask(m: AiModel, key: String, stable: String, user: String, schema: Map<String, Any>? = null, effort: String = "low",
            maxTokens: Long = 16000, webSearches: Int = 0): Reply {
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
            client(key).beta().messages().createStreaming(b.build()).use { resp -> val it = resp.stream().iterator(); while (it.hasNext()) acc.accumulate(it.next()) }
        } catch (e: UnauthorizedException) { throw AiError("Claude rejected the API key. Check it in Settings.")
        } catch (e: PermissionDeniedException) { throw AiError("This API key isn't allowed to use ${m.name}.")
        } catch (e: RateLimitException) { throw AiError("Claude is rate-limiting this key right now. Try again in a minute.")
        } catch (e: AnthropicIoException) { throw AiError("Can't reach Claude (offline?). Try again when connected.")
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 400 && e.body().toString().contains("credit", ignoreCase = true)) throw AiError("Your Anthropic account is out of credits.")
            throw AiError("Claude API error ${e.statusCode()}.")
        }
        val msg = acc.message()
        val u = msg.usage()
        val searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L).toInt()
        // The model that answered (a fallback may differ); cached input costs 10% to read and 125% to write.
        val used = AI_MODELS.firstOrNull { it.id == msg.model().asString() } ?: m
        val inp = u.inputTokens(); val read = u.cacheReadInputTokens().orElse(0L); val write = u.cacheCreationInputTokens().orElse(0L)
        val cost = Ai.record(inp + read + write, u.outputTokens(), searches,
            (inp + read * 0.1 + write * 1.25) / 1e6 * used.inPrice + u.outputTokens() / 1e6 * used.outPrice + searches * 0.01)
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw AiError("Claude declined this request.")
        return Reply(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""), cost)
    }

}
