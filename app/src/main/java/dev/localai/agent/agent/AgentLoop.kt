package dev.localai.agent.agent

import dev.localai.agent.container.ContainerManager
import dev.localai.agent.container.SnapshotStore
import dev.localai.agent.data.settings.AppSettings
import dev.localai.agent.data.settings.ModelSettings
import dev.localai.agent.data.settings.PromptFormat
import dev.localai.agent.data.settings.SettingsRepository
import dev.localai.agent.inference.GenerationConfig
import dev.localai.agent.inference.GenerationResult
import dev.localai.agent.inference.LlamaEngine
import dev.localai.agent.inference.ModelInfo
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

data class TurnResult(
    val text: String,
    val steps: Int,
    val cancelled: Boolean = false,
    val error: String? = null,
)

interface AgentListener {
    fun onDelta(text: String)
    fun onAssistantMessage(text: String)
    fun onStep(current: Int, max: Int)
    fun onToolStarted(id: String, name: String, argumentsPreview: String)
    fun onToolFinished(id: String, name: String, ok: Boolean, outputPreview: String)
    fun onNotice(text: String)

    /**
     * Called before a shell command that [CommandRisk] classified as needing
     * approval. Suspends until the user answers; false declines the command and
     * the observation tells the model to try something else.
     */
    suspend fun confirmCommand(command: String): Boolean = true
}

/**
 * The agent loop: build prompt -> stream -> parse at most one tool call -> execute ->
 * feed the observation back. Bounded by steps, context and the container's own limits.
 */
