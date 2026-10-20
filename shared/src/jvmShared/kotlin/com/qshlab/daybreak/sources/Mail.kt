package com.qshlab.daybreak.sources

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.data.ownerOnly
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.Properties

/** A mail account, read-only. Kept in secrets/ (synced, encrypted like everything else; never shown again). */
@Serializable
data class MailAccount(
    val id: String, val kind: String,               // "imap" | "outlook"
    val email: String, val name: String = email,
    val host: String = "", val port: Int = 993, val password: String = "",       // imap
    val refreshToken: String = "", val clientId: String = "",                    // outlook
)

@Serializable
data class Mail(val account: String, val from: String, val subject: String, val received: Long, val snippet: String,
                val bulk: Boolean = false, val url: String = "")

object MailAccounts {
    private val dir get() = File(Daybreak.platform.home, "secrets/mail")
    fun list(): List<MailAccount> = dir.listFiles().orEmpty().mapNotNull { it.readJson<MailAccount>() }.sortedBy { it.email }
    fun save(a: MailAccount) { File(dir, "${a.id}.json").also { it.writeJson(a); it.ownerOnly() }; Daybreak.sync.kickSoon() }
    fun delete(id: String) { File(dir, "$id.json").delete(); Daybreak.sync.kickSoon() }

    /** IMAP servers for common providers (they all need an app password, not your normal one). */
    val PRESETS = mapOf("gmail.com" to "imap.gmail.com", "googlemail.com" to "imap.gmail.com", "icloud.com" to "imap.mail.me.com",
        "me.com" to "imap.mail.me.com", "yahoo.com" to "imap.mail.yahoo.com", "qq.com" to "imap.qq.com", "163.com" to "imap.163.com",
        "126.com" to "imap.126.com", "fastmail.com" to "imap.fastmail.com", "outlook.com" to "outlook.office365.com", "hotmail.com" to "outlook.office365.com")
}

/** Reading mail: IMAP (Gmail and others, with an app password) or Microsoft Graph (Outlook). Never changes anything. */
object MailReader {
    fun fetch(a: MailAccount, since: Instant, limit: Int = 60): List<Mail> = if (a.kind == "outlook") Outlook.fetch(a, since, limit) else imap(a, since, limit)

    private fun imap(a: MailAccount, since: Instant, limit: Int): List<Mail> {
        // Plain IMAP only for a server on this machine (development); real accounts always use SSL.
        val proto = if (a.host == "127.0.0.1" || a.host == "localhost") "imap" else "imaps"
        val props = Properties().apply {
            put("mail.store.protocol", proto); put("mail.$proto.host", a.host); put("mail.$proto.port", a.port.toString())
            if (proto == "imaps") put("mail.imaps.ssl.enable", "true")
            put("mail.$proto.connectiontimeout", "15000"); put("mail.$proto.timeout", "30000")
        }
        val session = javax.mail.Session.getInstance(props)
        val store = session.getStore(proto)
        try {
            try { store.connect(a.host, a.port, a.email, a.password) }
            catch (e: javax.mail.AuthenticationFailedException) { throw IllegalStateException("${a.email}: the server refused the login. Use an app password (not your normal password).") }
            val inbox = store.getFolder("INBOX")
            inbox.open(javax.mail.Folder.READ_ONLY)          // read-only: nothing is marked read or changed
            try {
                val msgs = inbox.search(javax.mail.search.ReceivedDateTerm(javax.mail.search.ComparisonTerm.GE, Date.from(since)))
                    .sortedByDescending { it.receivedDate ?: it.sentDate }.take(limit)
                val fp = javax.mail.FetchProfile().apply { add(javax.mail.FetchProfile.Item.ENVELOPE); add("List-Unsubscribe"); add("Precedence") }
                inbox.fetch(msgs.toTypedArray(), fp)
                return msgs.mapNotNull { m ->
                    runCatching {
                        val from = (m.from?.firstOrNull() as? javax.mail.internet.InternetAddress)?.let { it.personal?.let { p -> "$p <${it.address}>" } ?: it.address } ?: "?"
                        val bulk = m.getHeader("List-Unsubscribe") != null || m.getHeader("Precedence")?.any { it.contains("bulk", true) || it.contains("list", true) } == true
                        Mail(a.email, from, m.subject ?: "(no subject)", (m.receivedDate ?: m.sentDate)?.time ?: 0, if (bulk) "" else snippet(m), bulk)
                    }.getOrNull()
                }
            } finally { inbox.close(false) }
        } finally { runCatching { store.close() } }
    }

