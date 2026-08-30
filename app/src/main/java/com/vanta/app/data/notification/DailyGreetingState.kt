package com.vanta.app.data.notification

import android.content.Context

/**
 * Cross-mechanism dedupe for the daily warm greetings (Good morning / Good
 * afternoon / Wind down).
 *
 * The scheduled AlarmManager check-ins ([CheckInReceiver]) and the AI engine's
 * intraday greeting detectors ([AiNotificationEngine]) used to independently fire
 * overlapping "morning recovery" / "morning check-in" / "afternoon" / "evening"
 * notifications, so a single morning could stack several near-identical recovery
 * notifications.
 *
 * Both mechanisms now share one flag per greeting slot and store it in the same
 * SharedPreferences ("vanta_notification_state"). A given window fires at most
 * ONCE per day — whichever mechanism claims the slot first wins, and the other
 * defers — so the user gets exactly one warm greeting per time-block.
 */
object DailyGreetingState {

    private const val PREFS = "vanta_notification_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Maps each mechanism's reason string to its canonical daily greeting slot.
     * Returns null for real event notifications (workout, strain, milestone,
     * weekly, goal) which are not greetings and never dedupe on this flag.
     */
    fun slotForReason(reason: String): String? = when (reason) {
        "recovery", "morning" -> "morning"
        "afternoon" -> "afternoon"
        "evening", "night" -> "evening"
        else -> null
    }

    /** True when this greeting window has already been claimed (by any mechanism) today. */
    fun alreadyGreeted(context: Context, today: String, reason: String): Boolean {
        val slot = slotForReason(reason) ?: return false
        return prefs(context).getBoolean("greeting_$slot-$today", false)
    }

    /** Claims this greeting slot for today so a concurrent source defers to it. */
    fun markGreeted(context: Context, today: String, reason: String) {
        val slot = slotForReason(reason) ?: return
        prefs(context).edit().putBoolean("greeting_$slot-$today", true).apply()
    }
}
