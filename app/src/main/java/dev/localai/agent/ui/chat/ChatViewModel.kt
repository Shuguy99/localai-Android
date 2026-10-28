package dev.localai.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.agent.AppContainer
import dev.localai.agent.agent.AgentListener
import dev.localai.agent.agent.ChatRole
import dev.localai.agent.agent.ChatTurn
import dev.localai.agent.data.chat.Conversation
import dev.localai.agent.data.chat.StoredMessage
import dev.localai.agent.data.chat.StoredTurn
import dev.localai.agent.data.settings.AppSettings
import dev.localai.agent.inference.EngineState
import dev.localai.agent.service.RuntimeForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MessageRole { USER, ASSISTANT, TOOL, NOTICE }

enum class ToolStatus { RUNNING, SUCCESS, FAILED }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val streaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
)

/** A shell command waiting for the user's allow/deny decision. */
data class PendingCommand(
    val id: String,
    val command: String,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val step: Int = 0,
    val maxSteps: Int = 0,
    val modelReady: Boolean = false,
    val modelLoading: Boolean = false,
    val modelLabel: String? = null,
    val selectedModelPath: String? = null,
    val settings: AppSettings = AppSettings(),
    val error: String? = null,
    val pendingCommand: PendingCommand? = null,
    val conversationId: String? = null,
)

class ChatViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val history = mutableListOf<ChatTurn>()

    /** id of the persisted conversation; null until the first turn is saved. */
    private var conversationId: String? = null

    /** creation time of the persisted conversation, kept across saves. */
    private var conversationCreatedAt: Long = 0L

    @Volatile
    private var activeAssistantId: String? = null

    private var serviceHeld = false

    /** Resolved by [allowCommand] / [denyCommand]; awaited inside confirmCommand. */
    @Volatile
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    /** Fires when a stopped turn fails to unwind; recovers the UI. */
    private var stopWatchdog: Job? = null

    /** Incremented on every send(); lets the watchdog ignore newer turns. */
    private var turnCounter = 0L

    init {
        viewModelScope.launch {
            app.settings.settings.collect { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        maxSteps = settings.model.maxAgentSteps,
                        selectedModelPath = settings.selectedModelPath,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            restoreLatestConversation()
        }
        viewModelScope.launch {
            app.runtime.modelState.collect { engine ->
                val ready = engine as? EngineState.Ready
                _state.update {
                    it.copy(
                        modelReady = ready != null,
                        modelLoading = engine is EngineState.Loading,
                        modelLabel = ready?.info?.description ?: ready?.info?.path?.substringAfterLast('/'),
                    )
                }
                when (engine) {
                    is EngineState.Ready -> {
                        if (!serviceHeld) {
                            serviceHeld = true
                            RuntimeForegroundService.acquire(app.appContext, SERVICE_KEY)
                        }
                    }
                    EngineState.Unloaded, is EngineState.Error -> {
                        if (serviceHeld) {
                            serviceHeld = false
                            RuntimeForegroundService.release(app.appContext, SERVICE_KEY)
                        }
                    }
                    EngineState.Loading -> Unit
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun send() {
        val snapshot = _state.value
        val text = snapshot.input.trim()
        if (text.isEmpty() || snapshot.generating) return
        if (!snapshot.modelReady) {
            _state.update { it.copy(error = "Load a model first (Models tab).") }
            return
        }

        val userMessage = ChatMessage("u-${System.nanoTime()}", MessageRole.USER, text)
        _state.update {
            it.copy(
                input = "",
                messages = it.messages + userMessage,
                generating = true,
                error = null,
                step = 0,
            )
        }
        activeAssistantId = null
        stopWatchdog?.cancel()
        turnCounter++

        viewModelScope.launch {
            val listener = object : AgentListener {
                override fun onDelta(text: String) = appendDelta(text)

                override fun onAssistantMessage(text: String) = finalizeAssistant(text)

                override fun onStep(current: Int, max: Int) {
                    finalizeAssistant("")
                    _state.update { it.copy(step = current, maxSteps = max) }
                    startAssistant()
                }

                override fun onToolStarted(id: String, name: String, argumentsPreview: String) {
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                id = id,
                                role = MessageRole.TOOL,
                                text = argumentsPreview,
                                toolName = name,
                                toolStatus = ToolStatus.RUNNING,
                            ),
                        )
                    }
                }

                override fun onToolFinished(id: String, name: String, ok: Boolean, outputPreview: String) {
                    _state.update { s ->
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == id) {
                                    m.copy(
                                        text = outputPreview.ifBlank { "(no output)" },
                                        toolStatus = if (ok) ToolStatus.SUCCESS else ToolStatus.FAILED,
                                    )
                                } else {
                                    m
                                }
                            },
                        )
                    }
                }

                override fun onNotice(text: String) {
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                "n-${System.nanoTime()}",
                                MessageRole.NOTICE,
                                text,
                            ),
                        )
                    }
                }

                override suspend fun confirmCommand(command: String): Boolean {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingConfirm = deferred
                    _state.update {
                        it.copy(
                            pendingCommand = PendingCommand(
                                id = "c-${System.nanoTime()}",
                                command = command,
                            ),
                        )
                    }
                    try {
                        return deferred.await()
                    } finally {
                        pendingConfirm = null
                        _state.update { it.copy(pendingCommand = null) }
                    }
                }
            }

            val result = try {
                app.runtime.runTurn(text, history.toList(), listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Agent failed") }
                null
            } finally {
                finalizeAssistant("")
                _state.update { it.copy(generating = false) }
            }

            if (result != null) {
                history += ChatTurn(ChatRole.USER, text)
                if (result.text.isNotBlank()) history += ChatTurn(ChatRole.ASSISTANT, result.text)
                while (history.size > 24) history.removeAt(0)
                persistConversation()
            }
        }
    }

    fun stop() {
        // Cancels the native sampler and any container process owned by the turn.
        app.runtime.cancel()
        // A command waiting for approval must not block a stopped turn forever.
        resolvePending(false)
        // If the engine ignores the cancellation (a native call wedged between
        // tokens, a hung container exec), the UI would stay on the Stop button
        // with input disabled — the "only a restart helps" class of bugs. The
        // watchdog force-releases the UI; the runtime remains usable because
        // generate() serialises on the engine mutex anyway.
        stopWatchdog?.cancel()
        val stoppedTurn = turnCounter
        stopWatchdog = viewModelScope.launch {
            delay(STOP_WATCHDOG_MS)
            if (_state.value.generating && turnCounter == stoppedTurn) recoverStuckStop()
        }
    }

    private fun recoverStuckStop() {
        _state.update { s ->
            s.copy(
                generating = false,
                messages = s.messages.map { m -> if (m.streaming) m.copy(streaming = false) else m },
            )
        }
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    "n-${System.nanoTime()}",
                    MessageRole.NOTICE,
                    "Stop requested, but the engine did not respond in time. Chat recovered.",
                ),
            )
        }
    }

    fun allowCommand() = resolvePending(true)

    fun denyCommand() = resolvePending(false)

    private fun resolvePending(allowed: Boolean) {
        pendingConfirm?.complete(allowed)
    }

    fun newChat() {
        if (_state.value.generating) return
        // Keep the outgoing conversation on disk before the reset wipes it.
        if (conversationId != null && _state.value.messages.isNotEmpty()) persistConversation()
        history.clear()
        conversationId = null
        conversationCreatedAt = 0L
        activeAssistantId = null
        _state.update { it.copy(messages = emptyList(), error = null, step = 0, conversationId = null) }
        // The old conversation's KV prefix is dead weight now; drop it so the
        // next request re-decodes from a clean cache.
        viewModelScope.launch { runCatching { app.runtime.resetConversation() } }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Persists the current conversation atomically (tmp + rename inside ChatStore). */
    private fun persistConversation() {
        val id = conversationId ?: app.chatStore.newId().also {
            conversationId = it
            conversationCreatedAt = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        val messages = _state.value.messages
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        val conversation = Conversation(
            id = id,
            title = dev.localai.agent.data.chat.ChatStore.titleFrom(firstUser),
            createdAt = if (conversationCreatedAt == 0L) now else conversationCreatedAt,
            updatedAt = now,
            turns = history.map { StoredTurn(it.role.name, it.content) },
            messages = messages.filterNot { it.streaming }.map {
                StoredMessage(
                    role = it.role.name,
                    text = it.text.take(ChatStoreMaxToolText),
                    toolName = it.toolName,
                    toolStatus = it.toolStatus?.name,
                )
            },
        )
        _state.update { it.copy(conversationId = id) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.chatStore.save(conversation) }
        }
    }

    /** Brings back the most recent conversation after process death. */
    private fun restoreLatestConversation() {
        val meta = runCatching { app.chatStore.list().firstOrNull() }.getOrNull() ?: return
        val conv = runCatching { app.chatStore.load(meta.id) }.getOrNull() ?: return
        conversationId = conv.id
        conversationCreatedAt = conv.createdAt
        val uiMessages = conv.messages.mapNotNull { stored ->
            val role = runCatching { MessageRole.valueOf(stored.role) }.getOrNull() ?: return@mapNotNull null
            ChatMessage(
                id = "r-${stored.role.firstOrNull()}-${System.nanoTime()}",
                role = role,
                text = stored.text,
                toolName = stored.toolName,
                toolStatus = stored.toolStatus?.let { runCatching { ToolStatus.valueOf(it) }.getOrNull() },
            )
        }
        val turns = conv.turns.mapNotNull { stored ->
            val role = runCatching { ChatRole.valueOf(stored.role) }.getOrNull() ?: return@mapNotNull null
            ChatTurn(role, stored.text)
        }
        history.clear()
        history.addAll(turns)
        _state.update { it.copy(messages = uiMessages, conversationId = conv.id) }
    }

    private fun startAssistant() {
        val id = "a-${System.nanoTime()}"
        activeAssistantId = id
        _state.update {
            it.copy(messages = it.messages + ChatMessage(id, MessageRole.ASSISTANT, "", streaming = true))
        }
    }

    private fun appendDelta(text: String) {
        val id = activeAssistantId ?: return
        _state.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id == id) m.copy(text = m.text + text) else m
                },
            )
        }
    }

    private fun finalizeAssistant(text: String) {
        val id = activeAssistantId ?: return
        activeAssistantId = null
        _state.update { s ->
            val updated = if (text.isBlank()) {
                s.messages.filterNot { it.id == id }
            } else {
                s.messages.map { if (it.id == id) it.copy(text = text, streaming = false) else it }
            }
            s.copy(messages = updated)
        }
    }

    override fun onCleared() {
        app.runtime.cancel()
        resolvePending(false)
        if (serviceHeld) {
            serviceHeld = false
            RuntimeForegroundService.release(app.appContext, SERVICE_KEY)
        }
        super.onCleared()
    }

    private companion object {
        const val SERVICE_KEY = "model:chat"
        const val ChatStoreMaxToolText = 4096
        const val STOP_WATCHDOG_MS = 5_000L
    }
}
