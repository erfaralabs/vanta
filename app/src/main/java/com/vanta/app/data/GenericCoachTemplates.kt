package com.vanta.app.data

/**
 * Generic, data-grounded Vanta Coach overview templates used on the Home screen
 * whenever the AI (cloud or on-device) is unavailable or offline.
 *
 * Every template is built from placeholders that always exist — recovery {r},
 * energy {e}, and strain {s} — so ANY telemetry state produces a meaningful,
 * complete 2–3 sentence briefing. Workouts are deliberately never referenced
 * (a 0-minute day must never be called out), and each pool rotates by day.
 */
object GenericCoachTemplates {

    // ── Daytime · Recovery >= 85 ──────────────────────────────────────────────
    val DAY_HIGH = listOf(
        "You're at {r}% recovery with {e}% energy in the tank. This is a go-day — push the intensity while your body is on your side.",
        "{r}% recovery is a green light. Energy reads {e}%, so make today's main work count and trust the engine.",
        "Recovery sits at {r}% — a rare day to lean in. Run your hardest session early while freshness is at its peak.",
        "At {r}% recovery and {e}% energy, everything is pointing up. Train with intent, then protect the work with good rest.",
        "Your body is ready: {r}% recovery, {e}% energy. Hit your key session with confidence today.",
        "{r}% recovery and a full {e}% energy tank — the ideal window for progressive overload. Make it productive.",
        "Fresh legs at {r}% recovery. The {e}% energy reading confirms it — this is the day to test yourself."
    )

    // ── Daytime · Recovery 70–84 ──────────────────────────────────────────────
    val DAY_GOOD = listOf(
        "You're at {r}% recovery with {e}% energy available. Solid footing — execute your session with sharp focus.",
        "{r}% recovery is a good foundation. Train well today, keep volume honest, and skip the fluff.",
        "Recovery reads {r}% with energy at {e}%. You have enough to train well — keep quality high and stay disciplined.",
        "At {r}% recovery, today rewards steady, quality work. Your {e}% energy supports a productive session.",
        "{r}% recovery and {e}% energy means a capable day. Hit your main lift or run, then recover properly.",
        "Solid {r}% recovery this morning. Train with purpose, respect your limits, and bank the gains.",
        "You've got {r}% recovery behind you today. Use it for focused work — then let recovery do its job."
    )

    // ── Daytime · Recovery 55–69 ──────────────────────────────────────────────
    val DAY_MODERATE = listOf(
        "Recovery is at {r}% with energy at {e}%. Keep today moderate — quality over volume.",
        "{r}% recovery means your body is still working. Do the essentials, skip the extras.",
        "At {r}% recovery, prioritize consistency over intensity. Energy at {e}% is enough for honest work.",
        "Moderate {r}% recovery today. Stay in your zone, hydrate well, and don't chase numbers.",
        "Your body is at {r}% recovery — a day for maintenance and smart movement, not heroics.",
        "{r}% recovery with {e}% energy reads as a steady day. Keep the effort controlled.",
        "Recovery at {r}% — manageable, not maximal. Choose the work that moves you forward without breaking you down."
    )

    // ── Daytime · Recovery < 55 ───────────────────────────────────────────────
    val DAY_LOW = listOf(
        "Recovery is low at {r}% with energy at {e}%. Today is about active recovery and rest — not output.",
        "{r}% recovery signals your body needs a lighter day. Move gently, sleep well, and let it rebuild.",
        "At {r}% recovery, forcing a hard session would cost you days. Choose easy movement and recovery rituals.",
        "Your body is asking for rest at {r}% recovery. Light activity, good food, early night — that's today's win.",
        "Recovery sits at {r}% — a stay-easy day. Protect tomorrow by not digging the hole deeper.",
        "Low recovery ({r}%) means the tank is refilling. Keep the day gentle and prioritize sleep tonight.",
        "{r}% recovery is a signal, not a failure. Rest now so you can return stronger tomorrow."
    )

