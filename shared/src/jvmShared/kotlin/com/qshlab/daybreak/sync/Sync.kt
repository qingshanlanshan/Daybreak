package com.qshlab.daybreak.sync

import com.qshlab.daybreak.Platform
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.json
import com.qshlab.daybreak.data.ownerOnly
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Sync between your devices through your own Cloudflare R2 bucket, end-to-end encrypted (the same protocol
 * as Folio). Off unless set up in Settings.
 *
 * Bucket layout (everything but folio-sync.json is AES-256-GCM encrypted with a key only your devices have;
 * it travels inside the pairing code):
 *   folio-sync.json      {"format": 1, "check": …}  tells a different sync key apart
 *   b/<id>               one piece (≤ 8 MiB) of a file; id = keyed hash of the content, written once
 *   l/<device>/<seq>     a batch of changes by one device: {p: path, c: [piece ids], s: size, t: clock, d: device, x: deleted}
 *   d/<device>           the device's name, platform and when it was last seen
 * Devices only ever add new keys, so they never overwrite each other. For each file the change with the
 * highest (clock, device) wins. Everything under the data folder syncs except .sync/ and local/ (this
 * device's own state: sync settings, caches, the trash).
 */

@Serializable
data class SyncConfig(val endpoint: String, val bucket: String, val keyId: String, val secret: String, val key: String,
                      val device: String = "", val name: String = "")

@Serializable
data class Change(val p: String, val c: List<String> = emptyList(), val s: Long = 0, val t: Long, val d: String, val x: Boolean = false)

@Serializable
data class Batch(val v: Int = 1, val changes: List<Change>)

@Serializable
data class LocalFile(val s: Long = 0, val m: Long = 0, val c: List<String> = emptyList(), val t: Long, val d: String, val x: Boolean = false)

@Serializable
data class DeviceInfo(val name: String, val platform: String, val seen: Double, val me: Boolean = false)

@Serializable
data class SyncState(
    var seq: Long = -1, var clock: Long = 0,        // seq -1: not known yet (fresh start or rejoined)
    val cursors: MutableMap<String, String> = mutableMapOf(),
    val files: MutableMap<String, LocalFile> = mutableMapOf(),
    val known: MutableMap<String, Change> = mutableMapOf(),
    val pieces: MutableSet<String> = mutableSetOf(),
    var announced: Double = 0.0, var listed: Double = 0.0,
    var devices: List<DeviceInfo> = emptyList(),
)

@Serializable
private data class Marker(val format: Int = 1, val check: String)

@Serializable
private data class CodePayload(val endpoint: String, val bucket: String, val keyId: String, val secret: String, val key: String)

data class SyncStatus(val running: Boolean = false, val last: Double? = null, val error: String? = null,
                      val sent: Int = 0, val received: Int = 0)

class SyncError(message: String) : Exception(message)

class Sync(private val platform: Platform) {
    private val home = platform.home
    private val dir = File(home, ".sync")
    private val configFile = File(dir, "config.json")
    private val deviceFile = File(dir, "device")
    private val stateFile = File(dir, "state.json")
    private val trash = File(home, "local/trash")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val _config = MutableStateFlow<SyncConfig?>(null)
    val config: StateFlow<SyncConfig?> = _config
    private var soon: Job? = null

    fun start() {
        _config.value = configFile.readJson<SyncConfig>()
        scope.launch {
            while (true) {
                if (_config.value != null) runOnce()
                withTimeoutOrNull(INTERVAL_MS) { wake.receive() }
            }
        }
    }

    /** Sync now (app opened, "Sync now"). */
    fun kick() { wake.trySend(Unit) }

    /** Sync a few seconds after a local edit, once edits settle. */
    fun kickSoon() {
        soon?.cancel()
        soon = scope.launch { delay(4_000); kick() }
    }

    // --- setup and pairing

    private fun saveConfig(c: SyncConfig?) {
        if (c == null) configFile.delete() else { configFile.writeJson(c); configFile.ownerOnly() }
        _config.value = c
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun master(c: SyncConfig) = Base64.getUrlDecoder().decode(c.key)
    private fun encKey(c: SyncConfig) = hmac(master(c), "folio-sync enc".toByteArray())
    private fun macKey(c: SyncConfig) = hmac(master(c), "folio-sync mac".toByteArray())
    private fun check(c: SyncConfig) = hex(hmac(macKey(c), "folio-sync".toByteArray())).take(16)
    private fun client(c: SyncConfig) = S3(c.endpoint, c.bucket, c.keyId, c.secret)
    private fun randomHex(n: Int) = hex(ByteArray(n).also { SecureRandom().nextBytes(it) })

    /** Kept when sync is turned off, so rejoining isn't a new device. */
    private fun deviceId(): String = deviceFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { Regex("[0-9a-f]{12}").matches(it) }
        ?: randomHex(6).also { dir.mkdirs(); deviceFile.writeText(it) }

    fun endpoint(account: String): String {
        val a = account.trim()
        if (a.startsWith("https://") || a.startsWith("http://")) return java.net.URI(a).let { "${it.scheme}://${it.authority}" }
        if (!Regex("[0-9a-f]{32}").matches(a)) throw SyncError("The account ID is 32 letters and digits (Cloudflare dashboard → R2 → Account details).")
        return "https://$a.r2.cloudflarestorage.com"
    }

    private fun marker(c: SyncConfig): Marker? = try {
        client(c).get("folio-sync.json")?.let { json.decodeFromString<Marker>(String(it)) }
    } catch (e: S3.Error) {
        throw when (e.status) {
            401, 403 -> SyncError("Cloudflare refused the key. Check the access key ID and secret, and that the token can read and write this bucket.")
            404 -> SyncError("There's no bucket named “${c.bucket}” in this account.")
            else -> SyncError("Cloudflare said: ${e.message}")
        }
    } catch (e: IOException) {
        throw SyncError("Couldn't reach Cloudflare (${e.javaClass.simpleName}). Check the account ID and your connection.")
    }

    /** First device: check the bucket and start a new sync key. Call off the main thread. */
    fun connect(account: String, bucket: String, keyId: String, secret: String) {
        val c = SyncConfig(endpoint(account), bucket.trim(), keyId.trim(), secret.trim(),
            Base64.getUrlEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }), deviceId(), platform.deviceName)
        if (listOf(c.bucket, c.keyId, c.secret).any { it.isEmpty() }) throw SyncError("Fill in all four fields.")
        if (marker(c) != null) throw SyncError("This bucket already holds synced data. On the device that set it up, open Settings → Sync → “Code for another device”, and join with that code instead.")
        client(c).put("folio-sync.json", json.encodeToString(Marker(1, check(c))).toByteArray())
        begin(c)
    }

    fun code(): String {
        val c = _config.value ?: throw SyncError("Sync is off.")
        val p = kotlinx.serialization.json.Json.encodeToString(CodePayload(c.endpoint, c.bucket, c.keyId, c.secret, c.key))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(p.toByteArray())
    }

    /** Another device: use the code from a device that already syncs. Call off the main thread. */
    fun join(text: String) {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) throw SyncError("That isn't a Daybreak sync code. It starts with “$PREFIX”.")
        val p = runCatching { json.decodeFromString<CodePayload>(String(Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)))) }
            .getOrElse { throw SyncError("The code is incomplete. Copy all of it.") }
        val c = SyncConfig(p.endpoint, p.bucket, p.keyId, p.secret, p.key, deviceId(), platform.deviceName)
        val m = marker(c) ?: throw SyncError("The bucket in this code is empty. Set up sync on the other device first.")
        if (m.check != check(c)) throw SyncError("This code doesn't match the data in the bucket. Get a fresh code from the other device.")
        begin(c)
    }

    private fun begin(c: SyncConfig) {
        synchronized(lock) { stateFile.delete(); saveConfig(c) }
        _status.value = SyncStatus()
        kick()
    }

    /** Stop syncing on this device. Nothing is deleted, here or in the bucket. */
    fun disconnect() {
        synchronized(lock) { saveConfig(null); stateFile.delete() }
        _status.value = SyncStatus()
    }

    fun devices(): List<DeviceInfo> = stateFile.readJson<SyncState>()?.devices ?: emptyList()

    @Serializable private data class Lease(val device: String, val until: Long)

    /**
     * A short lock in the bucket so only one device does a job (e.g. writing the scheduled feed). True if this
     * device holds it for [seconds]. Always true when sync is off. Call off the main thread.
     */
    fun tryLease(name: String, seconds: Long): Boolean {
        val c = _config.value ?: return true
        return runCatching {
            val s3 = client(c); val key = "x/lease-$name"; val now = System.currentTimeMillis()
            val cur = s3.get(key)?.let { json.decodeFromString<Lease>(String(it)) }
            if (cur != null && cur.device != c.device && cur.until > now) return false
            s3.put(key, json.encodeToString(Lease(c.device, now + seconds * 1000)).toByteArray())
            Thread.sleep(1500)   // another device writing at the same moment: the last write wins, both read it back
            s3.get(key)?.let { json.decodeFromString<Lease>(String(it)) }?.device == c.device
        }.getOrDefault(true)   // can't reach the bucket: go ahead rather than skip the update
    }

    val deviceId: String get() = _config.value?.device ?: deviceId()

    // --- encryption

    private fun seal(c: SyncConfig, data: ByteArray, aad: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(c), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(data)
    }

    private fun open(c: SyncConfig, blob: ByteArray, aad: String): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(c), "AES"), GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        cipher.doFinal(blob, 12, blob.size - 12)
    } catch (e: AEADBadTagException) {
        throw SyncError("Some synced data couldn't be decrypted (wrong key or damaged).")
    } catch (e: IllegalArgumentException) {
        throw SyncError("Some synced data couldn't be decrypted (damaged).")
    }

    private fun pieceId(c: SyncConfig, data: ByteArray, len: Int = data.size) =
        hex(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey(c), "HmacSHA256")); update(data, 0, len); doFinal() })

    // --- local files

    private fun synced(path: String) = !path.startsWith(".sync/") && !path.startsWith("local/") && !path.contains("/../") &&
        !path.startsWith("../") && !path.endsWith(".tmp") && !path.endsWith(".sync-tmp") && !path.endsWith(".DS_Store")

    private fun scan(): Map<String, File> = home.walkTopDown()
        .onEnter { d -> d == home || d.relativeTo(home).invariantSeparatorsPath.let { it != ".sync" && it != "local" } }
        .filter { it.isFile }
        .associateBy { it.relativeTo(home).invariantSeparatorsPath }
        .filterKeys(::synced)

    private fun beats(e: Change, cur: Change?) = cur == null || e.t > cur.t || (e.t == cur.t && e.d > cur.d)

    private fun moveToTrash(f: File) {
        trash.mkdirs()
        var dest = File(trash, f.name); var n = 2
        while (dest.exists()) { dest = File(trash, "${f.name}-$n"); n++ }
        if (!f.renameTo(dest)) f.deleteRecursively()
    }

    // --- one round

    fun runOnce() {
        val c = _config.value ?: return
        synchronized(lock) {
            _status.value = _status.value.copy(running = true)
            try {
                val (sent, received) = round(c)
                _status.value = SyncStatus(last = System.currentTimeMillis() / 1000.0, sent = sent, received = received)
                if (received > 0) Changes.bump()
            } catch (e: SyncError) {
                _status.value = _status.value.copy(running = false, error = e.message)
            } catch (e: S3.Error) {
                _status.value = _status.value.copy(running = false, error = if (e.status == 401 || e.status == 403) "Cloudflare refused the key." else "Cloudflare said: ${e.message}")
            } catch (e: IOException) {
                _status.value = _status.value.copy(running = false, error = "Couldn't reach Cloudflare (${e.javaClass.simpleName}). Will retry.")
            }
        }
    }

    private fun round(c: SyncConfig): Pair<Int, Int> {
        val s3 = client(c); val me = c.device
        val st = stateFile.readJson<SyncState>() ?: SyncState()
        fun save() = stateFile.writeJson(st)
        if (st.seq < 0)     // after rejoining: carry on after this device's earlier batches
            st.seq = s3.list("l/$me/").first.maxOfOrNull { it.substringAfterLast('/').toLong() } ?: 0

        // 1. local changes (files are read and sent in 8 MiB pieces)
        val local = scan()
        val changes = mutableListOf<Change>()
        val buf = ByteArray(CHUNK)
        for ((path, f) in local) {
            val cur = st.files[path]
            if (cur != null && !cur.x && cur.s == f.length() && cur.m == f.lastModified()) continue
            val ids = mutableListOf<String>()
            f.inputStream().use { input ->
                while (true) {
                    var n = 0
                    while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                    if (n == 0 && ids.isNotEmpty()) break
                    val id = pieceId(c, buf, n)
                    ids += id
                    if (id !in st.pieces) { s3.put("b/$id", seal(c, buf.copyOf(n), id)); st.pieces += id }
                    if (n < CHUNK) break
                }
            }
            if (cur != null && !cur.x && cur.c == ids) { st.files[path] = cur.copy(s = f.length(), m = f.lastModified()); continue }
            st.clock += 1
            val e = Change(path, ids, f.length(), st.clock, me)
            changes += e
            st.files[path] = LocalFile(f.length(), f.lastModified(), ids, e.t, me)
            st.known[path] = e
        }
        for ((path, cur) in st.files.toMap()) {
            if (!cur.x && path !in local) {
                st.clock += 1
                val e = Change(path, t = st.clock, d = me, x = true)
                changes += e
                st.files[path] = LocalFile(t = e.t, d = me, x = true)
                st.known[path] = e
            }
        }
        if (changes.isNotEmpty()) {
            val key = "l/$me/%010d".format(st.seq + 1)
            s3.put(key, seal(c, json.encodeToString(Batch(1, changes)).toByteArray(), key))
            st.seq += 1
        }
        save()

        // 2. other devices' changes
        for (prefix in s3.list("l/", delimiter = "/").second) {
            val dev = prefix.removePrefix("l/").removeSuffix("/")
            if (dev == me) continue
            for (key in s3.list(prefix, startAfter = st.cursors[dev] ?: "").first) {
                val raw = s3.get(key) ?: continue
                for (e in json.decodeFromString<Batch>(String(open(c, raw, key))).changes) {
                    if (!synced(e.p)) continue
                    st.clock = maxOf(st.clock, e.t)
                    if (beats(e, st.known[e.p])) st.known[e.p] = e
                    st.pieces += e.c
                }
                st.cursors[dev] = key
            }
        }
        save()

        // 3. apply what other devices won: deletions first; a deleted META file takes its folder to the trash
        val todo = st.known.values.filter { e -> e.d != me && st.files[e.p].let { it?.t != e.t || it.d != e.d } }
            .sortedWith(compareBy({ !it.x }, { (File(it.p).name in META) != it.x }))
        var received = 0
        for (e in todo) {
            val f = File(home, e.p)
            if (e.x) {
                if (File(e.p).name in META && f.parentFile?.exists() == true) moveToTrash(f.parentFile!!)
                else f.delete()
                st.files[e.p] = LocalFile(t = e.t, d = e.d, x = true)
            } else {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".sync-tmp")
                tmp.outputStream().use { out ->
                    for (pid in e.c) {
                        val part = open(c, s3.get("b/$pid") ?: throw SyncError("A synced file is missing a piece. Will retry."), pid)
                        if (pieceId(c, part) != pid) throw SyncError("A synced file didn't match its checksum. Will retry.")
                        out.write(part)
                    }
                }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                st.files[e.p] = LocalFile(f.length(), f.lastModified(), e.c, e.t, e.d)
            }
            received++
            if (received % 20 == 0) save()
        }

        // 4. this device (said once a day), and the list of devices for Settings
        val now = System.currentTimeMillis() / 1000.0
        if (now - st.announced > 86400) {
            s3.put("d/$me", seal(c, json.encodeToString(DeviceInfo(c.name, platform.kind, now)).toByteArray(), "d/$me"))
            st.announced = now
        }
        if (now - st.listed > 3600 || received > 0) {
            st.listed = now
            // Devices not seen for 60 days (e.g. before a reinstall) drop off the list.
            st.devices = s3.list("d/").first.mapNotNull { key ->
                s3.get(key)?.let { json.decodeFromString<DeviceInfo>(String(open(c, it, key))).copy(me = key == "d/$me") }
            }.filter { it.me || now - it.seen < 60 * 86400 }.sortedWith(compareBy({ !it.me }, { it.name }))
        }
        save()
        return changes.size to received
    }

    companion object {
        const val PREFIX = "daybreak1:"
        private const val CHUNK = 8 shl 20
        private const val INTERVAL_MS = 5 * 60_000L
        /** A folder's defining file: when it's deleted elsewhere, the whole folder goes to the trash here. */
        private val META = setOf("calendar.json", "list.json")
    }
}
