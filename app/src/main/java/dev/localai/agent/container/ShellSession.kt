package dev.localai.agent.container

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Line protocol spoken by the persistent shell session.
 *
 * Every command travels as one base64-encoded line, so multi-line shell
 * scripts survive the round trip and guest output can never be mistaken for
 * protocol traffic. The shell answers each command with a sentinel line that
 * carries a per-command random marker plus the exit code:
 *
 *     host -> __LC_EXEC:<marker>:<base64(command)>
 *     guest output...
 *     host <- <marker>:<exit>        (always on its own line, see SESSION_LOOP)
 *
 * The random marker makes sentinel collisions practically impossible even if
 * the guest prints a line that looks like a sentinel for a different command.
 */
internal object ShellProtocol {

    const val EXEC_PREFIX = "__LC_EXEC:"
    const val QUIT_LINE = "__LC_QUIT"
    const val HALTED_LINE = "__LC_HALTED"
    const val NO_BASE64_LINE = "__LC_NOBASE64"

    /**
     * The long-lived shell program. It reads protocol lines from stdin until
     * QUIT or EOF. Commands run with stdin redirected from /dev/null so a
     * guest command cannot swallow the protocol stream. The per-command
     * sentinel is printed with a leading newline, which keeps it on its own
     * line even when the command's last output line lacks a trailing one.
     */
    val SESSION_LOOP = """
        if ! command -v base64 >/dev/null 2>&1; then printf '__LC_NOBASE64\n'; exit 66; fi
        while IFS= read -r __line || [ -n "${'$'}__line" ]; do
          case "${'$'}__line" in
            __LC_QUIT) printf '__LC_HALTED\n'; break ;;
            __LC_EXEC:*) ;;
            *) continue ;;
          esac
          __rest=${'$'}{__line#__LC_EXEC:}
          __m=${'$'}{__rest%%:*}
          __b64=${'$'}{__rest#*:}
          eval "${'$'}(printf %s "${'$'}__b64" | base64 -d)" < /dev/null
          printf '\n%s:%s\n' "${'$'}__m" "${'$'}?"
        done
    """.trimIndent()

    /** Wraps the command in a subshell so a per-command ulimit never lowers the session's own limits. */
    fun withMemoryLimit(command: String, memoryLimitMb: Int): String =
        if (memoryLimitMb > 0) {
            "( ulimit -v ${memoryLimitMb.toLong() * 1024} 2>/dev/null; $command )"
        } else {
            command
        }

    fun encode(command: String, marker: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return "$EXEC_PREFIX$marker:$b64"
    }

    /** Exit code when [line] is this command's sentinel, null for any other output line. */
    fun sentinelExitCode(line: String, marker: String): Int? {
        if (line.length <= marker.length + 1 || !line.startsWith(marker)) return null
        if (line[marker.length] != ':') return null
        return line.substring(marker.length + 1).toIntOrNull()
    }
}

/**
 * A long-lived PRoot shell. One PRoot process is kept alive across
 * [ContainerManager.exec] calls, which removes the 150-300 ms ptrace startup
 * from every agent command and keeps shell state (cwd, exported variables,
 * umask) between commands, exactly like a human sitting in a terminal.
 *
 * The class is deliberately decoupled from [Process]: it talks to any
 * byte streams plus a kill callback, which keeps it unit-testable with pipes.
 *
 * Lifecycle: created by ContainerManager on demand, destroyed on timeout or
 * process death, on container reset, on Stop, and after a long idle period.
 * A killed session is always recreated on the next exec() — only the shell
 * state (cwd/env) is lost, never the rootfs or the workspace.
 */
