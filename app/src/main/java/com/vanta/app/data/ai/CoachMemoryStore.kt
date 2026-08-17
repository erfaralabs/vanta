package com.vanta.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persistent on-device athlete memory for Vanta Coach across all chat sessions.
 * Ensures the AI never treats an existing athlete as a stranger or forgets their history,
 * preferences, personal bests, dietary requirements, and past conversation highlights.
 */
class CoachMemoryStore private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "vanta_coach_memory"
        private const val KEY_ATHLETE_FACTS = "athlete_facts"
        private const val KEY_RECENT_TOPICS = "recent_topics"
        private const val KEY_LAST_CONVERSATION_TIMESTAMP = "last_convo_time"

        @Volatile
        private var instance: CoachMemoryStore? = null

        fun getInstance(context: Context): CoachMemoryStore {
            return instance ?: synchronized(this) {
                instance ?: CoachMemoryStore(context).also { instance = it }
            }
        }
    }

    /**
     * Retrieves stored athlete facts and highlights for injection into Coach system prompts.
     */
    fun getAthleteMemorySummary(): String {
        val facts = getStoredList(KEY_ATHLETE_FACTS)
        val topics = getStoredList(KEY_RECENT_TOPICS)

        if (facts.isEmpty() && topics.isEmpty()) {
            return "Ongoing athlete journey. Focus on continuous physiological progression."
        }

        return buildString {
            if (facts.isNotEmpty()) {
                append("Established Athlete Profile & Preferences: ")
                append(facts.takeLast(6).joinToString(", "))
                append(". ")
            }
            if (topics.isNotEmpty()) {
                append("Recent Discussion Context & Focus: ")
                append(topics.takeLast(4).joinToString("; "))
                append(".")
            }
        }
    }

    fun saveAthleteFact(fact: String) {
        val clean = fact.trim()
        if (clean.isBlank()) return
        val current = getStoredList(KEY_ATHLETE_FACTS).toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            if (current.size > 20) current.removeAt(0)
            saveList(KEY_ATHLETE_FACTS, current)
        }
    }

    fun recordChatTopic(topic: String) {
        val clean = topic.trim()
        if (clean.isBlank()) return
        val current = getStoredList(KEY_RECENT_TOPICS).toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            if (current.size > 10) current.removeAt(0)
            saveList(KEY_RECENT_TOPICS, current)
        }
        prefs.edit().putLong(KEY_LAST_CONVERSATION_TIMESTAMP, System.currentTimeMillis()).apply()
    }

    fun clearMemory() {
        prefs.edit().clear().apply()
    }

    private fun getStoredList(key: String): List<String> {
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i)
                if (item.isNotBlank()) list.add(item)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveList(key: String, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
