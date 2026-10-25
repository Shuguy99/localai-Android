package dev.localai.agent.inference

import dev.localai.agent.data.settings.ModelSettings
import dev.localai.agent.data.settings.PromptFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class ModelInfo(
    val path: String,
    val description: String,
    val parameters: Long,
    val sizeBytes: Long,
    val nCtxTrain: Int,
    val nEmbd: Int,
    val nHead: Int,
    val nHeadKv: Int,
)

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val info: ModelInfo) : EngineState
    data class Error(val message: String) : EngineState
}

data class GenerationConfig(
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
)

sealed interface GenerationResult {
    data class Completed(val text: String, val tokens: Int) : GenerationResult
    data class Failed(val reason: String) : GenerationResult
    data class Cancelled(val partialText: String) : GenerationResult
}

/**
 * Owns the single loaded model. Generation is serialised: one model, one
 * persistent context, one request at a time. The native side reuses the KV
 * prefix between requests, so agent steps that share the system prompt and
 * history re-decode only their tail. Callers run on a background dispatcher;
 * the UI never blocks.
 */
class LlamaEngine {

    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()
    private var handle: Long = 0L

    /**
     * Latched for the whole agent turn. A simple flag in the native session is not
     * enough: pressing Stop between two tool steps would be reset by the next
     * generate() call. beginTurn() clears it, cancel() sets it.
     */
    private val cancelRequested = AtomicBoolean(false)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    suspend fun load(path: String, settings: ModelSettings): Result<ModelInfo> = mutex.withLock {
        LlamaBridge.ensureLoaded()?.let { err ->
            val message = "Native runtime unavailable: $err. Build with scripts/fetch_deps.sh."
            _state.value = EngineState.Error(message)
            return@withLock Result.failure(IllegalStateException(message))
        }

        if (handle != 0L) {
            withContext(Dispatchers.Default) { LlamaBridge.freeModel(handle) }
            handle = 0L
        }

        _state.value = EngineState.Loading
        val threads = settings.threads.coerceIn(1, Runtime.getRuntime().availableProcessors())
        val ctx = settings.contextSize.coerceIn(512, 32768)
        val batch = minOf(512, ctx)

        val loaded = withContext(Dispatchers.Default) {
            runCatching {
                LlamaBridge.loadModel(path, ctx, threads, settings.gpuLayers, batch)
            }
        }.getOrElse { t ->
            val message = t.message ?: "Model load failed"
            _state.value = EngineState.Error(message)
            return@withLock Result.failure(t)
        }

        if (loaded == 0L) {
            val message = "llama.cpp could not load this GGUF. The file may be corrupt, " +
                "sharded, or use an unsupported architecture."
            _state.value = EngineState.Error(message)
            return@withLock Result.failure(IllegalStateException(message))
        }

        handle = loaded
        val info = runCatching { parseInfo(path, LlamaBridge.modelInfo(loaded)) }.getOrNull()
            ?: ModelInfo(path, path.substringAfterLast('/'), 0, 0, ctx, 0, 0, 0)
        _state.value = EngineState.Ready(info)
        Result.success(info)
    }

    suspend fun unload() = mutex.withLock {
        if (handle != 0L) {
            withContext(Dispatchers.Default) { LlamaBridge.freeModel(handle) }
            handle = 0L
        }
        _state.value = EngineState.Unloaded
    }

    /**
     * Drops the native KV prefix cache. Called when the user starts a new chat:
     * the next request then re-decodes from scratch instead of matching against
     * tokens that belong to the discarded conversation.
     */
    suspend fun resetConversation() = mutex.withLock {
        val h = handle
        if (h != 0L) withContext(Dispatchers.Default) { LlamaBridge.resetContext(h) }
    }

    fun isReady(): Boolean = _state.value is EngineState.Ready && handle != 0L

    /** Call once at the beginning of a new user turn. */
    fun beginTurn() {
        cancelRequested.set(false)
    }

    fun isCancellationRequested(): Boolean = cancelRequested.get()

    fun cancel() {
        cancelRequested.set(true)
        val h = handle
        if (h != 0L) LlamaBridge.cancel(h)
    }

    suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
    ): GenerationResult = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock GenerationResult.Failed("No model is loaded.")
        if (cancelRequested.get()) return@withLock GenerationResult.Cancelled("")

        val textBuilder = StringBuilder()
        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                textBuilder.append(text)
                onToken(text)
            }
        }


        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine<GenerationResult> { cont ->
                cont.invokeOnCancellation { LlamaBridge.cancel(h) }
                val result = try {
                    LlamaBridge.generate(
                        h,
                        prompt,
                        config.maxTokens.coerceIn(1, 8192),
                        config.temperature,
                        config.topP,
                        config.topK,
                        config.repeatPenalty,
                        stopSequences.toTypedArray(),
                        sink,
                    )
                } catch (t: Throwable) {
                    val failure = GenerationResult.Failed(t.message ?: "Native generation failed")
                    if (cont.isActive) cont.resume(failure)
                    return@suspendCancellableCoroutine
                }

                val outcome = when {
                    result == -1 -> GenerationResult.Cancelled(textBuilder.toString())
                    result == -3 -> GenerationResult.Failed(
                        "Prompt is larger than the context window. Lower history or raise context size.",
                    )
                    result < 0 -> GenerationResult.Failed("llama.cpp generation error ($result).")
                    cancelRequested.get() -> GenerationResult.Cancelled(textBuilder.toString())
                    else -> GenerationResult.Completed(textBuilder.toString(), result)
                }
                if (cont.isActive) cont.resume(outcome)
            }
        }
    }

    private fun parseInfo(path: String, raw: String): ModelInfo {
        val obj = json.parseToJsonElement(raw).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.content ?: ""
        fun int(key: String) = obj[key]?.jsonPrimitive?.int ?: 0
        fun long(key: String) = obj[key]?.jsonPrimitive?.long ?: 0L
        return ModelInfo(
            path = path,
            description = str("desc"),
            parameters = long("params"),
            sizeBytes = long("sizeBytes"),
            nCtxTrain = int("nCtxTrain"),
            nEmbd = int("nEmbd"),
            nHead = int("nHead"),
            nHeadKv = int("nHeadKv"),
        )
    }

    fun detectFormat(modelPath: String, configured: PromptFormat): PromptFormat {
        if (configured != PromptFormat.AUTO) return configured
        val name = modelPath.lowercase()
        return when {
            name.contains("llama-3") || name.contains("llama3") -> PromptFormat.LLAMA3
            name.contains("gemma") -> PromptFormat.GEMMA
            name.contains("mistral") || name.contains("mixtral") -> PromptFormat.MISTRAL
            name.contains("phi-3") || name.contains("phi3") -> PromptFormat.PHI3
            else -> PromptFormat.CHATML
        }
    }
}
