package com.assist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {
    private val gate = ActionGate()

    private fun tap(
        text: String?,
        tool: String = AgentTools.TAP,
        allowlist: Set<String> = emptySet(),
    ) = GateInput(toolName = tool, argsJson = "{}", targetText = text, allowlist = allowlist)

    @Test
    fun `tap on a Send control is gated as SEND`() {
        val d = gate.classify(tap("Send"))
        assertTrue(d.gated)
        assertEquals(GateCategory.SEND, d.category)
    }

    @Test
    fun `tap on Delete is gated as DELETE`() {
        assertEquals(GateCategory.DELETE, gate.classify(tap("Delete")).category)
    }

    @Test
    fun `tap on Uninstall is gated as DELETE`() {
        assertEquals(GateCategory.DELETE, gate.classify(tap("Uninstall")).category)
    }

    @Test
    fun `tap on Buy now is gated as PAY`() {
        assertEquals(GateCategory.PAY, gate.classify(tap("Buy now")).category)
    }

    @Test
    fun `tap on Install is gated as INSTALL`() {
        assertEquals(GateCategory.INSTALL, gate.classify(tap("Install")).category)
    }

    @Test
    fun `tap on Call is gated as CALL`() {
        assertEquals(GateCategory.CALL, gate.classify(tap("Call")).category)
    }

    @Test
    fun `tap on a benign control is not gated`() {
        val d = gate.classify(tap("Search"))
        assertFalse(d.gated)
        assertNull(d.category)
    }

    @Test
    fun `keyword must be a whole word`() {
        // "sender" contains "send" but is not the word "send".
        assertFalse(gate.classify(tap("Sender name")).gated)
        // "reinstalled" contains "install" but is not the word "install".
        assertFalse(gate.classify(tap("Reinstalled apps list")).gated)
    }

    @Test
    fun `set_text into a password field is gated as PASSWORD`() {
        val d =
            gate.classify(
                GateInput(
                    toolName = AgentTools.SET_TEXT,
                    argsJson = "{}",
                    targetText = "Password",
                    isPasswordField = true,
                ),
            )
        assertTrue(d.gated)
        assertEquals(GateCategory.PASSWORD, d.category)
    }

    @Test
    fun `set_text into a normal field is not gated`() {
        val d =
            gate.classify(
                GateInput(
                    toolName = AgentTools.SET_TEXT,
                    argsJson = "{}",
                    targetText = "Search",
                    isPasswordField = false,
                ),
            )
        assertFalse(d.gated)
    }

    @Test
    fun `open_app is never gated even if the label looks sensitive`() {
        assertFalse(gate.classify(tap("Send Money", tool = AgentTools.OPEN_APP)).gated)
    }

    @Test
    fun `an allowlisted category skips the gate`() {
        val d = gate.classify(tap("Send", allowlist = setOf(GateCategory.SEND.name)))
        assertFalse(d.gated)
    }

    @Test
    fun `null target text is not gated`() {
        assertFalse(gate.classify(tap(null)).gated)
    }

    @Test
    fun `affirmative parsing`() {
        assertTrue(ActionGate.isAffirmative("yes"))
        assertTrue(ActionGate.isAffirmative("  YES please "))
        assertTrue(ActionGate.isAffirmative("confirm"))
        assertTrue(ActionGate.isAffirmative("go ahead"))
        assertFalse(ActionGate.isAffirmative("no"))
        assertFalse(ActionGate.isAffirmative("nope"))
        assertFalse(ActionGate.isAffirmative("stop"))
        assertFalse(ActionGate.isAffirmative(""))
    }
}
