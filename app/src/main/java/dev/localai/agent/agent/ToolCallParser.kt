package dev.localai.agent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class ToolCall(
    val name: String,
    val arguments: JsonObject,
    val raw: String,
    val startIndex: Int,
)

/**
 * Parses the tool-call protocol. Small local models cannot be relied on for native
 * function calling, so the app defines a strict, easy-to-copy fenced JSON block and
 * also accepts two common variants (<tool_call> tags, bare JSON at the end).
 */
object ToolCallParser {

    val allowedTools = setOf("read_file", "write_file", "list_dir", "exec_shell")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fenceRegex = Regex(
        "```(?:tool|tool_call|json)?[ \\t]*\\r?\\n([\\s\\S]*?)```",
        RegexOption.IGNORE_CASE,
    )
    private val tagRegex = Regex(
        "<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>",
        RegexOption.IGNORE_CASE,
    )
    private val nameKeys = listOf("name", "tool", "tool_name", "function")
    private val argKeys = listOf("arguments", "args", "parameters", "input")

    fun parse(text: String): ToolCall? {
        val candidates = buildList {
            fenceRegex.findAll(text).forEach { add(it.groupValues[1] to it.range.first) }
            tagRegex.findAll(text).forEach { add(it.groupValues[1] to it.range.first) }
        }
        for ((raw, start) in candidates.asReversed()) {
            parseJson(raw, start)?.let { return it }
        }
        // Last resort: a bare JSON object that names a tool.
        val bare = lastBalancedObject(text)
        if (bare != null) {
            val start = text.indexOf(bare).coerceAtLeast(0)
            parseJson(bare, start)?.let { return it }
        }
        return null
    }

    private fun parseJson(raw: String, start: Int): ToolCall? {
        val trimmed = raw.trim().removePrefix("json").trim()
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null

        val name = nameKeys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.content
        }?.trim()?.lowercase()?.removeSurrounding("\"") ?: return null
        if (name !in allowedTools) return null

        val argsElement = argKeys.firstNotNullOfOrNull { obj[it] }
        val args: JsonObject = when {
            argsElement is JsonObject -> argsElement
            argsElement is JsonPrimitive && argsElement.isString ->
                runCatching { json.parseToJsonElement(argsElement.content).jsonObject }.getOrNull()
                    ?: JsonObject(emptyMap())
            else -> JsonObject(obj.filterKeys { it !in nameKeys && it !in argKeys })
        }
        return ToolCall(name, args, trimmed, start)
    }

    /** Finds the last complete top-level `{...}` block, ignoring braces inside strings. */
    private fun lastBalancedObject(text: String): String? {
        var best: String? = null
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            while (j < text.length) {
                val c = text[j]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val candidate = text.substring(i, j + 1)
                                if (candidate.contains("\"name\"") || candidate.contains("\"tool\"")) {
                                    best = candidate
                                }
                                break
                            }
                        }
                    }
                }
                j++
            }
            i++
        }
        return best
    }
}
