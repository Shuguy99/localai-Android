package dev.localai.agent.ui.chat

/**
 * One renderable piece of an assistant message.
 *
 * Models stream a mix of plain prose, reasoning blocks (`<think>...</think>`),
 * fenced code and the agent's own ```tool fences. Rendering the raw text shows
 * all of that as literal characters; parsing it into segments lets the UI show
 * thinking as a collapsible section, tool calls as compact cards and code as
 * monospace blocks.
 */
sealed interface AssistantSegment {

    /** Regular prose. */
    data class Text(val text: String) : AssistantSegment

    /**
     * A reasoning block. [open] is true while the closing tag has not arrived
     * yet (live streaming); finished messages collapse it by default.
     */
    data class Thinking(val text: String, val open: Boolean) : AssistantSegment

    /** A ```lang fenced code block. */
    data class Code(val language: String, val text: String, val open: Boolean) : AssistantSegment

    /**
     * A ```tool fenced agent tool call. [name] is best-effort extracted from
     * the JSON payload for the collapsed card's label; may be null while the
     * call is still streaming in or when the payload is malformed.
     */
    data class ToolCall(val json: String, val name: String?, val open: Boolean) : AssistantSegment
}

/**
 * Splits an assistant message into [AssistantSegment]s.
 *
 * Tolerant by design: every tag/fence may be missing, unterminated (mid-stream)
 * or empty. Anything unrecognised stays as plain text, so a parser bug can never
 * hide message content — worst case it renders exactly like before.
 */
object AssistantText {

    fun parse(raw: String): List<AssistantSegment> {
        // Normalise line endings once; fences and tags are compared per line.
        val text = raw.replace("\r\n", "\n")
        val segments = mutableListOf<AssistantSegment>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            val t = plain.toString().trim()
            if (t.isNotEmpty()) segments += AssistantSegment.Text(t)
            plain.clear()
        }

        while (i < text.length) {
            val think = text.indexOf(THINK_OPEN, i, ignoreCase = true)
            val fence = text.indexOf(FENCE, i)
            val next = when {
                think == -1 && fence == -1 -> -1
                think == -1 -> fence
                fence == -1 -> think
                else -> minOf(think, fence)
            }

            if (next == -1) {
                plain.append(text, i, text.length)
                break
            }
            if (next > i) plain.append(text, i, next)
            i = next

            if (next == think) {
                i = parseThink(text, i, segments, ::flushPlain)
            } else {
                i = parseFence(text, i, segments, ::flushPlain)
            }
        }

        flushPlain()
        return segments
    }

    /** Returns the scan position after the (possibly unterminated) think block. */
    private fun parseThink(
        text: String,
        start: Int,
        segments: MutableList<AssistantSegment>,
        flushPlain: () -> Unit,
    ): Int {
        val bodyStart = start + THINK_OPEN.length
        val close = text.indexOf(THINK_CLOSE, bodyStart, ignoreCase = true)
        flushPlain()
        if (close == -1) {
            val body = text.substring(bodyStart).trim()
            // The tag just arrived with no content yet — emit it anyway so the
            // UI can show "thinking…" immediately during streaming.
            segments += AssistantSegment.Thinking(body, open = true)
            return text.length
        }
        val body = text.substring(bodyStart, close).trim()
        segments += AssistantSegment.Thinking(body, open = false)
        return close + THINK_CLOSE.length
    }

    /** Returns the scan position after the (possibly unterminated) fenced block. */
    private fun parseFence(
        text: String,
        start: Int,
        segments: MutableList<AssistantSegment>,
        flushPlain: () -> Unit,
    ): Int {
        val newline = text.indexOf('\n', start)
        // "```tool" with the line not finished yet: show a live empty block.
        if (newline == -1) {
            val language = text.substring(start + FENCE.length).trim()
            flushPlain()
            segments += if (language.equals(TOOL_LANG, ignoreCase = true)) {
                AssistantSegment.ToolCall("", null, open = true)
            } else {
                AssistantSegment.Code(language, "", open = true)
            }
            return text.length
        }

        val language = text.substring(start + FENCE.length, newline).trim()
        val isTool = language.equals(TOOL_LANG, ignoreCase = true)
        val bodyStart = newline + 1
        val close = text.indexOf(FENCE, bodyStart)

        if (close == -1) {
            val body = text.substring(bodyStart).trimEnd('\n')
            flushPlain()
            segments += if (isTool) {
                AssistantSegment.ToolCall(body.trim(), toolName(body), open = true)
            } else {
                AssistantSegment.Code(language, body, open = true)
            }
            return text.length
        }

        val body = text.substring(bodyStart, close).trimEnd('\n')
        flushPlain()
        segments += if (isTool) {
            AssistantSegment.ToolCall(body.trim(), toolName(body), open = false)
        } else {
            AssistantSegment.Code(language, body, open = false)
        }

        // Skip past the closing fence and the rest of its line (usually empty).
        var after = close + FENCE.length
        val lineEnd = text.indexOf('\n', after)
        after = if (lineEnd == -1) text.length else lineEnd + 1
        return after
    }

    /** Best-effort `"name"` / `"tool"` extraction from a tool-call JSON payload. */
    private fun toolName(json: String): String? =
        Regex("\"(?:name|tool)\"\\s*:\\s*\"([^\"]{1,64})\"")
            .find(json)?.groupValues?.get(1)

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
    private const val FENCE = "```"
    private const val TOOL_LANG = "tool"
}
