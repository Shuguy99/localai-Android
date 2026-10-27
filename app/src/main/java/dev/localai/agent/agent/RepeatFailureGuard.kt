package dev.localai.agent.agent

/**
 * Detects an agent stuck repeating the exact same failing tool call — the classic
 * "apk add py3-pytz" loop seen in the 0.2.2 report, where one wrong package name
 * aborted every `apk add` transaction until the step limit burned out.
 *
 * The guard counts *consecutive* failures of an identical call signature. Any
 * successful call (or a different failing call) resets the streak. Once the same
 * call has failed [NUDGE_AFTER] times in a row, [record] returns a short system
 * nudge that is appended to the observation so the model changes strategy instead
 * of repeating itself. Pure Kotlin, no Android deps — unit-testable directly.
 */
class RepeatFailureGuard {

    private var lastSignature: String? = null
    private var consecutiveFailures = 0

    /**
     * Records a tool call outcome.
     *
     * @param signature stable identity of the call (tool name + arguments);
     *   identical strings are treated as the same call
     * @param ok whether the call succeeded
     * @return a nudge message when the exact same call just failed at least
     *   [NUDGE_AFTER] times in a row, or null when the observation needs no note
     */
    fun record(signature: String, ok: Boolean): String? {
        if (ok) {
            lastSignature = null
            consecutiveFailures = 0
            return null
        }
        if (signature == lastSignature) {
            consecutiveFailures++
        } else {
            lastSignature = signature
            consecutiveFailures = 1
        }
        return if (consecutiveFailures >= NUDGE_AFTER) nudge() else null
    }

    /** Clears the streak; call when a new turn starts so history cannot leak in. */
    fun reset() {
        lastSignature = null
        consecutiveFailures = 0
    }

    private fun nudge(): String = "\n\n[system] This exact tool call has now failed " +
        "$consecutiveFailures times in a row with the same result. Do NOT repeat it " +
        "unchanged. Change the approach: fix the arguments (e.g. verify exact package " +
        "names with `apk search <term>`), try a different tool, or report the blocker " +
        "to the user and stop."

    companion object {
        /** Number of consecutive identical failures before the first nudge. */
        const val NUDGE_AFTER = 2

        fun signature(name: String, arguments: String): String = "$name|$arguments"
    }
}
