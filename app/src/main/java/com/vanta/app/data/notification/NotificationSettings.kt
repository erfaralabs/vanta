package com.vanta.app.data.notification

import android.content.Context
import android.content.SharedPreferences

/**
 * User-controllable AI notification preferences, stored in their own
 * SharedPreferences file so background workers can read them without the UI.
 *
 * The user does not wear their watch every day, so Heart Rate insights default
 * OFF: strain-spike and recovery-change notifications (which are driven by HR /
 * RHR data) are suppressed and no message ever mentions heart rate.
 */
class NotificationSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vanta_notification_settings", Context.MODE_PRIVATE)

    /** Master switch. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var morningRecovery: Boolean
        get() = prefs.getBoolean("cat_morning_recovery", true)
        set(value) = prefs.edit().putBoolean("cat_morning_recovery", value).apply()

    var workout: Boolean
        get() = prefs.getBoolean("cat_workout", true)
        set(value) = prefs.edit().putBoolean("cat_workout", value).apply()

    var strain: Boolean
        get() = prefs.getBoolean("cat_strain", true)
        set(value) = prefs.edit().putBoolean("cat_strain", value).apply()

    var achievement: Boolean
        get() = prefs.getBoolean("cat_achievement", true)
        set(value) = prefs.edit().putBoolean("cat_achievement", value).apply()

    var weekly: Boolean
        get() = prefs.getBoolean("cat_weekly", true)
        set(value) = prefs.edit().putBoolean("cat_weekly", value).apply()

    var goals: Boolean
        get() = prefs.getBoolean("cat_goals", true)
        set(value) = prefs.edit().putBoolean("cat_goals", value).apply()

    /** HR-driven insights (resting-HR based recovery, HR-based strain). */
    var heartRate: Boolean
        get() = prefs.getBoolean("cat_heart_rate", false)
        set(value) = prefs.edit().putBoolean("cat_heart_rate", value).apply()

    /** Scheduled warm morning check-in (08:10). Template-based, never AI. */
    var morningCheckIn: Boolean
        get() = prefs.getBoolean("cat_morning_checkin", true)
        set(value) = prefs.edit().putBoolean("cat_morning_checkin", value).apply()

    /** Scheduled evening check-in (21:30). Template-based, never AI. */
    var nightCheckIn: Boolean
        get() = prefs.getBoolean("cat_night_checkin", true)
        set(value) = prefs.edit().putBoolean("cat_night_checkin", value).apply()

    /** Dynamic intraday WHOOP-style athletic nudges (midday, afternoon, evening). */
    var intradayNudges: Boolean
        get() = prefs.getBoolean("cat_intraday_nudges", true)
        set(value) = prefs.edit().putBoolean("cat_intraday_nudges", value).apply()

    /** AI-written notifications allowed per day (3–10); above this, premium templates. */
    var aiLimitPerDay: Int
        get() = prefs.getInt("ai_limit_per_day", 3).coerceIn(3, 10)
        set(value) = prefs.edit().putInt("ai_limit_per_day", value.coerceIn(3, 10)).apply()

    fun categoryEnabled(reason: String): Boolean = when (reason) {
        "recovery" -> morningRecovery
        "workout" -> workout
        "strain" -> strain
        "achievement" -> achievement
        "weekly" -> weekly
        "goal" -> goals
        "midday", "afternoon", "evening" -> intradayNudges
        else -> true
    }
}
