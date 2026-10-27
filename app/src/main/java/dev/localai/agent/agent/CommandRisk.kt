package dev.localai.agent.agent

/**
 * Risk classification for guest shell commands, one step below the hard
 * [CommandPolicy] deny-list.
 *
 * The container already confines damage (PRoot jail, snapshot before each turn,
 * output caps), so this layer exists for transparency, not for security: the
 * user should see and approve commands that *change* something, while purely
 * observational commands run without friction.
 *
 * The classifier is deliberately conservative — it must be able to prove a
 * command is read-only, otherwise it asks. Shell syntax is parsed roughly but
 * quote-aware: operators inside quotes do not split segments, and any unquoted
 * redirection, command substitution or pipeline stage that is not on the
 * read-only whitelist escalates to [Risk.CONFIRM]. Note that `$()`, backticks
 * and redirections inside *double* quotes still expand/execute in POSIX sh,
 * so only single quotes count as a literal context for those checks.
 */
object CommandRisk {

    enum class Risk { SAFE, CONFIRM }

    /** First words that never modify the filesystem or the network. */
    private val safeCommands = setOf(
        "ls", "cat", "head", "tail", "grep", "egrep", "fgrep",
        "find", "pwd", "echo", "printf", "wc", "du", "df", "file", "stat",
        "which", "type", "whereis", "uname", "whoami", "id", "hostname",
        "date", "ps", "env", "printenv", "sort", "uniq", "cut", "tr",
        "rev", "tac", "nl", "od", "hexdump", "xxd", "base64", "md5sum",
        "sha256sum", "sha1sum", "sha512sum", "cksum", "readlink", "realpath",
        "basename", "dirname", "seq", "true", "false", "test", "diff", "cmp",
        "nproc", "arch", "lscpu", "free", "uptime", "sleep", "cd", "export",
        "unset", "set", "alias", "unalias", "history", "clear", "reset",
        "less", "more", "column", "paste", "join", "comm", "expand", "fold",
        "fmt", "numfmt", "factor", "tty", "groups", "users", "logname",
    )

    /** Words that always need approval, even as a pipeline stage. */
    private val confirmCommands = setOf(
        "rm", "rmdir", "mv", "cp", "ln", "mkdir", "touch", "chmod", "chown",
        "chgrp", "truncate", "shred", "apk", "wget", "curl", "scp", "rsync",
        "tar", "gzip", "gunzip", "zip", "unzip", "xz", "zstd", "sh", "ash",
        "bash", "dash", "zsh", "su", "sudo", "doas", "xargs", "awk", "perl",
        "python", "python3", "node", "nc", "ncat", "socat", "kill", "killall",
        "pkill", "nohup", "setsid", "nice", "timeout", "stdbuf", "watch",
        "crond", "crontab", "tee", "dd", "mount", "umount", "sed", "patch",
        "abduco", "dtach", "tmux", "screen", "git", "install", "make",
        "cmake", "gcc", "clang", "ld", "ar", "strip", "nslookup", "dig",
        "host", "ping", "traceroute", "ip", "ifconfig", "route", "sysctl",
    )

    /**
     * Prefix executors that run their remaining words as a command. Transparent
     * enough to shift off and classify the wrapped command directly.
     */
    private val prefixExecutors = setOf("env", "command", "exec", "busybox")

    private val findDangerousFlags = Regex(
        """(^|\s)-(delete|exec|execdir|ok|okdir|fprintf|fprint)\b""",
    )

    private val assignment = Regex("""^[A-Za-z_][A-Za-z0-9_]*=[^=]*$""")

    /**
     * Classifies a full command line. Every unquoted `&&`/`||`/`;`/`|`/newline
     * segment must independently qualify as read-only, otherwise the whole
     * command is [Risk.CONFIRM].
     */
    fun classify(command: String): Risk {
        if (command.isBlank()) return Risk.SAFE
        return if (splitSegments(command).all { isSegmentSafe(it) }) Risk.SAFE else Risk.CONFIRM
    }