class ShellSession(
    private val output: InputStream,
    private val input: OutputStream,
    private val killFn: () -> Unit,
    private val aliveFn: () -> Boolean,
    private val pingTimeoutSeconds: Int = 10,
) {
    private val writer = BufferedWriter(OutputStreamWriter(input, Charsets.UTF_8))
    private val lines = LinkedBlockingQueue<String>()
    private val commandLock = ReentrantLock()
    private val counter = AtomicLong()

    @Volatile private var dead = false
    @Volatile private var killed = false

    /** Set when the guest shell reports that busybox base64 is unavailable. */
    @Volatile var sawUnsupported = false
        private set

    private val reader = Thread {
        try {
            BufferedReader(InputStreamReader(output, Charsets.UTF_8), 16 * 1024).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    lines.put(line)
                }
            }
        } catch (_: IOException) {
            // Killed or closed; the EOF marker below ends every waiter.
        }
        dead = true
        lines.put(EOF)
    }.apply {
        name = "proot-session-reader"
        isDaemon = true
        start()
    }

    val isAlive: Boolean
        get() = !dead && !killed && runCatching { aliveFn() }.getOrDefault(false)

    /**
     * Handshake executed right after the process starts: proves the pipes work
     * and busybox base64 exists before the session is trusted with commands.
     */
    fun ping(): Boolean = try {
        val r = run("true", pingTimeoutSeconds, 8 * 1024)
        !r.timedOut && r.exitCode == 0
    } catch (_: IllegalStateException) {
        false
    }

    /**
     * Runs one command inside the session shell. Returns the collected output
     * and the command's exit code. On timeout the whole session is killed (a
     * guest command cannot be killed individually from outside ptrace), and
     * the next exec() transparently starts a fresh session. If the shell dies
     * mid-command the result carries exitCode -2 and whatever was printed.
     */
    fun run(command: String, timeoutSeconds: Int, maxOutputBytes: Int): ExecResult {
        commandLock.withLock {
            if (dead || killed) throw IllegalStateException("session is closed")
            val start = System.currentTimeMillis()
            val marker = nextMarker()
            val deadline = start + timeoutSeconds * 1000L

            try {
                writeLine(ShellProtocol.encode(command, marker))
            } catch (_: IOException) {
                dead = true
                return ExecResult(
                    -2, "", false, timedOut = false,
                    System.currentTimeMillis() - start,
                )
            }

            val out = StringBuilder()
            var truncated = false
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    kill()
                    return ExecResult(
                        -1, out.toString(), truncated, timedOut = true,
                        System.currentTimeMillis() - start,
                    )
                }
                val line = lines.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
                when {
                    line == EOF -> return ExecResult(
                        -2, out.toString(), truncated, timedOut = false,
                        System.currentTimeMillis() - start,
                    )
                    line == ShellProtocol.NO_BASE64_LINE -> {
                        sawUnsupported = true
                        dead = true
                        return ExecResult(
                            -2, out.toString(), truncated, timedOut = false,
                            System.currentTimeMillis() - start,
                        )
                    }
                    else -> {
                        val exit = ShellProtocol.sentinelExitCode(line, marker)
                        if (exit != null) {
                            // The sentinel printf starts with '\n', so command
                            // output that itself ended in a newline leaves one
                            // protocol-induced empty line; drop that artifact
                            // so session output matches the one-shot path.
                            val text = out.toString().let {
                                if (it.endsWith("\n")) it.removeSuffix("\n") else it
                            }
                            return ExecResult(
                                exit, text, truncated, timedOut = false,
                                System.currentTimeMillis() - start,
                            )
                        }
                        if (!truncated) {
                            if (out.isNotEmpty()) out.append('\n')
                            if (out.length + line.length > maxOutputBytes) {
                                out.append(line, 0, (maxOutputBytes - out.length).coerceAtLeast(0))
                                truncated = true
                            } else {
                                out.append(line)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Asks the shell to exit, closes the pipe, and force-kills after a grace period. */
    fun shutdown() {
        if (dead || killed) {
            killFn()
            return
        }
        dead = true
        try {
            writeLine(ShellProtocol.QUIT_LINE)
            writer.flush()
        } catch (_: IOException) {
            // Already gone.
        }
        runCatching { input.close() }
        killFn()
    }

    /** Hard kill without the polite quit handshake (Stop button, timeout). */
    fun kill() {
        killed = true
        dead = true
        runCatching { killFn() }
    }

    private fun writeLine(s: String) {
        writer.write(s)
        writer.write('\n'.code)
        writer.flush()
    }

    private fun nextMarker(): String =
        "LC" + java.lang.Long.toString(counter.incrementAndGet(), 36) +
            "-" + java.lang.Long.toHexString(System.nanoTime())

    private companion object {
        /** Stream terminator marker; a guest would have to print it verbatim to confuse the reader. */
        const val EOF = "__LC_STREAM_EOF__"
    }
}
