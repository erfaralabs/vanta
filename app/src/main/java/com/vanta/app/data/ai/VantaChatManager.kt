package com.vanta.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" or "assistant"
    val content: String,
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)

class VantaChatManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vanta_ai_chat_sessions", Context.MODE_PRIVATE)

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<ChatSession>(createNewSession())
    val currentSession: StateFlow<ChatSession> = _currentSession.asStateFlow()

    init {
        loadSessionsFromDisk()
    }

    companion object {
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: VantaChatManager? = null

        fun getInstance(context: Context): VantaChatManager {
            return instance ?: synchronized(this) {
                instance ?: VantaChatManager(context.applicationContext).also { instance = it }
            }
        }

        private fun createNewSession(): ChatSession {
            return ChatSession(
                id = UUID.randomUUID().toString(),
                title = "New Conversation",
                timestamp = System.currentTimeMillis(),
                messages = emptyList()
            )
        }
    }

    fun startNewSession(): ChatSession {
        // If current session already has no messages, reuse it instead of creating duplicates
        if (_currentSession.value.messages.isEmpty()) {
            return _currentSession.value
        }
        val newSession = createNewSession()
        _currentSession.value = newSession
        // Clean any empty sessions from the list
        _sessions.value = _sessions.value.filter { it.messages.isNotEmpty() }
        persistSessions()
        return newSession
    }

    fun loadSession(sessionId: String) {
        val target = _sessions.value.find { it.id == sessionId }
        if (target != null) {
            _currentSession.value = target
        }
    }

    fun deleteSession(sessionId: String) {
        val currentId = _currentSession.value.id
        val updated = _sessions.value.filter { it.id != sessionId && it.messages.isNotEmpty() }
        _sessions.value = updated
        if (currentId == sessionId) {
            _currentSession.value = updated.firstOrNull() ?: createNewSession()
        }
        persistSessions()
    }

    fun clearAllSessions() {
        val fresh = createNewSession()
        _currentSession.value = fresh
        _sessions.value = emptyList()
        persistSessions()
    }

    fun addMessage(role: String, content: String, imageUri: String? = null) {
        val cur = _currentSession.value
        val msg = ChatMessage(role = role, content = content, imageUri = imageUri)
        val newMessages = cur.messages + msg

        // Auto-generate title from first user query if still default
        val newTitle = if (cur.title == "New Conversation" && role == "user") {
            val titleText = if (content.isNotBlank()) content else "Food & Nutrition Photo"
            titleText.take(34).trim() + if (titleText.length > 34) "..." else ""
        } else {
            cur.title
        }

        val updatedSession = cur.copy(
            title = newTitle,
            timestamp = System.currentTimeMillis(),
            messages = newMessages
        )
        _currentSession.value = updatedSession

        val sessionList = _sessions.value.filter { it.messages.isNotEmpty() && it.id != updatedSession.id }.toMutableList()
        sessionList.add(0, updatedSession)
        _sessions.value = sessionList
        persistSessions()
    }

    fun updateLastAssistantMessage(chunk: String) {
        val cur = _currentSession.value
        if (cur.messages.isEmpty()) return
        val last = cur.messages.last()
        if (last.role == "assistant") {
            val updatedLast = last.copy(content = chunk)
            val updatedMessages = cur.messages.dropLast(1) + updatedLast
            _currentSession.value = cur.copy(messages = updatedMessages)
            // NOTE: do NOT rebuild `_sessions` or persist here. Persisting the full
            // conversation (JSON serialize + SharedPreferences) on every token janks
            // streaming. We persist once when the stream finishes (see commitCurrentSession).
        }
    }

    /** Persists the finished session once (called after the stream completes). */
    fun commitCurrentSession() {
        val updatedSession = _currentSession.value
        if (updatedSession.messages.isEmpty()) return
        val sessionList = _sessions.value.filter { it.id != updatedSession.id }.toMutableList()
        sessionList.add(0, updatedSession)
        _sessions.value = sessionList
        persistSessions()
    }

    private fun loadSessionsFromDisk() {
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_MILLIS
        val jsonStr = prefs.getString("chat_sessions_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<ChatSession>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val sessionTimestamp = obj.optLong("timestamp", now)
                    // Auto-delete sessions older than 7 days
                    if (sessionTimestamp < cutoff) continue

                    val msgArray = obj.getJSONArray("messages")
                    val msgs = mutableListOf<ChatMessage>()
                    for (j in 0 until msgArray.length()) {
                        val mObj = msgArray.getJSONObject(j)
                        val msgTimestamp = mObj.optLong("timestamp", sessionTimestamp)
                        if (msgTimestamp < cutoff) continue

                        msgs.add(
                            ChatMessage(
                                id = mObj.getString("id"),
                                role = mObj.getString("role"),
                                content = mObj.getString("content"),
                                imageUri = if (mObj.has("imageUri") && !mObj.isNull("imageUri")) mObj.getString("imageUri").takeIf { it.isNotBlank() } else null,
                                timestamp = msgTimestamp
                            )
                        )
                    }
                    val cleanedMsgs = msgs.filterNot {
                        it.content.contains("I'm your dedicated Vanta Coach", ignoreCase = true) ||
                        it.content.startsWith("Hey athlete.", ignoreCase = true)
                    }
                    // NEVER save or load empty sessions
                    if (cleanedMsgs.isEmpty()) continue

                    list.add(
                        ChatSession(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            timestamp = sessionTimestamp,
                            messages = cleanedMsgs
                        )
                    )
                }
                _sessions.value = list
                _currentSession.value = list.firstOrNull() ?: createNewSession()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun persistSessions() {
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_MILLIS
        try {
            val array = JSONArray()
            for (s in _sessions.value) {
                // NEVER save sessions without messages or older than 7 days
                if (s.messages.isEmpty() || s.timestamp < cutoff) continue
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("title", s.title)
                obj.put("timestamp", s.timestamp)
                val msgArray = JSONArray()
                for (m in s.messages) {
                    if (m.timestamp < cutoff) continue
                    val mObj = JSONObject()
                    mObj.put("id", m.id)
                    mObj.put("role", m.role)
                    mObj.put("content", m.content)
                    if (m.imageUri != null) {
                        mObj.put("imageUri", m.imageUri)
                    }
                    mObj.put("timestamp", m.timestamp)
                    msgArray.put(mObj)
                }
                if (msgArray.length() == 0) continue
                obj.put("messages", msgArray)
                array.put(obj)
            }
            prefs.edit().putString("chat_sessions_json", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