    // ── Evening / Night · Recovery >= 85 ──────────────────────────────────────
    val EVE_HIGH = listOf(
        "You're ending the day at {r}% recovery with {s} strain logged. Bank the freshness with a solid night's sleep.",
        "{r}% recovery carried you through {s} strain today. Keep the evening relaxed and protect tomorrow's edge.",
        "Recovery sits at {r}% as you wrap up ({s} strain today). Wind down early to keep the momentum.",
        "A {r}% recovery day with {s} strain in the books. Unwind now — tomorrow stays yours.",
        "{r}% recovery at the end of the day. Keep it low-key tonight and wake up ready to push.",
        "Strong {r}% recovery after {s} strain. Tonight's sleep locks in everything you built today.",
        "You finished strong: {r}% recovery, {s} strain banked. Rest well and let the body do its work."
    )

    // ── Evening / Night · Recovery 70–84 ──────────────────────────────────────
    val EVE_GOOD = listOf(
        "The day closed at {r}% recovery with {s} strain logged. Unwind and bank good sleep to lock in tomorrow's gains.",
        "{r}% recovery after {s} strain — a balanced day. Focus on wind-down and hydration tonight.",
        "Recovery holds at {r}% as the day ends ({s} strain). Keep the evening calm and protect your sleep.",
        "A solid {r}% recovery day behind you ({s} strain). Prepare tonight's routine for a strong tomorrow.",
        "{r}% recovery is a good place to finish. Relax, eat clean, and get to bed at a consistent time.",
        "Day done at {r}% recovery ({s} strain). The evening is for recovery — keep it low stimulus.",
        "You closed at {r}% recovery. Sleep well tonight and tomorrow starts from a good place."
    )



    // ── Evening / Night · Recovery 55–69 ──────────────────────────────────────
    val EVE_MODERATE = listOf(
        "You finished the day at {r}% recovery with {s} strain. Keep the evening gentle and prioritize sleep.",
        "{r}% recovery after {s} strain — a steady day. Tonight's rest is what tips tomorrow higher.",
        "Recovery sits at {r}% as the day winds down ({s} strain). Early sleep and low stimulation from here.",
        "A {r}% recovery day in the books ({s} strain). Wind down without screens and let recovery run.",
        "{r}% recovery at day's end. Keep it quiet tonight — tomorrow will thank you.",
        "Moderate {r}% recovery to close the day ({s} strain). Protect tonight's sleep above all.",
        "Day's end at {r}% recovery. A calm evening and early night rebuilds the tank."
    )

    // ── Evening / Night · Recovery < 55 ───────────────────────────────────────
    val EVE_LOW = listOf(
        "Recovery is low at {r}% after {s} strain today. Tonight's priority is rest — nothing else matters more.",
        "{r}% recovery at the end of the day ({s} strain). The fastest way back is an early, screen-free night.",
        "A tough day closes at {r}% recovery. Sleep now; recovery compounds while you rest.",
        "Recovery sits low ({r}%) after {s} strain. Ease into bed early — tomorrow needs the reserve.",
        "The day ends at {r}% recovery. Hydrate, wind down, and make tonight fully restorative.",
        "{r}% recovery signals a heavy load today ({s} strain). Recovery starts the moment you rest.",
        "Low recovery ({r}%) after {s} strain. Skip any late stimulus and give the body the night it needs."
    )

    // ── Data-limited (very early morning / minimal telemetry yet) ─────────────
    val DATA_LIMITED = listOf(
        "The day is just getting started — recovery reads {r}% and energy {e}%. The numbers will sharpen as you move.",
        "Morning baseline: {r}% recovery, {e}% energy. Early data is light, so let the day build before judging it.",
        "{r}% recovery to open the day. The full picture forms as telemetry streams in — start moving and it'll come to life.",
        "A fresh day at {r}% recovery. More data lands as you go; for now, set your intention and start well."
    )

    /** Renders placeholders into a template. {r}=recovery%, {e}=energy%, {s}=strain. */
    fun render(template: String, recovery: Int, energy: Int, strain: Double): String =
        template
            .replace("{r}", "$recovery")
            .replace("{e}", "$energy")
            .replace("{s}", "%.1f".format(strain))
}
