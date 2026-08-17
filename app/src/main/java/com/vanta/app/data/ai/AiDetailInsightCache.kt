package com.vanta.app.data.ai

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persistent cache for AI insights on Physiology Detail Pages (Recovery, Strain, Energy,
 * and the expanded Vanta Coach deep-dive overview).
 *
 * Prevents redundant API calls on repeated page opens or minor telemetry shifts.
 */
class AiDetailInsightCache(context: Context) {
    private val prefs = context.getSharedPreferences("vanta_ai_detail_insights_cache", Context.MODE_PRIVATE)

    data class Entry(
        val key: String,
        val date: String,
        val hourOfDay: Int,
        val recovery: Int,
        val energy: Int,
        val strain: Double,
        val workoutMinutes: Int,
        val text: String,
        val isAiGenerated: Boolean,
        val timestampMs: Long
    )

    fun load(metricKey: String): Entry? {
        val raw = prefs.getString("detail_cache_$metricKey", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Entry(
                key = metricKey,
                date = json.getString("date"),
                hourOfDay = json.getInt("hourOfDay"),
                recovery = json.getInt("recovery"),
                energy = json.getInt("energy"),
                strain = json.getDouble("strain"),
                workoutMinutes = json.optInt("workoutMinutes", 0),
                text = json.getString("text"),
                isAiGenerated = json.getBoolean("isAiGenerated"),
                timestampMs = json.optLong("timestampMs", 0L)
            )
        }.getOrNull()
    }

    fun save(
        metricKey: String,
        date: String,
        hourOfDay: Int,
        recovery: Int,
        energy: Int,
        strain: Double,
        workoutMinutes: Int,
        text: String,
        isAiGenerated: Boolean
    ) {
        val json = JSONObject()
            .put("date", date)
            .put("hourOfDay", hourOfDay)
            .put("recovery", recovery)
            .put("energy", energy)
            .put("strain", strain)
            .put("workoutMinutes", workoutMinutes)
            .put("text", text)
            .put("isAiGenerated", isAiGenerated)
            .put("timestampMs", System.currentTimeMillis())
        prefs.edit().putString("detail_cache_$metricKey", json.toString()).apply()
    }

    fun isValid(
        entry: Entry?,
        targetRecovery: Int,
        targetEnergy: Int,
        targetStrain: Double,
        targetWorkoutMinutes: Int,
        currentDate: String,
        currentHour: Int
    ): Boolean {
        if (entry == null) return false
        if (entry.date != currentDate) return false
        if (abs(entry.hourOfDay - currentHour) > 1) return false
        if (abs(entry.recovery - targetRecovery) > 3) return false
        if (abs(entry.energy - targetEnergy) > 3) return false
        if (abs(entry.strain - targetStrain) > 0.5) return false
        if (entry.workoutMinutes != targetWorkoutMinutes) return false
        return true
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
