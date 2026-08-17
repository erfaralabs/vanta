package com.vanta.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches the latest AI-generated overview so the cloud API is NOT called on every
 * app open or telemetry tick. The cache is only reused when nothing meaningful
 * changed; it is invalidated (regenerated) when:
 *  - Recovery changes
 *  - Energy drifts by 3+ points (so the AI text never cites a stale energy number)
 *  - Strain changes by at least 0.5
 *  - A new workout session is logged (exercise minutes increased)
 */
class AiOverviewCache(context: Context) {

    private val prefs = context.getSharedPreferences("vanta_ai_cache", Context.MODE_PRIVATE)

    data class Entry(
        val date: String,
        val recovery: Int,
        val energy: Int,
        val strain: Double,
        val workoutMinutes: Int,
        val generatedWithModel: Boolean,
        val overview: String,
        val recommendation: String,
        val callouts: List<GemmaCallout>
    )

    fun load(): Entry? {
        val jsonStr = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(jsonStr)
            val arr = o.getJSONArray("callouts")
            val callouts = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                GemmaCallout(c.getString("text"), c.getString("colorHex"))
            }
            Entry(
                date = o.getString("date"),
                recovery = o.getInt("recovery"),
                // -1 = legacy cache without an energy snapshot → treated as stale
                energy = o.optInt("energy", -1),
                strain = o.getDouble("strain"),
                workoutMinutes = o.getInt("workoutMinutes"),
                generatedWithModel = o.getBoolean("generatedWithModel"),
                overview = o.getString("overview"),
                recommendation = o.getString("recommendation"),
                callouts = callouts
            )
        }.getOrNull()
    }

    fun save(entry: Entry) {
        val arr = JSONArray()
        entry.callouts.forEach { c ->
            arr.put(JSONObject().put("text", c.text).put("colorHex", c.colorHex))
        }
        val o = JSONObject()
            .put("date", entry.date)
            .put("recovery", entry.recovery)
            .put("energy", entry.energy)
            .put("strain", entry.strain)
            .put("workoutMinutes", entry.workoutMinutes)
            .put("generatedWithModel", entry.generatedWithModel)
            .put("overview", entry.overview)
            .put("recommendation", entry.recommendation)
            .put("callouts", arr)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "latest_ai_overview"
    }
}
