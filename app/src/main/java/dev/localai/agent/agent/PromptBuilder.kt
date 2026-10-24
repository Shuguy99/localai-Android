package dev.localai.agent.agent

import dev.localai.agent.data.settings.PromptFormat

enum class ChatRole { USER, ASSISTANT }

data class ChatTurn(
    val role: ChatRole,
    val content: String,
)

data class BuiltPrompt(
    val text: String,
    val stopSequences: List<String>,
    val format: PromptFormat,
)

/**
 * Chat templates are applied here, in Kotlin, instead of relying on
 * llama_chat_apply_template. That keeps the JNI surface small and lets the user
 * override the format per model when a model ships a nonstandard template.
 *
 * add_special is false on the native side, so BOS markers are part of the text
 * below where the format requires one.
 */
object PromptBuilder {

    fun detectFormat(modelPath: String, configured: PromptFormat): PromptFormat {
        if (configured != PromptFormat.AUTO) return configured
        val name = modelPath.lowercase()
        return when {
            name.contains("llama-3") || name.contains("llama3") || name.contains("llama_3") ->
                PromptFormat.LLAMA3
            name.contains("gemma") -> PromptFormat.GEMMA
            name.contains("mistral") || name.contains("mixtral") -> PromptFormat.MISTRAL
            name.contains("phi-3") || name.contains("phi3") -> PromptFormat.PHI3
            else -> PromptFormat.CHATML
        }
    }

    fun build(
        systemPrompt: String,
        turns: List<ChatTurn>,
        format: PromptFormat,
    ): BuiltPrompt = when (format) {
        PromptFormat.LLAMA3 -> llama3(systemPrompt, turns)
        PromptFormat.GEMMA -> gemma(systemPrompt, turns)
        PromptFormat.MISTRAL -> mistral(systemPrompt, turns)
        PromptFormat.PHI3 -> phi3(systemPrompt, turns)
        PromptFormat.CHATML, PromptFormat.AUTO -> chatml(systemPrompt, turns)
    }

    fun observation(name: String, ok: Boolean, output: String): String = buildString {
        append("TOOL RESULT\n")
        append("tool: ").append(name).append('\n')
        append("ok: ").append(ok).append('\n')
        append("output:\n")
        append(output.ifBlank { "(no output)" })
    }

    private fun chatml(system: String, turns: List<ChatTurn>): BuiltPrompt {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (t in turns) {
            val role = if (t.role == ChatRole.USER) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(t.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return BuiltPrompt(sb.toString(), listOf("<|im_end|>", "<|im_start|>"), PromptFormat.CHATML)
    }

    private fun llama3(system: String, turns: List<ChatTurn>): BuiltPrompt {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            .append(system).append("<|eot_id|>")
        for (t in turns) {
            sb.append("<|start_header_id|>")
                .append(if (t.role == ChatRole.USER) "user" else "assistant")
                .append("<|end_header_id|>\n\n")
                .append(t.content).append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return BuiltPrompt(sb.toString(), listOf("<|eot_id|>", "<|end_of_text|>"), PromptFormat.LLAMA3)
    }

    private fun gemma(system: String, turns: List<ChatTurn>): BuiltPrompt {
        val sb = StringBuilder("<bos>")
        if (turns.isEmpty()) {
            sb.append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
        } else {
            for ((index, t) in turns.withIndex()) {
                val content = if (index == 0 && t.role == ChatRole.USER) {
                    "$system\n\n${t.content}"
                } else {
                    t.content
                }
                sb.append("<start_of_turn>")
                    .append(if (t.role == ChatRole.USER) "user" else "model")
                    .append('\n').append(content).append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return BuiltPrompt(sb.toString(), listOf("<end_of_turn>", "<eos>"), PromptFormat.GEMMA)
    }

    private fun mistral(system: String, turns: List<ChatTurn>): BuiltPrompt {
        val sb = StringBuilder("<s>")
        var pendingUser = StringBuilder()
        var first = true
        for (t in turns) {
            when (t.role) {
                ChatRole.USER -> {
                    if (pendingUser.isNotEmpty()) pendingUser.append("\n\n")
                    if (first) {
                        pendingUser.append(system).append("\n\n")
                        first = false
                    }
                    pendingUser.append(t.content)
                }
                ChatRole.ASSISTANT -> {
                    sb.append("[INST] ").append(pendingUser).append(" [/INST] ")
                        .append(t.content).append("</s>")
                    pendingUser = StringBuilder()
                }
            }
        }
        if (pendingUser.isNotEmpty()) {
            sb.append("[INST] ").append(pendingUser).append(" [/INST]")
        } else {
            sb.append("[INST] ")
        }
        return BuiltPrompt(sb.toString(), listOf("</s>", "[INST]"), PromptFormat.MISTRAL)
    }

    private fun phi3(system: String, turns: List<ChatTurn>): BuiltPrompt {
        val sb = StringBuilder()
        sb.append("<|system|>\n").append(system).append("<|end|>\n")
        for (t in turns) {
            sb.append(if (t.role == ChatRole.USER) "<|user|>\n" else "<|assistant|>\n")
                .append(t.content).append("<|end|>\n")
        }
        sb.append("<|assistant|>\n")
        return BuiltPrompt(sb.toString(), listOf("<|end|>", "<|endoftext|>"), PromptFormat.PHI3)
    }
}
