package dev.localai.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.agent.AppContainer
import dev.localai.agent.data.settings.AppSettings
import dev.localai.agent.data.settings.ContainerSettings
import dev.localai.agent.data.settings.ModelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val tokenConfigured: Boolean = false,
    val notice: String? = null,
)

class SettingsViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(tokenConfigured = app.secrets.hasToken()),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.settings.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun updateModel(transform: (ModelSettings) -> ModelSettings) {
        viewModelScope.launch { app.settings.updateModel(transform) }
    }

    fun updateContainer(transform: (ContainerSettings) -> ContainerSettings) {
        viewModelScope.launch { app.settings.updateContainer(transform) }
    }

    fun resetModel() {
        viewModelScope.launch {
            app.settings.resetModel()
            _state.update { it.copy(notice = "Model settings reset") }
        }
    }

    fun resetContainer() {
        viewModelScope.launch {
            app.settings.resetContainer()
            _state.update { it.copy(notice = "Container settings reset") }
        }
    }

    fun saveToken(token: String) {
        app.secrets.setToken(token)
        _state.update {
            it.copy(tokenConfigured = app.secrets.hasToken(), notice = "HF token saved")
        }
    }

    fun clearToken() {
        app.secrets.setToken(null)
        _state.update { it.copy(tokenConfigured = false, notice = "HF token cleared") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }
}
