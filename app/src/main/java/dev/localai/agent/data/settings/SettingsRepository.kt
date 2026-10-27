package dev.localai.agent.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.localai.agent.inference.CpuTopology
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class PromptFormat {
    AUTO, CHATML, LLAMA3, GEMMA, MISTRAL, PHI3
}

enum class Distro(val displayName: String) {
    ALPINE("Alpine 3.20 (minirootfs)")
}

data class ModelSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val contextSize: Int = 4096,
    val threads: Int = defaultThreads(),
    val kvCacheQ8: Boolean = true,
    val gpuLayers: Int = 0,
    val maxTokens: Int = 512,
    val maxAgentSteps: Int = 6,
    val promptFormat: PromptFormat = PromptFormat.AUTO,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a careful coding agent running fully on the user's Android device. " +
                "You can inspect and modify files inside a Linux container at /workspace " +
                "and run shell commands there. Prefer small, verifiable steps. " +
                "When you need the container, emit exactly one tool call and then wait for its result. " +
                "Never claim a command succeeded before you have seen its output. " +
                "Do not attempt to access the host Android filesystem outside /workspace and /shared."
    }
}

private fun defaultThreads(): Int = CpuTopology.detectBigCoreCount(Runtime.getRuntime().availableProcessors())

data class ContainerSettings(
    val distro: Distro = Distro.ALPINE,
    val rootfsDirName: String = "alpine",
    val workingDir: String = "/workspace",
    val timeoutSeconds: Int = 60,
    val maxOutputBytes: Int = 32 * 1024,
    val snapshotEnabled: Boolean = true,
    val simulatedRoot: Boolean = true,
    val memoryLimitMb: Int = 0, // 0 = unlimited (ulimit not applied)
    val noSeccomp: Boolean = false,
    val exposeSharedStorage: Boolean = false,
    /** Ask the user before shell commands that are not provably read-only. */
    val confirmShellCommands: Boolean = true,
)

