package com.vanta.app

import com.vanta.app.data.ai.CoachChatPromptSystem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the chat output sanitizer — assistant replies must never surface
 * model prefixes, markdown code fences, or JSON wrappers on screen.
 */
class ChatSanitizeTest {

    @Test
    fun `plain chat reply passes through unchanged`() {
        val reply = "Your recovery is primed for a hard session today."
        assertEquals(reply, CoachChatPromptSystem.sanitizeOutput(reply))
    }

    @Test
    fun `coach prefix is stripped`() {
        assertEquals(
            "Keep it light today.",
            CoachChatPromptSystem.sanitizeOutput("Vanta Coach: Keep it light today.")
        )
    }

    @Test
    fun `fenced json reply is unwrapped`() {
        val raw = "```json\n{\"response\": \"Your strain is at 12.4 — good work.\"}\n```"
        assertEquals("Your strain is at 12.4 — good work.", CoachChatPromptSystem.sanitizeOutput(raw))
    }

    @Test
    fun `json with message field is unwrapped`() {
        assertEquals(
            "Hydrate well tonight.",
            CoachChatPromptSystem.sanitizeOutput("""{"message": "Hydrate well tonight."}""")
        )
    }

    @Test
    fun `json with text field is unwrapped`() {
        assertEquals(
            "Keep the evening easy to protect your recovery.",
            CoachChatPromptSystem.sanitizeOutput("""{"text": "Keep the evening easy to protect your recovery."}""")
        )
    }
}
