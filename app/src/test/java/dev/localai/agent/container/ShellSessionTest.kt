package dev.localai.agent.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs [ShellSession] against an in-JVM fake of the guest shell: the same
 * pipes, the same line protocol, no PRoot required.
 */
class ShellSessionTest {

    /**
     * Emulates the session loop from ShellProtocol.SESSION_LOOP. Responses are
     * decided by [behavior]; returning null makes the fake stay silent, which
     * drives the timeout scenario. [dieHook] runs as a side effect when a
     * command arrives (used to emulate mid-command death).
     */
    private class FakeShell(
        private val noBase64: Boolean = false,
        var dieHook: ((String) -> Unit)? = null,
        // Trailing-lambda target: kept last so `FakeShell { cmd -> ... }` binds here.
        private val behavior: (decodedCommand: String) -> Pair<List<String>, Int>? = { null },
    ) : Closeable {
        val killCount = AtomicInteger(0)

        private val fakeIn = PipedInputStream(64 * 1024)
        private val fakeOut = PipedOutputStream()
        private val hostToFake = PipedOutputStream(fakeIn)

        /** Stream the session reads guest output from. */
        val hostInputStream: PipedInputStream = PipedInputStream(fakeOut, 64 * 1024)

        init {
            Thread {
                try {
                    BufferedReader(InputStreamReader(fakeIn, Charsets.UTF_8)).use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            when {
                                line == "__LC_QUIT" -> {
                                    fakeOut.write("__LC_HALTED\n".toByteArray())
                                    fakeOut.flush()
                                    break
                                }
                                line.startsWith("__LC_EXEC:") -> {
                                    val rest = line.removePrefix("__LC_EXEC:")
                                    val marker = rest.substringBefore(':')
                                    val b64 = rest.substringAfter(':')
                                    val decoded = String(
                                        java.util.Base64.getDecoder().decode(b64),
                                        Charsets.UTF_8,
                                    )
                                    if (noBase64) {
                                        fakeOut.write("__LC_NOBASE64\n".toByteArray())
                                        fakeOut.flush()
                                        break
                                    }
                                    dieHook?.invoke(decoded)
                                    val response = behavior(decoded) ?: continue
                                    response.first.forEach {
                                        fakeOut.write((it + "\n").toByteArray(Charsets.UTF_8))
                                    }
                                    fakeOut.write(("\n$marker:${response.second}\n").toByteArray())
                                    fakeOut.flush()
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Pipe closed; the test is done with this fake.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        /** Stream the session writes protocol lines to. */
        fun hostOutputStream(): PipedOutputStream = hostToFake

        /** Closes only the guest-output side: the session then observes EOF. */
        fun dieAfterThisCommand() {
            runCatching { fakeOut.close() }
        }

        override fun close() {
            runCatching { fakeOut.close() }
            runCatching { hostToFake.close() }
        }
    }

    private fun newSession(fake: FakeShell, pingTimeout: Int = 5): ShellSession =
        ShellSession(
            output = fake.hostInputStream,
            input = fake.hostOutputStream(),
            killFn = { fake.killCount.incrementAndGet() },
            aliveFn = { fake.killCount.get() == 0 },
            pingTimeoutSeconds = pingTimeout,
        )

    @Test
    fun `handshake succeeds and commands round-trip`() {
        val fake = FakeShell { cmd ->
            when (cmd) {
                "true" -> Pair(emptyList(), 0)
                "echo hi" -> Pair(listOf("hi"), 0)
                else -> null
            }
        }
        val session = newSession(fake)
        assertTrue(session.ping())

        val result = session.run("echo hi", timeoutSeconds = 5, maxOutputBytes = 8192)
        assertEquals(0, result.exitCode)
        assertEquals("hi", result.output)
        assertFalse(result.timedOut)
        assertTrue(result.ok)
        session.shutdown()
    }

    @Test
    fun `multi-line output is preserved in order`() {
        val fake = FakeShell { cmd ->
            when (cmd) {
                "true" -> Pair(emptyList(), 0)
                "seq 3" -> Pair(listOf("one", "two", "three"), 0)
                else -> null
            }
        }
        val session = newSession(fake)
        assertTrue(session.ping())
        val result = session.run("seq 3", timeoutSeconds = 5, maxOutputBytes = 8192)
        assertEquals("one\ntwo\nthree", result.output)
        session.shutdown()
    }

    @Test
    fun `non-zero exit code propagates and ok is false`() {
        val fake = FakeShell { cmd ->
            when (cmd) {
                "true" -> Pair(emptyList(), 0)
                "false" -> Pair(listOf("boom"), 3)
                else -> null
            }
        }
        val session = newSession(fake)
        assertTrue(session.ping())
        val result = session.run("false", timeoutSeconds = 5, maxOutputBytes = 8192)
        assertEquals(3, result.exitCode)
        assertEquals("boom", result.output)
        assertFalse(result.ok)
        session.shutdown()
    }

    @Test
    fun `timeout kills the session and reports timedOut`() {
        val fake = FakeShell { cmd ->
            when (cmd) {
                "true" -> Pair(emptyList(), 0)
                else -> null // stay silent forever
            }
        }
        val session = newSession(fake)
        assertTrue(session.ping())

        val result = session.run("hang", timeoutSeconds = 1, maxOutputBytes = 8192)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertEquals(1, fake.killCount.get())
        assertFalse(session.isAlive)
    }

    @Test
    fun `shell death is reported as a died result`() {
        val fake = FakeShell(
            behavior = { cmd ->
                when (cmd) {
                    "true" -> Pair(emptyList(), 0)
                    else -> null // never answer the doomed command
                }
            },
        )
        // Closing the guest-output side when the doomed command arrives emulates
        // the guest being killed: the session must report a died result.
        fake.dieHook = { cmd -> if (cmd == "die") fake.dieAfterThisCommand() }
        val session = newSession(fake)
        assertTrue(session.ping())

        val result = session.run("die", timeoutSeconds = 5, maxOutputBytes = 8192)
        assertEquals(-2, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(session.isAlive)
    }

    @Test
    fun `missing busybox base64 is detected through the handshake`() {
        val fake = FakeShell(noBase64 = true)
        val session = newSession(fake)
        assertFalse(session.ping())
        assertTrue(session.sawUnsupported)
        session.shutdown()
    }

    @Test
    fun `output cap marks the result truncated`() {
        val longLine = "x".repeat(10_000)
        val fake = FakeShell { cmd ->
            when (cmd) {
                "true" -> Pair(emptyList(), 0)
                "big" -> Pair(listOf(longLine), 0)
                else -> null
            }
        }
        val session = newSession(fake)
        assertTrue(session.ping())
        val result = session.run("big", timeoutSeconds = 5, maxOutputBytes = 1024)
        assertTrue(result.truncated)
        assertTrue(result.output.length <= 1024)
        session.shutdown()
    }
}
