package com.vanta.app

import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.data.ai.PhysiologyInsightPromptSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies extractProseFromRaw — the resilience layer that guarantees the
 * Strain/Recovery/Energy detail page never renders raw JSON or truncated junk
 * from either cloud providers or the on-device Gemma 4 E2B model.
 */
class AiProseExtractionTest {

    @Test
    fun `plain prose passes through unchanged`() {
        val raw = "Recovery sits at 74%, matching your 7-day baseline. Push intensity while you have the window."
        assertEquals(raw, PhysiologyInsightPromptSystem.extractProseFromRaw(raw))
    }

    @Test
    fun `json wrapped insight is unwrapped`() {
        val raw = """{"insight": "Recovery holds at 74%, aligned with your baseline. Train with intent today."}"""
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        assertEquals("Recovery holds at 74%, aligned with your baseline. Train with intent today.", result)
    }

    @Test
    fun `markdown fenced json is unwrapped`() {
        val raw = "```json\n{\"insight\": \"Strain is at 8.3, near your weekly average. Bank the stimulus and hydrate.\"}\n```"
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        assertEquals("Strain is at 8.3, near your weekly average. Bank the stimulus and hydrate.", result)
    }

    @Test
    fun `truncated json still yields the partial insight text`() {
        val raw = """{"insight": "Energy is balanced at 68% and your circadian"""
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        assertTrue(result != null)
        assertTrue(result!!.startsWith("Energy is balanced at 68%"))
    }

    @Test
    fun `model preamble before json is ignored`() {
        val raw = """Here is your breakdown: {"text": "Your recovery is at 80% — a strong signal for a hard session."}"""
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        assertEquals("Your recovery is at 80% — a strong signal for a hard session.", result)
    }

    @Test
    fun `raw json junk with no usable text is rejected`() {
        val raw = """{"recovery": 74, "strain": 8.3, "energy": 68}"""
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        // The extractor must never hand back a bare JSON object for display.
        assertTrue(result == null || !result.startsWith("{"))
    }

    @Test
    fun `blank and too-short outputs are rejected`() {
        assertNull(PhysiologyInsightPromptSystem.extractProseFromRaw(""))
        assertNull(PhysiologyInsightPromptSystem.extractProseFromRaw("   \n  "))
        assertNull(PhysiologyInsightPromptSystem.extractProseFromRaw("OK"))
    }

    @Test
    fun `stray json braces and label prefixes are stripped`() {
        val raw = """Insight: {"overview": "Your strain hit 9.4 today."}"""
        val result = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        assertEquals("Your strain hit 9.4 today.", result)
    }

    @Test
    fun `completeness guard accepts finished prose and rejects fragments`() {
        // Complete: ends in sentence punctuation, long enough, no JSON tokens.
        assertTrue(VantaGemmaEngine.isCompleteProse("Your recovery sits at 74% and energy at 68%. Keep the evening light."))
        assertTrue(VantaGemmaEngine.isCompleteProse("Recovery holds at 74% today. Train with intent. Sleep early."))
        // Truncated / junk: must be rejected so the deterministic fallback takes over.
        assertFalse(VantaGemmaEngine.isCompleteProse("Your recovery sits at 74% and your baseline is at"))
        assertFalse(VantaGemmaEngine.isCompleteProse("Your energy sits at 68%"))
        assertFalse(VantaGemmaEngine.isCompleteProse("""{"overview": "Your recovery sits at 74%."}"""))
        assertFalse(VantaGemmaEngine.isCompleteProse("json {recovery:74}"))
        assertFalse(VantaGemmaEngine.isCompleteProse(""))
        assertFalse(VantaGemmaEngine.isCompleteProse("OK"))
    }
}
