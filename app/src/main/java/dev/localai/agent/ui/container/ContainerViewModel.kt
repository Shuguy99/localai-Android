package dev.localai.agent.ui.container

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.agent.AppContainer
import dev.localai.agent.container.ContainerState
import dev.localai.agent.container.SnapshotStore
import dev.localai.agent.data.settings.AppSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ContainerUiState(
    val status: ContainerState = ContainerState.NotInstalled,
    val settings: AppSettings = AppSettings(),
    val output: String = "",
    val running: Boolean = false,
    val command: String = "echo hello && uname -a && id",
    val snapshots: List<SnapshotStore.Snapshot> = emptyList(),
    val notice: String? = null,
)

class ContainerViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ContainerUiState())
    val state: StateFlow<ContainerUiState> = _state.asStateFlow()

    init {
        app.container.refresh()
        viewModelScope.launch {
            app.container.state.collect { s -> _state.update { it.copy(status = s) } }
        }
        viewModelScope.launch {
            app.settings.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        refreshSnapshots()
    }

    fun install() {
        viewModelScope.launch {
            app.container.install().onFailure { e ->
                _state.update { it.copy(notice = e.message) }
            }
            refreshSnapshots()
        }
    }

    fun reset() {
        viewModelScope.launch {
            app.container.reset()
            _state.update { it.copy(output = "", snapshots = emptyList()) }
        }
    }

    fun onCommandChange(value: String) {
        _state.update { it.copy(command = value) }
    }

    fun run() {
        val command = _state.value.command.trim()
        if (command.isEmpty() || _state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, output = it.output + "\n$ " + command + "\n") }
            val result = app.container.exec(command, _state.value.settings.container)
            val block = buildString {
                append(result.output.ifBlank { "(no output)" })
                if (result.truncated) append("\n[output truncated]")
                if (result.timedOut) append("\n[timed out]")
                if (result.exitCode >= 0) append("\n[exit ${result.exitCode} in ${result.durationMs} ms]")
                else if (result.exitCode == -2) append("\n[container unavailable]")
            }
            _state.update {
                it.copy(running = false, output = it.output + block + "\n")
            }
            refreshSnapshots()
        }
    }

    fun restore(snapshot: SnapshotStore.Snapshot) {
        viewModelScope.launch {
            _state.update { it.copy(notice = "Restoring ${snapshot.name}…") }
            app.snapshots.restore(snapshot.name, _state.value.settings.container)
                .onSuccess { _state.update { s -> s.copy(notice = "Restored ${snapshot.name}") } }
                .onFailure { e -> _state.update { s -> s.copy(notice = e.message) } }
        }
    }

    fun clearOutput() {
        _state.update { it.copy(output = "") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun refreshSnapshots() {
        _state.update { it.copy(snapshots = app.snapshots.list()) }
    }
}