    /** The first ~1000 characters of the text (plain preferred, else HTML stripped). */
    private fun snippet(p: javax.mail.Part): String {
        fun find(part: javax.mail.Part, html: Boolean): String? = when {
            part.isMimeType(if (html) "text/html" else "text/plain") -> part.content as? String
            part.isMimeType("multipart/*") -> (part.content as javax.mail.Multipart).let { mp -> (0 until mp.count).firstNotNullOfOrNull { find(mp.getBodyPart(it), html) } }
            else -> null
        }
        val text = find(p, false) ?: find(p, true)?.replace(Regex("(?s)<(style|script)[^>]*>.*?</\\1>"), " ")?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")?.replace("&amp;", "&") ?: ""
        return text.replace(Regex("(?m)^>.*$"), "").replace(Regex("\\s+"), " ").trim().take(1000)
    }
}

/**
 * Outlook / Microsoft 365 through Microsoft Graph. Sign-in uses the "device code" flow: the app shows a short
 * code, you enter it at microsoft.com/devicelogin and approve read-only mail access. Needs a client ID from a
 * one-time app registration (Settings → Outlook app ID).
 */
object Outlook {
    private const val AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val SCOPES = "offline_access Mail.Read User.Read"

    @Serializable data class DeviceCode(val user_code: String, val device_code: String, val verification_uri: String, val interval: Int = 5, val expires_in: Int = 900, val message: String = "")

    private fun post(url: String, form: Map<String, String>): kotlinx.serialization.json.JsonObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        Http.client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r -> return json.parseToJsonElement(r.body.string()).jsonObject }
    }

    fun start(clientId: String): DeviceCode {
        val o = post("$AUTH/devicecode", mapOf("client_id" to clientId, "scope" to SCOPES))
        o["error"]?.let { throw IllegalStateException(o["error_description"]?.jsonPrimitive?.content?.lineSequence()?.first() ?: it.jsonPrimitive.content) }
        return json.decodeFromJsonElement(DeviceCode.serializer(), o)
    }

    /** Waits until you've signed in; returns the new account. */
    suspend fun finish(clientId: String, code: DeviceCode): MailAccount {
        val deadline = System.currentTimeMillis() + code.expires_in * 1000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(code.interval * 1000L)
            val o = post("$AUTH/token", mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to code.device_code))
            val err = o["error"]?.jsonPrimitive?.content
            if (err == "authorization_pending" || err == "slow_down") continue
            if (err != null) throw IllegalStateException(o["error_description"]?.jsonPrimitive?.content?.lineSequence()?.first() ?: err)
            val access = o["access_token"]!!.jsonPrimitive.content
            val me = Http.jsonOf("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName,displayName", mapOf("Authorization" to "Bearer $access")).jsonObject
            val email = (me["mail"]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: me["userPrincipalName"]?.jsonPrimitive?.content).orEmpty()
            return MailAccount(com.qshlab.daybreak.cal.newId(), "outlook", email, me["displayName"]?.jsonPrimitive?.content ?: email,
                refreshToken = o["refresh_token"]!!.jsonPrimitive.content, clientId = clientId)
        }
        throw IllegalStateException("The sign-in code expired. Try again.")
    }

    fun fetch(a: MailAccount, since: Instant, limit: Int): List<Mail> {
        val t = post("$AUTH/token", mapOf("grant_type" to "refresh_token", "client_id" to a.clientId, "refresh_token" to a.refreshToken, "scope" to SCOPES))
        t["error"]?.let { throw IllegalStateException("${a.email}: sign in again (${it.jsonPrimitive.content}).") }
        // Microsoft rotates refresh tokens: keep the new one.
        t["refresh_token"]?.jsonPrimitive?.content?.let { if (it != a.refreshToken) MailAccounts.save(a.copy(refreshToken = it)) }
        val access = t["access_token"]!!.jsonPrimitive.content
        val url = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=$limit&\$orderby=receivedDateTime%20desc" +
            "&\$filter=receivedDateTime%20ge%20${since}&\$select=subject,from,receivedDateTime,bodyPreview,inferenceClassification,webLink"
        return Http.jsonOf(url, mapOf("Authorization" to "Bearer $access")).jsonObject["value"]?.jsonArray.orEmpty().map { m ->
            val o = m.jsonObject
            val f = o["from"]?.jsonObject?.get("emailAddress")?.jsonObject
            val name = f?.get("name")?.jsonPrimitive?.content; val addr = f?.get("address")?.jsonPrimitive?.content ?: "?"
            Mail(a.email, if (name != null && name != addr) "$name <$addr>" else addr, o["subject"]?.jsonPrimitive?.content ?: "(no subject)",
                Instant.parse(o["receivedDateTime"]!!.jsonPrimitive.content).toEpochMilli(), o["bodyPreview"]?.jsonPrimitive?.content.orEmpty().take(1000),
                bulk = o["inferenceClassification"]?.jsonPrimitive?.content == "other", url = o["webLink"]?.jsonPrimitive?.content.orEmpty())
        }
    }
}
