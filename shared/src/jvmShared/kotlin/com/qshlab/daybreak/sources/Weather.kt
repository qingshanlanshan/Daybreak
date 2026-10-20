package com.qshlab.daybreak.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The weather for one place: now, the next hours, the next days, air quality (Open-Meteo; free, no key). */
@Serializable
data class WeatherReport(
    val place: String,
    val now: String,                     // "29°C, feels 33°C · light rain"
    val today: String,                   // "24–31°C · 80% chance of rain (12 mm) · UV 9"
    val nextHours: List<String>,         // notable hours: "15:00 heavy rain 70%"
    val nextDays: List<String>,          // "Wed: 25–31°C, thunderstorm 90%"
    val air: String?,                    // "Air quality: moderate (US AQI 72, PM2.5 21 µg/m³)"
    val code: Int, val temp: Double,
)

object Weather {
    fun describe(code: Int): String = when (code) {
        0 -> "clear"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "fog"
        51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"; 61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"
        66, 67 -> "freezing rain"; 71 -> "light snow"; 73 -> "snow"; 75 -> "heavy snow"; 77 -> "snow grains"
        80 -> "light showers"; 81 -> "showers"; 82 -> "violent showers"; 85, 86 -> "snow showers"
        95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"; else -> "—"
    }

    fun icon(code: Int): String = when (code) { 0, 1 -> "☀️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"; else -> "🌡️" }

    private fun aqiText(a: Int) = when { a <= 50 -> "good"; a <= 100 -> "moderate"; a <= 150 -> "unhealthy for sensitive groups"; a <= 200 -> "unhealthy"; a <= 300 -> "very unhealthy"; else -> "hazardous" }

    fun fetch(p: Place): WeatherReport {
        val f = Http.jsonOf("https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,precipitation_sum,uv_index_max&timezone=auto&forecast_days=4").jsonObject
        fun arr(o: JsonObject?, k: String) = o?.get(k)?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val cur = f["current"]!!.jsonObject
        fun c(k: String) = cur[k]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val code = c("weather_code").toInt()
        val now = "%.0f°C, feels %.0f°C · %s".format(c("temperature_2m"), c("apparent_temperature"), describe(code)) +
            (if (c("wind_speed_10m") >= 30) " · windy (%.0f km/h)".format(c("wind_speed_10m")) else "")
        val d = f["daily"]?.jsonObject
        val dTime = arr(d, "time"); val dMax = arr(d, "temperature_2m_max"); val dMin = arr(d, "temperature_2m_min"); val dCode = arr(d, "weather_code")
        val dRain = arr(d, "precipitation_probability_max"); val dSum = arr(d, "precipitation_sum"); val dUv = arr(d, "uv_index_max")
        fun day(i: Int) = "%.0f–%.0f°C · %s".format(dMin[i].toDouble(), dMax[i].toDouble(), describe(dCode[i].toDouble().toInt())) +
            (dRain.getOrNull(i)?.toDoubleOrNull()?.takeIf { it >= 30 }?.let { " · %.0f%% chance of rain".format(it) + (dSum.getOrNull(i)?.toDoubleOrNull()?.takeIf { s -> s >= 1 }?.let { s -> " (%.0f mm)".format(s) } ?: "") } ?: "") +
            (dUv.getOrNull(i)?.toDoubleOrNull()?.takeIf { it >= 8 }?.let { " · UV %.0f (very high)".format(it) } ?: "")
        val h = f["hourly"]?.jsonObject
        val hTime = arr(h, "time"); val hCode = arr(h, "weather_code"); val hRain = arr(h, "precipitation_probability")
        val nowHour = cur["time"]?.jsonPrimitive?.content?.take(13) ?: ""
        val start = hTime.indexOfFirst { it.take(13) >= nowHour }.coerceAtLeast(0)
        val notable = (start until minOf(start + 18, hTime.size)).filter { i ->
            val cd = hCode[i].toDouble().toInt(); val rp = hRain.getOrNull(i)?.toDoubleOrNull() ?: 0.0
            (cd >= 61 || cd in 95..99) && rp >= 40
        }.map { i -> "${hTime[i].takeLast(5)} ${describe(hCode[i].toDouble().toInt())} ${hRain[i].toDouble().toInt()}%" }
        val air = runCatching {
            val a = Http.jsonOf("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}&longitude=${p.lon}&current=us_aqi,pm2_5&timezone=auto").jsonObject["current"]!!.jsonObject
            val aqi = a["us_aqi"]!!.jsonPrimitive.content.toDouble().toInt()
            "Air quality: ${aqiText(aqi)} (US AQI $aqi, PM2.5 %.0f µg/m³)".format(a["pm2_5"]!!.jsonPrimitive.content.toDouble())
        }.getOrNull()
        return WeatherReport(p.name, now, if (dTime.isNotEmpty()) day(0) else "", notable,
            (1 until minOf(4, dTime.size)).map { i -> java.time.LocalDate.parse(dTime[i]).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ": " + day(i) },
            air, code, c("temperature_2m"))
    }
}
