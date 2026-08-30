package com.vanta.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vanta.app.R
import com.vanta.app.data.db.VantaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fires the scheduled morning (08:10) and night (21:30) template check-ins.
 *
 * Reads the user's REAL latest record from Room DB (recovery / energy / strain /
 * steps) and the profile name, then posts a warm, deterministic message. Never
 * calls any AI provider — zero budget, works offline, always personal.
 */
class CheckInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != CheckInScheduler.ACTION_MORNING && action != CheckInScheduler.ACTION_NIGHT) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = VantaDatabase.getInstance(context)
                val today = LocalDate.now().toString()
                val record = runCatching { db.dailyMetricsDao().getRecordForDate(today) }.getOrNull()
                val profile = runCatching { db.userProfileDao().getUserProfile() }.getOrNull()
                val name = profile?.name?.trim().orEmpty().replaceFirstChar { it.uppercase() }

                when (action) {
                    CheckInScheduler.ACTION_MORNING -> postMorning(context, name, record?.recovery, record?.energy, record?.strain)
                    CheckInScheduler.ACTION_NIGHT -> postNight(context, name, record?.strain, record?.steps)
                }
            } finally {
                // Re-arm tomorrow's alarm so the chain continues until the user opts out.
                runCatching { CheckInScheduler.scheduleAll(context) }
                pendingResult.finish()
            }
        }
    }

    private fun postMorning(context: Context, name: String, recovery: Int?, energy: Int?, strain: Double?) {
        val today = LocalDate.now().toString()
        // A "Good morning" greeting already fired this window (the engine fallback,
        // or a duplicate alarm) — never double up. This is what keeps a morning to
        // exactly ONE warm notification instead of a pile of "morning recovery" dupes.
        if (DailyGreetingState.alreadyGreeted(context, today, "morning")) return
        val greeting = if (name.isNotBlank()) context.getString(R.string.good_morning_name, name) else context.getString(R.string.good_morning_plain)
        val dataLine = when {
            recovery != null && energy != null -> "$recovery% recovered, $energy% energy."
            recovery != null -> "$recovery% recovered."
            else -> "A fresh day is waiting."
        }
        val close = MORNING_CLOSERS[dayOfYear() % MORNING_CLOSERS.size]
        val posted = NotificationPoster.post(
            context,
            NotificationDecision(
                notify = true,
                title = context.getString(R.string.notif_good_morning),
                message = "$greeting $dataLine $close",
                priority = "normal",
                reason = "morning"
            )
        )
        if (posted) DailyGreetingState.markGreeted(context, today, "morning")
    }

    private fun postNight(context: Context, name: String, strain: Double?, steps: Long?) {
        val today = LocalDate.now().toString()
        // The engine's "wind down" (evening) and this night check-in share one daily
        // slot — whichever fires first wins, so exactly ONE wind-down shows per night.
        if (DailyGreetingState.alreadyGreeted(context, today, "night")) return

        val greeting = if (name.isNotBlank()) context.getString(R.string.good_night_name, name) else context.getString(R.string.good_night_plain)
        val dataLine = buildString {
            append("Today: ")
            val parts = mutableListOf<String>()
            strain?.let { parts.add("strain ${"%.1f".format(it)}") }
            steps?.let { parts.add("${it} steps") }
            if (parts.isNotEmpty()) append(parts.joinToString(", ")) else append("a full day on the board")
            append(".")
        }
        val close = NIGHT_CLOSERS[dayOfYear() % NIGHT_CLOSERS.size]
        val posted = NotificationPoster.post(
            context,
            NotificationDecision(
                notify = true,
                title = context.getString(R.string.notif_wind_down),
                message = "$greeting $dataLine $close",
                priority = "normal",
                reason = "night"
            )
        )
        if (posted) DailyGreetingState.markGreeted(context, today, "night")
    }

    private fun dayOfYear(): Int = LocalDate.now().dayOfYear

    private companion object {
        val MORNING_CLOSERS = listOf(
            "Ready when you are.",
            "Let's see what today brings.",
            "A fresh day starts here.",
            "Take it steady and build.",
            "The day is yours to shape.",
            "Room to move — use it well."
        )
        val NIGHT_CLOSERS = listOf(
            "Rest well — the work's done.",
            "Settle in and recover.",
            "Tomorrow is another chapter.",
            "The best recovery starts now.",
            "Close the day, recharge, repeat.",
            "Sleep well — the body thanks you."
        )
    }
}
