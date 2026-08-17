package com.vanta.app.data.ai

import android.content.Context
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.ui.screens.PhysiologyMetric
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dynamic, Time-Aware, and History-Grounded Client-Side Safety Net & AI Template Generator.
 * Compares today's state against yesterday's real output to ensure every day's analysis
 * is unique, responsive, and grounded in active trends.
 */
object PhysiologyTemplateSelector {

    private enum class TimeBlock {
        MORNING, AFTERNOON, EVENING, NIGHT;

        companion object {
            fun current(): TimeBlock {
                val hour = LocalTime.now(ZoneId.systemDefault()).hour
                return when (hour) {
                    in 5..11 -> MORNING
                    in 12..16 -> AFTERNOON
                    in 17..20 -> EVENING
                    else -> NIGHT
                }
            }
        }
    }

    private val MEDICAL_DISALLOWED_KEYWORDS = setOf(
        "diagnose", "diagnosis", "disease", "cure", "treatment", "prescription",
        "heart failure", "arrhythmia", "pathology", "clinical", "pharmaceutical", "doctor"
    )

    fun cleanAndValidateAiResponse(raw: String, maxWords: Int = 45, maxSentences: Int = 3): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        var cleaned = trimmed
            .replace(Regex("(?i)^[#*\\s]*(?:analysis|breakdown|refinement|content|insight|summary|vanta\\s*coach|coach|sentence\\s*\\d+|and\\s+refinement)[^:\n]*[:\\-]?\\s*"), "")
            .replace(Regex("^(?:Sentence\\s*\\d+\\s*\\([^)]*\\)?)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Content:\\s*Sentence\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[*_#`\\\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val lower = cleaned.lowercase()
        val containsMedicalClaim = MEDICAL_DISALLOWED_KEYWORDS.any { lower.contains(it) }
        if (containsMedicalClaim) {
            return null
        }

        val sentences = cleaned.split(Regex("(?<=[.!?])\\s+"))
            .map { it.replace(Regex("(?i)^[#*\\s]*(?:analysis|breakdown|refinement|content|insight|summary|vanta\\s*coach|coach|sentence\\s*\\d+|and\\s+refinement)[^:\n]*[:\\-]?\\s*"), "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("Sentence", ignoreCase = true) && !it.startsWith("and ", ignoreCase = true) && !it.startsWith("or ", ignoreCase = true) }

        val candidate = sentences.take(maxSentences).joinToString(" ").trim()
        if (candidate.length < 15 || candidate.startsWith("{") || candidate.endsWith(":")) {
            return null
        }

        val words = candidate.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val finalResult = if (words.size > maxWords) {
            val sub = words.take(maxWords).joinToString(" ")
            val lastPeriod = sub.lastIndexOf('.')
            if (lastPeriod > 20) sub.substring(0, lastPeriod + 1) else "$sub."
        } else {
            candidate
        }

        return finalResult.ifBlank { null }
    }

    fun selectDeepTemplate(
        context: Context,
        metric: PhysiologyMetric,
        liveValue: Float,
        baseline: UserBaseline,
        telemetry: HealthConnectTelemetry,
        history: List<DailyMetricRecord>
    ): String {
        val todayStr = LocalDate.now(ZoneId.systemDefault()).toString()
        val ydayStr = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
        val yday = history.find { it.date == ydayStr } ?: history.sortedByDescending { it.date }.find { it.date < todayStr }

        return when (metric) {
            PhysiologyMetric.RECOVERY -> {
                val rec = liveValue.roundToInt().coerceIn(1, 100)
                val avgRec = if (baseline.avgRecovery > 0) baseline.avgRecovery.roundToInt() else 80
                val baselineDelta = rec - avgRec
                val ydayRec = yday?.recovery ?: 0

                val trendVerdict = when {
                    baselineDelta <= -4 && ydayRec > 0 && ydayRec <= avgRec - 3 ->
                        "Recovery stands at $rec%, marking consecutive days trailing below your 7-day baseline ($avgRec%)."
                    baselineDelta <= -4 ->
                        "Recovery dipped to $rec%, sitting ${abs(baselineDelta)}% below your 7-day baseline ($avgRec%)."
                    baselineDelta >= 4 ->
                        "Recovery reached $rec%, outpacing your 7-day baseline ($avgRec%)."
                    else ->
                        "Recovery holds at $rec%, matching your 7-day baseline of $avgRec%."
                }

                when {
                    rec >= 67 -> "$trendVerdict Parasympathetic tone was restored overnight, providing an optimal window for high-strain training and neural output. Cap hard physical efforts before 8 PM to protect tomorrow's recovery."
                    rec >= 34 -> "$trendVerdict Aerobic capacity is intact for Zone 2 work and steady-state maintenance. Maintain consistent fueling and prioritize sleep timing tonight."
                    else -> "$trendVerdict Autonomic telemetry signals residual fatigue from recent output. Focus on active recovery, hydration, and an early sleep window tonight."
                }
            }
            PhysiologyMetric.STRAIN -> {
                val str = "%.1f".format(liveValue)
                val avgStr = "%.1f".format(if (baseline.avgStrain > 0.0) baseline.avgStrain else 10.0)
                when {
                    liveValue >= 14.0 -> "Daily strain reached $str / 21.0, exceeding your 7-day average of $avgStr. High cardiovascular and muscular stimulus requires intentional parasympathetic down-regulation. Transition to hydration, mobility, and cool room temperatures tonight."
                    liveValue >= 8.0 -> "Strain has accumulated to $str / 21.0, aligning with your daily target and baseline of $avgStr. Muscular stimulus is balanced with current recovery status. Begin post-workout refuel with protein and electrolytes."
                    else -> "Today's strain is currently at $str / 21.0, below your 7-day average of $avgStr. Your cardiovascular system has significant remaining capacity for structured volume. Complete hard intervals during daylight hours."
                }
            }
            PhysiologyMetric.ENERGY -> {
                val nrg = liveValue.roundToInt().coerceIn(1, 100)
                val avgNrg = if (baseline.avgEnergy > 0) baseline.avgEnergy.roundToInt() else 75
                val baselineDelta = nrg - avgNrg
                val trendVerdict = when {
                    baselineDelta <= -4 -> "Energy reserves are reduced at $nrg%, sitting ${abs(baselineDelta)}% below your baseline ($avgNrg%)."
                    baselineDelta >= 4 -> "Energy reserves sit high at $nrg%, exceeding your baseline ($avgNrg%)."
                    else -> "Energy is balanced at $nrg%, aligned with your baseline of $avgNrg%."
                }
                when {
                    nrg >= 70 -> "$trendVerdict Circadian drive is strong, making this the primary window for demanding cognitive and physical tasks. Taper caffeine intake 8 hours before bed."
                    nrg >= 40 -> "$trendVerdict Pacing is key to prevent an evening crash. Ensure adequate hydration and balanced meals through the afternoon."
                    else -> "$trendVerdict Avoid late stimulants which disrupt slow-wave sleep. Transition into lower-intensity tasks and prepare for an early rest cycle."
                }
            }
        }
    }

    fun selectTemplate(
        context: Context,
        metric: PhysiologyMetric,
        liveValue: Float,
        baseline: UserBaseline,
        telemetry: HealthConnectTelemetry?,
        history: List<DailyMetricRecord> = emptyList()
    ): String {
        val todayStr = LocalDate.now(ZoneId.systemDefault()).toString()
        val ydayStr = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
        val yday = history.find { it.date == ydayStr } ?: history.sortedByDescending { it.date }.find { it.date < todayStr }

        return when (metric) {
            PhysiologyMetric.RECOVERY -> selectRecoveryTemplate(liveValue, baseline, yday)
            PhysiologyMetric.STRAIN -> selectStrainTemplate(liveValue, baseline, yday)
            PhysiologyMetric.ENERGY -> selectEnergyTemplate(liveValue, baseline, yday)
        }
    }

    private fun selectRecoveryTemplate(
        liveValue: Float,
        baseline: UserBaseline,
        yday: DailyMetricRecord?
    ): String {
        val valInt = liveValue.roundToInt()
        val normInt = if (baseline.avgRecovery > 0) baseline.avgRecovery.roundToInt() else 80
        val diff = valInt - normInt
        val tb = TimeBlock.current()

        val ydayRecovery = yday?.recovery
        val ydayStrain = yday?.strain
        val ydayContext = when {
            ydayRecovery != null && ydayStrain != null && ydayStrain >= 8.0 ->
                "Following yesterday's ${"%.1f".format(ydayStrain)} strain, recovery is logged at $valInt%."
            ydayRecovery != null -> {
                val delta = valInt - ydayRecovery
                when {
                    delta >= 4 -> "Recovery climbed +$delta% from yesterday's $ydayRecovery% to $valInt% today."
                    delta <= -4 -> "Recovery dropped -$delta% from yesterday's $ydayRecovery% to $valInt% today."
                    diff <= -4 && ydayRecovery <= normInt - 3 -> "Recovery holds at $valInt%, remaining below your 7-day baseline ($normInt%)."
                    else -> "Recovery is holding at $valInt%."
                }
            }
            else -> "Today's recovery is logged at $valInt%."
        }

        return when (tb) {
            TimeBlock.MORNING -> when {
                valInt >= 67 -> "$ydayContext Autonomic tone is fully primed — prime window for today's high-strain output."
                valInt >= 34 -> "$ydayContext Baseline readiness is balanced. Pace your morning effort steadily."
                else -> "$ydayContext Autonomic system is taxed — keep morning physical demands light."
            }
            TimeBlock.AFTERNOON -> when {
                valInt >= 67 -> "$ydayContext Afternoon resilience remains high. Strong capacity for remaining tasks."
                valInt >= 34 -> "$ydayContext Steady state holds through the afternoon. Keep hydration consistent."
                else -> "$ydayContext System strain detected — keep remaining physical output light today."
            }
            TimeBlock.EVENING -> when {
                valInt >= 67 -> "$ydayContext High recovery preserved through the day. Taper physical effort now to prepare for rest."
                valInt >= 34 -> "$ydayContext Steady baseline maintained today. Shift into evening wind-down mode."
                else -> "$ydayContext Lower recovery today. Prioritize an early wind-down and dim lighting."
            }
            TimeBlock.NIGHT -> when {
                valInt >= 67 -> "$ydayContext Prime foundation for deep cellular and muscular repair tonight."
                else -> "$ydayContext Shift into quiet rest mode to restore autonomic tone for tomorrow."
            }
        }
    }

    private fun selectStrainTemplate(
        liveValue: Float,
        baseline: UserBaseline,
        yday: DailyMetricRecord?
    ): String {
        val target = if (baseline.avgStrain > 0.0) baseline.avgStrain else 10.0
        val diff = liveValue.toDouble() - target
        val fmt = "%.1f".format(liveValue)
        val targetFmt = "%.1f".format(target)
        val tb = TimeBlock.current()

        val ydayStrain = yday?.strain
        val ydayContext = if (ydayStrain != null) {
            "vs yesterday's ${"%.1f".format(ydayStrain)}"
        } else ""

        return when (tb) {
            TimeBlock.MORNING -> when {
                liveValue < 3.0f -> "Morning strain at $fmt / 21.0 ($ydayContext). Fresh physiological tank — full capacity ready for your $targetFmt daily target."
                else -> "Morning strain already at $fmt / 21.0. Heavy early stimulus logged — balance remaining physical output."
            }
            TimeBlock.AFTERNOON -> when {
                diff >= 2.0 -> "Afternoon strain accumulated: $fmt / 21.0 ($ydayContext). High cardiovascular stimulus successfully banked."
                abs(diff) < 2.0 -> "Afternoon strain on target at $fmt / 21.0. Optimal physiological stimulus achieved."
                else -> "Afternoon strain at $fmt / 21.0. You have capacity remaining to build toward your $targetFmt target."
            }
            TimeBlock.EVENING -> when {
                diff >= 2.0 -> "Evening strain locked at $fmt / 21.0 ($ydayContext). Heavy exertion banked — shift focus to post-training recovery."
                abs(diff) < 2.0 -> "Evening strain solid at $fmt / 21.0 ($ydayContext). Balanced output — wind down for the night."
                else -> "Evening strain light at $fmt / 21.0. A light post-dinner walk will support sleep quality."
            }
            TimeBlock.NIGHT -> when {
                liveValue >= 8.0f -> "Night strain finalized at $fmt / 21.0 ($ydayContext). Physical stimulus complete — body is ready for deep sleep repair."
                else -> "Night strain closed at $fmt / 21.0. Day finished — begin evening wind-down."
            }
        }
    }

    private fun selectEnergyTemplate(
        liveValue: Float,
        baseline: UserBaseline,
        yday: DailyMetricRecord?
    ): String {
        val valInt = liveValue.roundToInt()
        val normInt = if (baseline.avgEnergy > 0) baseline.avgEnergy.roundToInt() else 75
        val diff = valInt - normInt
        val tb = TimeBlock.current()

        return when (tb) {
            TimeBlock.MORNING -> when {
                valInt >= 75 -> "Morning energy high at $valInt% ($diff% above norm). Circadian peak active — execute your highest priorities early."
                valInt >= 50 -> "Morning energy steady at $valInt% (norm $normInt%). Hydrate with water to kickstart metabolic drive."
                else -> "Morning energy at $valInt%. Get 10 minutes of direct sunlight exposure to boost cortisol alignment."
            }
            TimeBlock.AFTERNOON -> when {
                valInt >= 75 -> "Afternoon energy strong at $valInt%. High focus window open — avoid late caffeine to protect sleep."
                valInt >= 50 -> "Afternoon energy holding at $valInt%. Pacing is solid — take a brief stretch if focus dips."
                else -> "Afternoon energy dip ($valInt%). Circadian nadir active. Take a short walking break."
            }
            TimeBlock.EVENING -> when {
                valInt >= 60 -> "Evening energy at $valInt%. Energy tapering naturally. Transition to lower-intensity activities."
                else -> "Evening energy low ($valInt%). Body preparing for rest — avoid high-stress cognitive or physical tasks."
            }
            TimeBlock.NIGHT -> when {
                valInt >= 50 -> "Night energy sitting at $valInt%. Winding down for sleep — dim lights to trigger melatonin curve."
                else -> "Night energy depleted at $valInt%. Sleep pressure is high — ideal window for deep restorative sleep."
            }
        }
    }
}
