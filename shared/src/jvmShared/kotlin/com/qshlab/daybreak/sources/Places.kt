package com.qshlab.daybreak.sources

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.Changes
import com.qshlab.daybreak.data.readJson
import com.qshlab.daybreak.data.writeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A place the feed covers. The "current location" entry is filled in on each device from where it is. */
@Serializable
data class Place(val id: String, val name: String, val lat: Double = 0.0, val lon: Double = 0.0, val country: String = "",
                 val countryCode: String = "", val current: Boolean = false)

@Serializable
data class PlaceList(val places: List<Place> = listOf(Place("here", "Current location", current = true)))

/** Where this device is now (not synced: each device has its own). */
@Serializable
data class Here(val name: String, val lat: Double, val lon: Double, val country: String = "", val countryCode: String = "", val at: Long = 0,
                val approx: Boolean = false)    // from the internet connection (the device couldn't say)

object Places {
    private val file get() = File(Daybreak.platform.home, "feed/places.json")
    private val hereFile get() = File(Daybreak.platform.home, "local/here.json")

    fun list(): List<Place> = file.readJson<PlaceList>()?.places ?: PlaceList().places
    fun save(p: List<Place>) { file.writeJson(PlaceList(p)); Changes.bump(); Daybreak.sync.kickSoon() }
    fun here(): Here? = hereFile.readJson()
    /** Why the last attempt to find the current location failed (shown with the feed's sources). */
    @Volatile var lastError: String? = null

    /** The places with coordinates (current location resolved; skipped if unknown). */
    fun resolved(): List<Place> = list().mapNotNull { p ->
        if (!p.current) p else here()?.let { h -> p.copy(name = h.name, lat = h.lat, lon = h.lon, country = h.country, countryCode = h.countryCode) }
    }

    /** City search (Open-Meteo geocoding). */
    fun search(q: String): List<Place> = Http.jsonOf("https://geocoding-api.open-meteo.com/v1/search?name=${enc(q)}&count=8&language=en&format=json")
        .jsonObject["results"]?.jsonArray.orEmpty().map { r ->
            val o = r.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            Place(com.qshlab.daybreak.cal.newId(), s("name"), s("latitude").toDouble(), s("longitude").toDouble(),
                listOf(s("admin1"), s("country")).filter { it.isNotBlank() && it != s("name") }.joinToString(", "), s("country_code"))
        }

    /**
     * Asks the device where it is (with its permission) and names the place. When the device can't say (the Mac;
     * a phone with location off or out of reach), the city comes from the internet connection instead (approximate).
     * Call off the main thread.
     */
    suspend fun locate(): Result<Here> = runCatching {
        val exact = Daybreak.platform.locate()
        val base = "https://api-bdc.io/data/reverse-geocode-client?localityLanguage=en"
        val r = runCatching { Http.jsonOf(exact.getOrNull()?.let { (lat, lon) -> "$base&latitude=$lat&longitude=$lon" } ?: base).jsonObject }.getOrNull()
        fun s(k: String) = r?.get(k)?.jsonPrimitive?.content.orEmpty()
        val (lat, lon) = exact.getOrNull() ?: (s("latitude").toDoubleOrNull() ?: throw exact.exceptionOrNull()!!) to s("longitude").toDouble()
        val name = s("city").ifBlank { s("locality") }.ifBlank { s("principalSubdivision") }.ifBlank { "%.2f, %.2f".format(lat, lon) }
        Here(name, lat, lon, s("countryName"), s("countryCode"), System.currentTimeMillis(), approx = exact.isFailure)
            .also { hereFile.writeJson(it); Changes.bump() }
    }
}