data class AppSettings(
    val model: ModelSettings = ModelSettings(),
    val container: ContainerSettings = ContainerSettings(),
    val selectedModelPath: String? = null,
    val selectedModelName: String? = null,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> =
        context.settingsDataStore.data
            .catch { error ->
                // A corrupt preferences file should not take the app down; fall back
                // to defaults and let the next write repair the file.
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { it.toAppSettings() }
            .distinctUntilChanged()

    suspend fun updateModel(transform: (ModelSettings) -> ModelSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings().model)
            prefs.writeModel(next)
        }
    }

    suspend fun updateContainer(transform: (ContainerSettings) -> ContainerSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings().container)
            prefs.writeContainer(next)
        }
    }

    suspend fun selectModel(path: String, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SELECTED_MODEL_PATH] = path
            prefs[Keys.SELECTED_MODEL_NAME] = name
        }
    }

    suspend fun clearSelectedModel() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.SELECTED_MODEL_PATH)
            prefs.remove(Keys.SELECTED_MODEL_NAME)
        }
    }

    suspend fun resetModel() {
        context.settingsDataStore.edit { prefs ->
            prefs.writeModel(ModelSettings())
        }
    }

    suspend fun resetContainer() {
        context.settingsDataStore.edit { prefs ->
            prefs.writeContainer(ContainerSettings())
        }
    }

    private object Keys {
        val TEMPERATURE = floatPreferencesKey("model.temperature")
        val TOP_P = floatPreferencesKey("model.top_p")
        val TOP_K = intPreferencesKey("model.top_k")
        val REPEAT_PENALTY = floatPreferencesKey("model.repeat_penalty")
        val CONTEXT_SIZE = intPreferencesKey("model.context_size")
        val THREADS = intPreferencesKey("model.threads")
        val KV_Q8 = booleanPreferencesKey("model.kv_q8")
        val GPU_LAYERS = intPreferencesKey("model.gpu_layers")
        val MAX_TOKENS = intPreferencesKey("model.max_tokens")
        val MAX_AGENT_STEPS = intPreferencesKey("model.max_agent_steps")
        val PROMPT_FORMAT = stringPreferencesKey("model.prompt_format")
        val SYSTEM_PROMPT = stringPreferencesKey("model.system_prompt")

        val DISTRO = stringPreferencesKey("container.distro")
        val ROOTFS = stringPreferencesKey("container.rootfs")
        val WORKDIR = stringPreferencesKey("container.workdir")
        val TIMEOUT = intPreferencesKey("container.timeout")
        val MAX_OUTPUT = intPreferencesKey("container.max_output")
        val SNAPSHOT = booleanPreferencesKey("container.snapshot")
        val SIM_ROOT = booleanPreferencesKey("container.sim_root")
        val MEMORY_LIMIT = intPreferencesKey("container.memory_limit")
        val NO_SECCOMP = booleanPreferencesKey("container.no_seccomp")
        val EXPOSE_STORAGE = booleanPreferencesKey("container.expose_storage")
        val CONFIRM_SHELL = booleanPreferencesKey("container.confirm_shell")

        val SELECTED_MODEL_PATH = stringPreferencesKey("model.selected_path")
        val SELECTED_MODEL_NAME = stringPreferencesKey("model.selected_name")
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = ModelSettings()
        val cdefaults = ContainerSettings()
        val model = ModelSettings(
            temperature = this[Keys.TEMPERATURE] ?: defaults.temperature,
            topP = this[Keys.TOP_P] ?: defaults.topP,
            topK = this[Keys.TOP_K] ?: defaults.topK,
            repeatPenalty = this[Keys.REPEAT_PENALTY] ?: defaults.repeatPenalty,
            contextSize = this[Keys.CONTEXT_SIZE] ?: defaults.contextSize,
            threads = this[Keys.THREADS] ?: defaults.threads,
            kvCacheQ8 = this[Keys.KV_Q8] ?: defaults.kvCacheQ8,
            gpuLayers = this[Keys.GPU_LAYERS] ?: defaults.gpuLayers,
            maxTokens = this[Keys.MAX_TOKENS] ?: defaults.maxTokens,
            maxAgentSteps = this[Keys.MAX_AGENT_STEPS] ?: defaults.maxAgentSteps,
            promptFormat = runCatching {
                PromptFormat.valueOf(this[Keys.PROMPT_FORMAT] ?: PromptFormat.AUTO.name)
            }.getOrDefault(PromptFormat.AUTO),
            systemPrompt = this[Keys.SYSTEM_PROMPT] ?: defaults.systemPrompt,
        )
        val container = ContainerSettings(
            distro = runCatching {
                Distro.valueOf(this[Keys.DISTRO] ?: cdefaults.distro.name)
            }.getOrDefault(cdefaults.distro),
            rootfsDirName = this[Keys.ROOTFS] ?: cdefaults.rootfsDirName,
            workingDir = this[Keys.WORKDIR] ?: cdefaults.workingDir,
            timeoutSeconds = this[Keys.TIMEOUT] ?: cdefaults.timeoutSeconds,
            maxOutputBytes = this[Keys.MAX_OUTPUT] ?: cdefaults.maxOutputBytes,
            snapshotEnabled = this[Keys.SNAPSHOT] ?: cdefaults.snapshotEnabled,
            simulatedRoot = this[Keys.SIM_ROOT] ?: cdefaults.simulatedRoot,
            memoryLimitMb = this[Keys.MEMORY_LIMIT] ?: cdefaults.memoryLimitMb,
            noSeccomp = this[Keys.NO_SECCOMP] ?: cdefaults.noSeccomp,
            exposeSharedStorage = this[Keys.EXPOSE_STORAGE] ?: cdefaults.exposeSharedStorage,
            confirmShellCommands = this[Keys.CONFIRM_SHELL] ?: cdefaults.confirmShellCommands,
        )
        return AppSettings(
            model = model,
            container = container,
            selectedModelPath = this[Keys.SELECTED_MODEL_PATH],
            selectedModelName = this[Keys.SELECTED_MODEL_NAME],
        )
    }

    private fun MutablePreferences.writeModel(m: ModelSettings) {
        this[Keys.TEMPERATURE] = m.temperature
        this[Keys.TOP_P] = m.topP
        this[Keys.TOP_K] = m.topK
        this[Keys.REPEAT_PENALTY] = m.repeatPenalty
        this[Keys.CONTEXT_SIZE] = m.contextSize
        this[Keys.THREADS] = m.threads
        this[Keys.KV_Q8] = m.kvCacheQ8
        this[Keys.GPU_LAYERS] = m.gpuLayers
        this[Keys.MAX_TOKENS] = m.maxTokens
        this[Keys.MAX_AGENT_STEPS] = m.maxAgentSteps
        this[Keys.PROMPT_FORMAT] = m.promptFormat.name
        this[Keys.SYSTEM_PROMPT] = m.systemPrompt
    }

    private fun MutablePreferences.writeContainer(c: ContainerSettings) {
        this[Keys.DISTRO] = c.distro.name
        this[Keys.ROOTFS] = c.rootfsDirName
        this[Keys.WORKDIR] = c.workingDir
        this[Keys.TIMEOUT] = c.timeoutSeconds
        this[Keys.MAX_OUTPUT] = c.maxOutputBytes
        this[Keys.SNAPSHOT] = c.snapshotEnabled
        this[Keys.SIM_ROOT] = c.simulatedRoot
        this[Keys.MEMORY_LIMIT] = c.memoryLimitMb
        this[Keys.NO_SECCOMP] = c.noSeccomp
        this[Keys.EXPOSE_STORAGE] = c.exposeSharedStorage
        this[Keys.CONFIRM_SHELL] = c.confirmShellCommands
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
