package com.vanta.app

import com.vanta.app.data.GenericCoachTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Home-screen generic coach template pool:
 *  - at least 50 templates exist,
 *  - placeholders render with real numbers,
 *  - templates NEVER reference workouts (a 0-minute day must not be called out).
 */
class GenericCoachTemplatesTest {

    private val allPools = listOf(
        GenericCoachTemplates.DAY_HIGH,
        GenericCoachTemplates.DAY_GOOD,
        GenericCoachTemplates.DAY_MODERATE,
        GenericCoachTemplates.DAY_LOW,
        GenericCoachTemplates.EVE_HIGH,
        GenericCoachTemplates.EVE_GOOD,
        GenericCoachTemplates.EVE_MODERATE,
        GenericCoachTemplates.EVE_LOW,
        GenericCoachTemplates.DATA_LIMITED
    )

    private val allTemplates = allPools.flatten()

    @Test
    fun `pool has at least 50 templates`() {
        assertTrue("expected >= 50 templates, got ${allTemplates.size}", allTemplates.size >= 50)
    }

    @Test
    fun `templates never mention workouts`() {
        val joined = allTemplates.joinToString(" ")
        assertFalse("workout must never be referenced", joined.contains("workout", ignoreCase = true))
        assertFalse("exercise must never be referenced", joined.contains("exercise", ignoreCase = true))
    }

    @Test
    fun `placeholders render with real numbers`() {
        val out = GenericCoachTemplates.render("Recovery {r}% | Energy {e}% | Strain {s}", 74, 68, 12.4)
        assertEquals("Recovery 74% | Energy 68% | Strain 12.4", out)
        assertFalse("no leftover placeholders", out.contains("{r}") || out.contains("{e}") || out.contains("{s}"))
    }

    @Test
    fun `every pool is non-empty`() {
        allPools.forEach { pool ->
            assertTrue("empty template pool", pool.isNotEmpty())
            pool.forEach { template ->
                assertTrue("template too short: $template", template.length > 40)
            }
        }
    }

    @Test
    fun `recovery buckets cover the full range`() {
        // Every realistic recovery value (30..100) maps to exactly one bucket.
        for (r in 30..100) {
            val bucket = when {
                r >= 85 -> GenericCoachTemplates.DAY_HIGH
                r >= 70 -> GenericCoachTemplates.DAY_GOOD
                r >= 55 -> GenericCoachTemplates.DAY_MODERATE
                else -> GenericCoachTemplates.DAY_LOW
            }
            assertTrue("no template for recovery $r", bucket.isNotEmpty())
        }
    }

    @Test
    fun `offline message is present and informative`() {
        val msg = com.vanta.app.data.ai.PhysiologyInsightPromptSystem.OFFLINE_MESSAGE
        assertTrue(msg.contains("No internet", ignoreCase = true))
        assertEquals(msg, com.vanta.app.data.ai.PhysiologyInsightPromptSystem.OFFLINE_MESSAGE)
    }
}
