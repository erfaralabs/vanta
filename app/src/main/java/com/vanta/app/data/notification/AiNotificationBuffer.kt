package com.vanta.app.data.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pre-generated AI notification buffer.
 *
 * Allows on-device LLM (or cloud AI) to pre-generate a batch of athletic notification
 * messages during foreground analysis, and store them locally.
 *
 * Background workers (WorkManager / TelemetrySync) can then retrieve and dispatch
 * rich AI notifications instantly with 0MB background RAM footprint and zero LLM loading.
 */
class AiNotificationBuffer(context: Context) {

    private val prefs = context.getSharedPreferences("vanta_ai_notification_buffer", Context.MODE_PRIVATE)

    data class BufferedMessage(
        val reason: String, // e.g. "morning", "workout", "strain", "evening", "achievement"
        val title: String,
        val message: String,
        val priority: String = "normal",
        val date: String
    )

    fun saveDrafts(date: String, drafts: List<BufferedMessage>) {
        // Prune stale per-date buffers (only today's is ever consumed) so the
        // prefs file never grows unbounded across days.
        val todayKey = "buffered_drafts_$date"
        prefs.all.keys
            .filter { it.startsWith("buffered_drafts_") && it != todayKey }
            .forEach { prefs.edit().remove(it).apply() }

        val array = JSONArray()
        for (d in drafts) {
            val obj = JSONObject()
                .put("reason", d.reason)
                .put("title", d.title)
                .put("message", d.message)
                .put("priority", d.priority)
                .put("date", d.date)
            array.put(obj)
        }
        prefs.edit().putString("buffered_drafts_$date", array.toString()).apply()
    }

    fun popDraft(reason: String, date: String): BufferedMessage? {
        val raw = prefs.getString("buffered_drafts_$date", null) ?: return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        var foundIndex = -1
        var matchedDraft: BufferedMessage? = null

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("reason") == reason && obj.optString("date") == date) {
                foundIndex = i
                matchedDraft = BufferedMessage(
                    reason = obj.getString("reason"),
                    title = obj.getString("title"),
                    message = obj.getString("message"),
                    priority = obj.optString("priority", "normal"),
                    date = obj.getString("date")
                )
                break
            }
        }

        if (foundIndex != -1 && matchedDraft != null) {
            val updated = JSONArray()
            for (i in 0 until array.length()) {
                if (i != foundIndex) {
                    updated.put(array.get(i))
                }
            }
            prefs.edit().putString("buffered_drafts_$date", updated.toString()).apply()
            return matchedDraft
        }

        return null
    }

    fun hasDrafts(date: String): Boolean {
        val raw = prefs.getString("buffered_drafts_$date", null) ?: return false
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        return array.length() > 0
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
