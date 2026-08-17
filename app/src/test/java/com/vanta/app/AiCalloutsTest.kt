package com.vanta.app

import com.vanta.app.data.GemmaCallout
import com.vanta.app.data.VantaGemmaEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the cloud AI callout pipeline:
 *  - valid model chips pass through with palette colors,
 *  - blank / too-long / off-palette chips are rejected,
 *  - the deterministic metric callouts always pad the section to >= 3 chips
 *    so the AI COACH card can never show junk or empty rows when offline.
 */
class AiCalloutsTest {

    private val fallback = listOf(
        GemmaCallout("You're at 77% recovery — a solid build day.", "#39FF80"),
        GemmaCallout("8.3 strain so far, near your weekly average.", "#00F2FE"),
        GemmaCallout("Only 1,200 steps yet — a walk rounds out the day.", "#00F2FE")
    )

    private fun modelJson(chips: List<Pair<String, String>>): JSONObject =
        JSONObject().put(
            "callouts",
            JSONArray().apply {
                chips.forEach { (text, color) ->
                    put(JSONObject().put("text", text).put("colorHex", color))
                }
            }
        )

    @Test
    fun `valid model chips are parsed with their palette colors`() {
        val obj = modelJson(listOf(
            "77% recovery means a green light today" to "#39FF80",
            "Strain is 8.3, light next to your week" to "#FFB000",
            "Resting HR at 58 is 3 below your norm" to "#BF5AF2"
        ))
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        assertEquals(3, result.size)
        assertEquals("#39FF80", result[0].colorHex)
        assertEquals("#BF5AF2", result[2].colorHex)
        assertTrue(result.all { it.text.isNotBlank() })
    }

    @Test
    fun `blank too-short and too-long chips are dropped`() {
        val obj = modelJson(listOf(
            "Great recovery today, keep it up" to "#39FF80",
            "   " to "#39FF80",                          // blank
            "ok" to "#FFB000",                            // too short
            "A".repeat(150) to "#FF5252"                  // too long
        ))
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        // Only the one valid chip survives; the section is padded back up to 3.
        assertTrue("invalid chips must be dropped", result.none { it.text == "ok" || it.text == "   " })
        assertTrue("long chip must be dropped", result.none { it.text.length > 110 })
        assertEquals("Great recovery today, keep it up", result.first().text)
        assertEquals(3, result.size)
        assertTrue(result.subList(1, 3).all { fallback.contains(it) })
    }

    @Test
    fun `off-palette colors fall back to neutral cyan and lowercase is accepted`() {
        val obj = modelJson(listOf(
            "Strain is 8.3 so far, a real session" to "#123456",   // invalid color
            "Resting HR sits at 58, ready again" to "#39ff80"      // valid but lowercase
        ))
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        assertTrue(result.any { it.text.startsWith("Strain") && it.colorHex == "#00F2FE" })
        assertTrue(result.any { it.text.startsWith("Resting") && it.colorHex == "#39FF80" })
    }

    @Test
    fun `fewer than 3 usable chips are padded with the metric fallback`() {
        val obj = modelJson(listOf(
            "Only one solid chip came back today" to "#39FF80"
        ))
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        assertTrue("AI section must never show fewer than 3 callouts", result.size >= 3)
        assertTrue(result.contains(fallback[1]))
    }

    @Test
    fun `duplicate chips are deduplicated`() {
        val obj = modelJson(listOf(
            "Strain is 8.3, a real day's work" to "#39FF80",
            "Strain is 8.3, a real day's work" to "#39FF80",
            "Resting HR at 58 is 3 below your norm" to "#BF5AF2"
        ))
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        // Dedup keeps one copy of each; a fallback chip pads the section to 3.
        assertEquals(1, result.count { it.text.startsWith("Strain is 8.3") })
        assertEquals(1, result.count { it.text.startsWith("Resting HR at 58") })
        assertEquals(3, result.size)
        assertEquals(3, result.distinctBy { it.text.lowercase() }.size)
    }

    @Test
    fun `missing callouts array falls back entirely to the template`() {
        val obj = JSONObject().put("overview", "Only an overview was returned.")
        val result = VantaGemmaEngine.parseModelCallouts(obj, fallback)
        assertEquals(fallback, result)
    }
}
