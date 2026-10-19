package com.qshlab.daybreak.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.io.File

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; explicitNulls = false }

fun now(): Double = System.currentTimeMillis() / 1000.0

/** Bumped whenever stored data changes (locally or by sync), so screens re-read it. */
object Changes {
    val version = MutableStateFlow(0)
    fun bump() { version.value++ }
}

/** Write then rename, so a crash never leaves half a file. */
fun File.writeAtomic(text: String) = writeAtomic(text.toByteArray())

fun File.writeAtomic(bytes: ByteArray) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) { delete(); tmp.renameTo(this) }
}

inline fun <reified T> File.readJson(): T? = if (exists()) runCatching { json.decodeFromString<T>(readText()) }.getOrNull() else null
inline fun <reified T> File.writeJson(value: T) = writeAtomic(json.encodeToString(value))

/** Only the owner can read it (keys, passwords). No-op where the OS already keeps app files private. */
fun File.ownerOnly() { setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