    /**
     * Quote-aware split on shell operators. Quotes survive into the segment
     * content on purpose: `echo "a;b"` must stay one segment, so the caller
     * never sees the quoted `;`.
     */
    internal fun splitSegments(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\\' && quote != '\'') {
                // Backslash escape: keep both characters; the next char cannot
                // open/close a quote or be an operator.
                if (i + 1 < command.length) {
                    current.append(c).append(command[i + 1])
                    i += 2
                    continue
                }
                current.append(c)
                i++
                continue
            }
            val q = quote
            if (q == null && (c == '\'' || c == '"')) {
                quote = c
                current.append(c)
                i++
                continue
            }
            if (q != null && c == q) {
                quote = null
                current.append(c)
                i++
                continue
            }
            if (q == null) {
                if (c == '\n' || c == ';' || c == '|') {
                    // Collapse two-char operators (|| ;;) into one split.
                    if (c == '|' && i + 1 < command.length && command[i + 1] == '|') i++
                    if (c == ';' && i + 1 < command.length && command[i + 1] == ';') i++
                    segments += current.toString()
                    current.clear()
                    i++
                    continue
                }
                if (c == '&' && i + 1 < command.length && command[i + 1] == '&') {
                    segments += current.toString()
                    current.clear()
                    i += 2
                    continue
                }
            }
            current.append(c)
            i++
        }
        segments += current.toString()
        return segments
    }

    private fun isSegmentSafe(raw: String): Boolean {
        val segment = raw.trim()
        if (segment.isEmpty()) return true

        // Unquoted redirection means writing a file (or fd games) — ask.
        if (hasRedirect(segment)) return false
        // Command substitution can run anything — ask.
        if (hasSubstitution(segment)) return false

        val words = tokenize(segment)
        // Drop leading VAR=value environment assignments and prefix executors
        // (busybox <applet>, env <cmd>, ...) so the real command is classified.
        while (words.isNotEmpty()) {
            val w = words.first()
            if (w.matches(assignment) || w in prefixExecutors) {
                words.removeAt(0)
            } else {
                break
            }
        }
        val cmd = words.firstOrNull() ?: return true
        if (cmd in confirmCommands) return false
        // Read-only commands with dangerous variants of their own.
        if (cmd == "find" && findDangerousFlags.containsMatchIn(segment)) return false
        return cmd in safeCommands
    }

    /** True when `<` or `>` appears outside quotes (fd dups like `2>&1` allowed). */
    private fun hasRedirect(segment: String): Boolean {
        val stripped = removeQuoted(segment).replace(Regex("""\d?>&\d"""), "")
        return '<' in stripped || '>' in stripped
    }

    /**
     * True when `$( ` or a backtick survives outside single quotes. Double
     * quotes do NOT neutralize these in POSIX sh, so they are inspected too.
     */
    private fun hasSubstitution(segment: String): Boolean {
        val stripped = removeSingleQuoted(segment)
        return stripped.contains("\$(") || '`' in stripped
    }

    /** Removes single- and double-quoted spans so operators inside them are ignored. */
    private fun removeQuoted(segment: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\\' && i + 1 < segment.length && quote != '\'') {
                i += 2
                continue
            }
            val q = quote
            if (q == null && (c == '\'' || c == '"')) {
                quote = c
            } else if (q != null && c == q) {
                quote = null
            } else if (q == null) {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Removes only single-quoted spans (true literal context in sh). */
    private fun removeSingleQuoted(segment: String): String {
        val out = StringBuilder()
        var inSingle = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\'' ) {
                inSingle = !inSingle
                i++
                continue
            }
            if (!inSingle) out.append(c)
            i++
        }
        return out.toString()
    }

    /** Whitespace tokenization over the quote-stripped segment. */
    private fun tokenize(segment: String): MutableList<String> =
        removeQuoted(segment).trim().split(Regex("""\s+""")).toMutableList()
}
