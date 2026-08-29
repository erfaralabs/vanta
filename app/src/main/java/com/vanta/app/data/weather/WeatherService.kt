package com.vanta.app.data.weather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Lightweight, permission-free current-weather context for the AI coach.
 *
 * Design goals:
 * - NO location permissions and NO API keys (works out of the box).
 * - Approximate location via IP geolocation (ipapi.co → ipwho.is fallback),
 *   then current conditions from Open-Meteo (free, keyless).
 * - Cached for 45 minutes so a busy chat session never re-hits the network.
 * - Every step fails silently: if anything goes wrong the caller just omits the
 *   weather line and the coach answers from biometrics alone.
 */
object WeatherService {

    private const val PREFS = "vanta_weather_cache"
    private const val KEY_TS = "weather_ts"
    private const val KEY_LINE = "weather_line"
    private const val TTL_MS = 45L * 60L * 1000L

    private data class Geo(val lat: Double, val lon: Double, val label: String)

    /**
     * Returns a compact, human-readable weather sentence (or null when offline /
     * geolocation unavailable). Thread-safe and cheap after the first call.
     */
    suspend fun currentWeatherLine(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_TS, 0L)
        if (System.currentTimeMillis() - ts < TTL_MS) {
            val cached = prefs.getString(KEY_LINE, null)
            if (!cached.isNullOrBlank()) return@withContext cached
        }

        val geo = fetchGeo() ?: return@withContext null
        val line = fetchWeather(geo) ?: return@withContext null
        prefs.edit()
            .putLong(KEY_TS, System.currentTimeMillis())
            .putString(KEY_LINE, line)
            .apply()
        line
    }

    // ── Geolocation (IP-based, permission-free) ────────────────────────────────
    private fun fetchGeo(): Geo? {
        // ipwho.is is reliable over plain server-side HTTPS (no key). ipapi.co is
        // kept as a fallback but is frequently behind a browser challenge page.
        val services = listOf(
            "https://ipwho.is/" to emptyMap(),
            "https://ipapi.co/json/" to mapOf("User-Agent" to "VantaHealthApp/1.0")
        )
        for ((url, headers) in services) {
            val text = httpGet(url, headers) ?: continue
            val geo = runCatching {
                val json = JSONObject(text)
                val lat = json.getDouble("latitude")
                val lon = json.getDouble("longitude")
                val city = json.optString("city", "").trim()
                val country = json.optString("country", "").trim().ifBlank {
                    json.optString("country_name", "").trim()
                }
                val label = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
                    .ifBlank { "your location" }
                Geo(lat, lon, label)
            }.getOrNull()
            if (geo != null) return geo
        }
        return null
    }

    // ── Current conditions (Open-Meteo, keyless) ───────────────────────────────
    private fun fetchWeather(geo: Geo): String? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.lat}&longitude=${geo.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation" +
            "&timezone=auto"
        val text = httpGet(url) ?: return null
        return runCatching {
            val cur = JSONObject(text).getJSONObject("current")
            val tempC = cur.optDouble("temperature_2m", Double.NaN)
            val feels = cur.optDouble("apparent_temperature", Double.NaN)
            val humidity = cur.optInt("relative_humidity_2m", -1)
            val code = cur.optInt("weather_code", -1)
            val wind = cur.optDouble("wind_speed_10m", Double.NaN)
            val precip = cur.optDouble("precipitation", Double.NaN)

            val parts = mutableListOf<String>()
            parts.add("${conditionFromCode(code)}, ${"%.0f".format(tempC)}°C")
            if (!feels.isNaN() && abs(feels - tempC) > 1.0) parts.add("feels like ${"%.0f".format(feels)}°C")
            if (humidity >= 0) parts.add("${humidity}% humidity")
            if (!wind.isNaN()) parts.add("wind ${"%.0f".format(wind)} km/h")
            if (!precip.isNaN() && precip > 0.1) parts.add("${"%.1f".format(precip)} mm precipitation")

            "Current weather (${geo.label}): ${parts.joinToString(", ")}."
        }.getOrNull()
    }

    private fun conditionFromCode(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Cloudy"
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
