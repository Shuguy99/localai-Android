package dev.localai.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.localai.agent.AppContainer
import dev.localai.agent.ui.chat.ChatViewModel
import dev.localai.agent.ui.container.ContainerViewModel
import dev.localai.agent.ui.models.ModelsViewModel
import dev.localai.agent.ui.settings.SettingsViewModel

class AppViewModelFactory(private val app: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(app) as T
        modelClass.isAssignableFrom(ModelsViewModel::class.java) -> ModelsViewModel(app) as T
        modelClass.isAssignableFrom(ContainerViewModel::class.java) -> ContainerViewModel(app) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(app) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