class AgentLoop(
    private val engine: LlamaEngine,
    private val tools: ToolExecutor,
    private val snapshots: SnapshotStore,
) {

    suspend fun run(
        userText: String,
        history: List<ChatTurn>,
        settings: AppSettings,
        listener: AgentListener,
    ): TurnResult {
        val model = settings.model
        val maxSteps = model.maxAgentSteps.coerceIn(1, 16)
        engine.beginTurn()
        val format = PromptBuilder.detectFormat(settings.selectedModelPath.orEmpty(), model.promptFormat)
        val system = systemPrompt(settings)
        val messages = trimHistory(history.toMutableList() + ChatTurn(ChatRole.USER, userText), model)
        val conversation = messages.toMutableList()
        var finalText = ""
        var snapshotted = false
        var step = 0

        while (step < maxSteps) {
            if (engine.isCancellationRequested()) {
                listener.onNotice("Stopped.")
                return TurnResult(finalText, step, cancelled = true)
            }
            step++
            listener.onStep(step, maxSteps)

            val prompt = PromptBuilder.build(system, conversation, format)
            val result = engine.generate(
                prompt = prompt.text,
                config = GenerationConfig(
                    maxTokens = model.maxTokens,
                    temperature = model.temperature,
                    topP = model.topP,
                    topK = model.topK,
                    repeatPenalty = model.repeatPenalty,
                ),
                stopSequences = prompt.stopSequences,
            ) { delta -> listener.onDelta(delta) }

            when (result) {
                is GenerationResult.Cancelled -> {
                    finalText = result.partialText.trim()
                    if (finalText.isNotEmpty()) listener.onAssistantMessage(finalText)
                    return TurnResult(finalText, step, cancelled = true)
                }
                is GenerationResult.Failed -> {
                    listener.onNotice(result.reason)
                    return TurnResult(finalText, step, error = result.reason)
                }
                is GenerationResult.Completed -> {
                    val call = ToolCallParser.parse(result.text)
                    if (call == null) {
                        finalText = result.text.trim()
                        listener.onAssistantMessage(finalText)
                        return TurnResult(finalText, step)
                    }

                    val visible = result.text.take(call.startIndex).trim()
                    if (visible.isNotEmpty()) listener.onAssistantMessage(visible)
                    conversation += ChatTurn(ChatRole.ASSISTANT, result.text)

                    if (!snapshotted && settings.container.snapshotEnabled &&
                        call.name in setOf("write_file", "exec_shell")
                    ) {
                        snapshotted = true
                        snapshots.create(settings.container, "turn-$step")
                            .onSuccess { listener.onNotice("Workspace snapshot created (${it.name}).") }
                            .onFailure { listener.onNotice("Snapshot skipped: ${it.message}") }
                    }

                    val id = UUID.randomUUID().toString().take(8)
                    listener.onToolStarted(id, call.name, preview(call))
                    val toolResult = if (call.name == "exec_shell" &&
                        settings.container.confirmShellCommands
                    ) {
                        val command = (call.arguments["command"] as? JsonPrimitive)?.content.orEmpty()
                        if (CommandRisk.classify(command) == CommandRisk.Risk.CONFIRM &&
                            !listener.confirmCommand(command)
                        ) {
                            ToolResult(
                                false,
                                "declined: the user chose not to run this command. " +
                                    "Ask what to do differently or propose a safer approach.",
                            )
                        } else {
                            tools.execute(call, settings.container)
                        }
                    } else {
                        tools.execute(call, settings.container)
                    }
                    val output = toolResult.output.take(MAX_OBSERVATION)
                    listener.onToolFinished(id, call.name, toolResult.ok, output)

                    if (engine.isCancellationRequested()) {
                        listener.onNotice("Stopped.")
                        return TurnResult(finalText, step, cancelled = true)
                    }

                    conversation += ChatTurn(
                        ChatRole.USER,
                        PromptBuilder.observation(call.name, toolResult.ok, output),
                    )

                    if (step == maxSteps) {
                        finalText = "Stopped after $maxSteps tool steps. Ask me to continue if you want me to keep going."
                        listener.onAssistantMessage(finalText)
                        listener.onNotice("Step limit reached.")
                    }
                }
            }
        }
        return TurnResult(finalText, step)
    }

    private fun preview(call: ToolCall): String {
        val raw = call.arguments.toString()
        return if (raw.length <= 400) raw else raw.take(400) + "…"
    }

    private fun systemPrompt(settings: AppSettings): String = buildString {
        append(settings.model.systemPrompt.trim())

        val unavailable = tools.containerIssue(settings.container)
        if (unavailable != null) {
            append("\n\n# Container\n")
            append("The container is NOT available: ").append(unavailable).append('\n')
            append("Do not call any tool. Answer the user directly in plain text.\n")
            return@buildString
        }

        append("\n\n# Container\n")
        append("- Alpine Linux rootfs under PRoot with simulated root (uid 0 inside, NOT kernel root)\n")
        append("- Persistent working directory: /workspace\n")
        append("- Scratch space: /tmp; shared folder: /shared\n")
        append("- A shell, busybox tools, apk and musl libc are available. Python/Node are NOT preinstalled.\n")
        append("- Use `apk add --no-cache <pkg>` only when the task truly requires it.\n")

        append("\n# Tools\n")
        append("read_file  {\"path\":\"/workspace/file\",\"max_bytes\":32768}\n")
        append("write_file {\"path\":\"/workspace/file\",\"content\":\"...\"}\n")
        append("list_dir   {\"path\":\"/workspace\",\"depth\":1}\n")
        append("exec_shell {\"command\":\"sh -c '...'\",\"timeout_sec\":60}\n")

        append("\n# Tool protocol\n")
        append("To call a tool, output exactly one fenced block and nothing after it:\n")
        append("```tool\n{\"name\":\"exec_shell\",\"arguments\":{\"command\":\"ls -la /workspace\"}}\n```\n")
        append("Then stop and wait for a TOOL RESULT message. Do not invent tool output.\n")
        append("Call one tool at a time. When the task is complete, answer normally with no tool block.\n")
        append("Before finishing, verify your work by running it or reading the file back.\n")
    }

    /**
     * Character-budgeted history trim. Local models tokenize code badly, so this is
     * deliberately conservative; the native side rejects an overflowing prompt anyway.
     */
    private fun trimHistory(history: List<ChatTurn>, settings: ModelSettings): List<ChatTurn> {
        val budgetChars = (settings.contextSize * 3.5).toInt() - settings.maxTokens * 4 - 1500
        if (budgetChars <= 0) return history.takeLast(1)
        val out = ArrayDeque<ChatTurn>()
        var used = 0
        for (turn in history.asReversed()) {
            val cost = turn.content.length + 32
            if (out.isNotEmpty() && used + cost > budgetChars) break
            out.addFirst(turn)
            used += cost
        }
        return out.toList()
    }

    private companion object {
        const val MAX_OBSERVATION = 6 * 1024
    }
}

class AgentRuntime(
    private val engine: LlamaEngine,
    private val container: ContainerManager,
    private val settingsRepository: SettingsRepository,
) {
    private val snapshots = SnapshotStore(container)
    private val loop = AgentLoop(engine, ToolExecutor(container), snapshots)

    val modelState = engine.state

    suspend fun loadModel(path: String): Result<ModelInfo> {
        val settings = settingsRepository.settings.first()
        return engine.load(path, settings.model)
    }

    suspend fun unloadModel() = engine.unload()

    /** Drops the native KV prefix cache; call when the conversation is reset. */
    suspend fun resetConversation() = engine.resetConversation()

    suspend fun runTurn(
        userText: String,
        history: List<ChatTurn>,
        listener: AgentListener,
    ): TurnResult {
        val settings = settingsRepository.settings.first()
        return loop.run(userText, history, settings, listener)    }

    fun cancel() {
        engine.cancel()
        container.cancelActive()
    }

    fun formatFor(settings: AppSettings): PromptFormat =
        PromptBuilder.detectFormat(settings.selectedModelPath.orEmpty(), settings.model.promptFormat)
}
