package dev.localai.agent.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatFailureGuardTest {

    private val sig = RepeatFailureGuard.signature("exec_shell", """{"command":"apk add py3-pytz"}""")

    @Test
    fun `first failure produces no nudge`() {
        val guard = RepeatFailureGuard()
        assertNull(guard.record(sig, ok = false))
    }

    @Test
    fun `second identical failure produces nudge`() {
        val guard = RepeatFailureGuard()
        guard.record(sig, ok = false)
        val nudge = guard.record(sig, ok = false)
        assertTrue(nudge != null && "failed 2 times" in nudge)
        assertTrue("Do NOT repeat" in nudge!!)
    }

    @Test
    fun `different failing calls do not stack into a nudge`() {
        val guard = RepeatFailureGuard()
        guard.record(sig, ok = false)
        val other = RepeatFailureGuard.signature("write_file", """{"path":"a.txt"}""")
        assertNull(guard.record(other, ok = false))
    }

    @Test
    fun `success resets the streak`() {
        val guard = RepeatFailureGuard()
        guard.record(sig, ok = false)
        guard.record(sig, ok = false) // nudge here
        assertNull(guard.record(sig, ok = true))
        assertNull(guard.record(sig, ok = false)) // fresh streak: 1st failure again
    }

    @Test
    fun `reset clears state between turns`() {
        val guard = RepeatFailureGuard()
        guard.record(sig, ok = false)
        guard.reset()
        assertNull(guard.record(sig, ok = false))
    }

    @Test
    fun `signature separates tool names`() {
        val a = RepeatFailureGuard.signature("exec_shell", "x")
        val b = RepeatFailureGuard.signature("write_file", "x")
        assertTrue(a != b)
    }
}
