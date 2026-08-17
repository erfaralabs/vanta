package com.vanta.app.data.profile

import android.content.Context
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.UserProfileRecord
import com.vanta.app.data.db.VantaDatabase
import com.vanta.app.data.notification.NotificationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vanta Profile Backup — exports/imports the user's full on-device profile as a
 * versioned `.vanta` file.
 *
 * File format:
 *
 *   VANTA1\n                                <- magic prefix + format version
 *   { "format": "vanta-profile", ... }     <- JSON payload (org.json)
 *
 * The magic header means a random file can never be misread as a profile, and the
 * version field keeps the format evolvable. API keys are NEVER exported — secrets
 * stay on-device.
 */
object ProfileBackup {

    const val FILE_EXTENSION = ".vanta"
    private const val MAGIC_PREFIX = "VANTA"
    private const val VERSION = 1
    private const val HEADER = "$MAGIC_PREFIX$VERSION\n"
    private const val FORMAT_ID = "vanta-profile"

    data class NotificationPrefs(
        val enabled: Boolean = true,
        val morningRecovery: Boolean = true,
        val workout: Boolean = true,
        val strain: Boolean = true,
        val achievement: Boolean = true,
        val weekly: Boolean = true,
        val goals: Boolean = true,
        val heartRate: Boolean = false,
        val morningCheckIn: Boolean = true,
        val nightCheckIn: Boolean = true
    )

    fun suggestedFileName(): String =
        "VantaProfile_${java.time.LocalDate.now()}$FILE_EXTENSION"

    /**
     * Serializes profile + full training history + notification preferences.
     * Pure data, safe to write to the file system.
     */
    suspend fun export(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val db = VantaDatabase.getInstance(context)
        val records = db.dailyMetricsDao().getAllRecords()
        val profile = db.userProfileDao().getUserProfile()
        val ns = NotificationSettings(context)

        val payload = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            profile?.let { put("profile", it.toJson()) }
            val metrics = JSONArray()
            records.forEach { metrics.put(it.toJson()) }
            put("dailyMetrics", metrics)
            put(
                "notificationSettings",
                JSONObject().apply {
                    put("enabled", ns.enabled)
                    put("morningRecovery", ns.morningRecovery)
                    put("workout", ns.workout)
                    put("strain", ns.strain)
                    put("achievement", ns.achievement)
                    put("weekly", ns.weekly)
                    put("goals", ns.goals)
                    put("heartRate", ns.heartRate)
                    put("morningCheckIn", ns.morningCheckIn)
                    put("nightCheckIn", ns.nightCheckIn)
                    put("aiLimitPerDay", ns.aiLimitPerDay)
                }
            )
        }

        (HEADER + payload.toString()).toByteArray(Charsets.UTF_8)
    }

    /**
     * Imports a `.vanta` profile, fully replacing on-device history, profile and
     * notification preferences. Returns a human-readable success summary, or
     * null when the file is invalid / not a Vanta profile.
     */
    suspend fun import(context: Context, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = bytes.toString(Charsets.UTF_8)
            check(text.startsWith(HEADER)) { "Not a Vanta profile (bad magic/version)" }
            val obj = JSONObject(text.substring(HEADER.length))
            check(obj.optString("format") == FORMAT_ID) { "Unsupported Vanta profile format" }

            // 1. Notification preferences
            val ns = NotificationSettings(context)
            val n = obj.optJSONObject("notificationSettings")
            if (n != null) {
                ns.enabled = n.optBoolean("enabled", ns.enabled)
                ns.morningRecovery = n.optBoolean("morningRecovery", ns.morningRecovery)
                ns.workout = n.optBoolean("workout", ns.workout)
                ns.strain = n.optBoolean("strain", ns.strain)
                ns.achievement = n.optBoolean("achievement", ns.achievement)
                ns.weekly = n.optBoolean("weekly", ns.weekly)
                ns.goals = n.optBoolean("goals", ns.goals)
                ns.heartRate = n.optBoolean("heartRate", ns.heartRate)
                ns.morningCheckIn = n.optBoolean("morningCheckIn", ns.morningCheckIn)
                ns.nightCheckIn = n.optBoolean("nightCheckIn", ns.nightCheckIn)
                ns.aiLimitPerDay = n.optInt("aiLimitPerDay", ns.aiLimitPerDay)
            }

            val db = VantaDatabase.getInstance(context)

            // 2. User profile (upsert by primary key 1)
            obj.optJSONObject("profile")?.let { p ->
                db.userProfileDao().insertOrUpdateProfile(
                    UserProfileRecord(
                        id = 1,
                        name = p.optString("name", "Athlete"),
                        birthdateStr = p.optString("birthdateStr", "1999-01-01"),
                        age = p.optInt("age", 27),
                        heightCm = p.optDouble("heightCm", 178.0),
                        weightKg = p.optDouble("weightKg", 75.0),
                        sex = p.optString("sex", "Not Specified"),
                        fitnessGoal = p.optString("fitnessGoal", "General Fitness"),
                        stepsGoal = p.optInt("stepsGoal", 10000).coerceIn(1000, 100000),
                        avatarKey = p.optString("avatarKey", "avatar1"),
                        isOnboardingCompleted = p.optBoolean("isOnboardingCompleted", true),
                        createdAtTimestamp = p.optLong("createdAtTimestamp", System.currentTimeMillis())
                    )
                )
            }

            // 3. Daily metric history (full replace)
            val dao = db.dailyMetricsDao()
            dao.deleteAllRecords()
            val arr = obj.optJSONArray("dailyMetrics")
            var inserted = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val date = m.optString("date", "")
                    if (date.isBlank()) continue
                    dao.insertOrUpdate(
                        DailyMetricRecord(
                            date = date,
                            timestamp = m.optLong("timestamp", 0L),
                            restingBpm = m.optInt("restingBpm", 0),
                            avgBpm = m.optInt("avgBpm", 0),
                            maxBpm = m.optInt("maxBpm", 0),
                            steps = m.optLong("steps", 0L),
                            calories = m.optLong("calories", 0L),
                            distanceKm = m.optDouble("distanceKm", 0.0),
                            workoutDurationMin = m.optInt("workoutDurationMin", 0),
                            strain = m.optDouble("strain", 0.0),
                            recovery = m.optInt("recovery", 0),
                            energy = m.optInt("energy", 0)
                        )
                    )
                    inserted++
                }
            }

            "Profile restored — $inserted days of history ✓"
        }.getOrElse { e ->
            e.printStackTrace()
            null
        }
    }

    // ── JSON serializers ────────────────────────────────────────────────────────

    private fun UserProfileRecord.toJson() = JSONObject().apply {
        put("name", name)
        put("birthdateStr", birthdateStr)
        put("age", age)
        put("heightCm", heightCm)
        put("weightKg", weightKg)
        put("sex", sex)
        put("fitnessGoal", fitnessGoal)
        put("stepsGoal", stepsGoal)
        put("avatarKey", avatarKey)
        put("isOnboardingCompleted", isOnboardingCompleted)
        put("createdAtTimestamp", createdAtTimestamp)
    }

    private fun DailyMetricRecord.toJson() = JSONObject().apply {
        put("date", date)
        put("timestamp", timestamp)
        put("restingBpm", restingBpm)
        put("avgBpm", avgBpm)
        put("maxBpm", maxBpm)
        put("steps", steps)
        put("calories", calories)
        put("distanceKm", distanceKm)
        put("workoutDurationMin", workoutDurationMin)
        put("strain", strain)
        put("recovery", recovery)
        put("energy", energy)
    }
}

