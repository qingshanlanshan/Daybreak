package com.qshlab.daybreak.sources

import com.qshlab.daybreak.data.json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    fun text(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", "Daybreak/1.0 (+https://daybreak.qshlab.com)")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} from ${r.request.url.host}")
            return r.body.string()
        }
    }

    fun jsonOf(url: String, headers: Map<String, String> = emptyMap()): JsonElement = json.parseToJsonElement(text(url, headers))
}

fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
