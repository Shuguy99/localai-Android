package dev.localai.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.agent.AppContainer
import dev.localai.agent.agent.AgentListener
import dev.localai.agent.agent.ChatRole
import dev.localai.agent.agent.ChatTurn
import dev.localai.agent.data.settings.AppSettings
import dev.localai.agent.inference.EngineState
import dev.localai.agent.service.RuntimeForegroundService
import kotlinx.coroutines.CancellationException
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

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val step: Int = 0,
    val maxSteps: Int = 0,
    val modelReady: Boolean = false,
    val modelLabel: String? = null,
    val selectedModelPath: String? = null,
    val settings: AppSettings = AppSettings(),
    val error: String? = null,
)

class ChatViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val history = mutableListOf<ChatTurn>()

    @Volatile
    private var activeAssistantId: String? = null

    private var serviceHeld = false

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
        viewModelScope.launch {
            app.runtime.modelState.collect { engine ->
                val ready = engine as? EngineState.Ready
                _state.update {
                    it.copy(
                        modelReady = ready != null,
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
            }
        }
    }

    fun stop() {
        // Cancels the native sampler and any container process owned by the turn.
        app.runtime.cancel()
    }

    fun newChat() {
        if (_state.value.generating) return
        history.clear()
        activeAssistantId = null
        _state.update { it.copy(messages = emptyList(), error = null, step = 0) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
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
        if (serviceHeld) {
            serviceHeld = false
            RuntimeForegroundService.release(app.appContext, SERVICE_KEY)
        }
        super.onCleared()
    }

    private companion object {
        const val SERVICE_KEY = "model:chat"
    }
}
