package dev.localai.agent.agent

import dev.localai.agent.data.settings.PromptFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val system = "You are a coding agent."
    private val turns = listOf(
        ChatTurn(ChatRole.USER, "list /workspace"),
        ChatTurn(ChatRole.ASSISTANT, "Sure."),
        ChatTurn(ChatRole.USER, "now write a file"),
    )

    @Test
    fun chatmlWrapsEveryTurnAndOpensAssistant() {
        val built = PromptBuilder.build(system, turns, PromptFormat.CHATML)

        assertTrue(built.text.startsWith("<|im_start|>system\n$system<|im_end|>\n"))
        assertTrue(built.text.contains("<|im_start|>user\nlist /workspace<|im_end|>"))
        assertTrue(built.text.contains("<|im_start|>assistant\nSure.<|im_end|>"))
        assertTrue(built.text.endsWith("<|im_start|>assistant\n"))
        assertEquals(listOf("<|im_end|>", "<|im_start|>"), built.stopSequences)
        assertEquals(PromptFormat.CHATML, built.format)
    }

    @Test
    fun llama3AddsBosHeaderAndEot() {
        val built = PromptBuilder.build(system, turns, PromptFormat.LLAMA3)

        assertTrue(built.text.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(built.text.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(built.text.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertEquals(listOf("<|eot_id|>", "<|end_of_text|>"), built.stopSequences)
    }

    @Test
    fun gemmaFoldsSystemPromptIntoFirstUserTurn() {
        val built = PromptBuilder.build(system, turns, PromptFormat.GEMMA)

        assertTrue(built.text.startsWith("<bos><start_of_turn>user\n$system\n\nlist /workspace"))
        assertTrue(built.text.contains("<start_of_turn>model\nSure.<end_of_turn>"))
        assertTrue(built.text.endsWith("<start_of_turn>model\n"))
        assertEquals(listOf("<end_of_turn>", "<eos>"), built.stopSequences)
    }

    @Test
    fun gemmaUsesSystemAsOnlyUserTurnWhenHistoryIsEmpty() {
        val built = PromptBuilder.build(system, emptyList(), PromptFormat.GEMMA)
        assertEquals("<bos><start_of_turn>user\n$system<end_of_turn>\n<start_of_turn>model\n", built.text)
    }

    @Test
    fun mistralPairsInstructionsWithResponses() {
        val built = PromptBuilder.build(system, turns, PromptFormat.MISTRAL)

        assertTrue(built.text.startsWith("<s>[INST] $system\n\nlist /workspace [/INST] Sure.</s>"))
        assertTrue(built.text.endsWith("[INST] now write a file [/INST]"))
        assertEquals(listOf("</s>", "[INST]"), built.stopSequences)
    }

    @Test
    fun phi3UsesChatMarkers() {
        val built = PromptBuilder.build(system, turns, PromptFormat.PHI3)

        assertTrue(built.text.startsWith("<|system|>\n$system<|end|>\n<|user|>\nlist /workspace<|end|>"))
        assertTrue(built.text.endsWith("<|assistant|>\n"))
        assertEquals(listOf("<|end|>", "<|endoftext|>"), built.stopSequences)
    }

    @Test
    fun autoFormatDetectsCommonFamilies() {
        assertEquals(
            PromptFormat.LLAMA3,
            PromptBuilder.detectFormat("/models/Meta-Llama-3.2-3B-Instruct-Q4_K_M.gguf", PromptFormat.AUTO),
        )
        assertEquals(
            PromptFormat.GEMMA,
            PromptBuilder.detectFormat("/models/gemma-2-2b-it-Q4_K_M.gguf", PromptFormat.AUTO),
        )
        assertEquals(
            PromptFormat.MISTRAL,
            PromptBuilder.detectFormat("/models/Mistral-7B-Instruct-v0.3.Q4_K_M.gguf", PromptFormat.AUTO),
        )
        assertEquals(
            PromptFormat.PHI3,
            PromptBuilder.detectFormat("/models/Phi-3-mini-4k-instruct-q4.gguf", PromptFormat.AUTO),
        )
        assertEquals(
            PromptFormat.CHATML,
            PromptBuilder.detectFormat("/models/qwen2.5-coder-3b-instruct-q4_k_m.gguf", PromptFormat.AUTO),
        )
    }

    @Test
    fun explicitFormatOverridesDetection() {
        assertEquals(
            PromptFormat.PHI3,
            PromptBuilder.detectFormat("/models/llama-3.2-3b.gguf", PromptFormat.PHI3),
        )
    }

    @Test
    fun autoBuildsAsChatml() {
        val built = PromptBuilder.build(system, turns, PromptFormat.AUTO)
        assertTrue(built.text.startsWith("<|im_start|>system\n"))
        assertEquals(PromptFormat.CHATML, built.format)
    }

    @Test
    fun observationIsStableAndMarksEmptyOutput() {
        assertEquals(
            "TOOL RESULT\ntool: exec_shell\nok: true\noutput:\n(no output)",
            PromptBuilder.observation("exec_shell", true, "  "),
        )
        assertEquals(
            "TOOL RESULT\ntool: read_file\nok: false\noutput:\ndenied",
            PromptBuilder.observation("read_file", false, "denied"),
        )
    }
}
