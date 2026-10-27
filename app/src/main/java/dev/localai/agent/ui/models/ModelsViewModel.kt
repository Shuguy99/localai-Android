package dev.localai.agent.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.agent.AppContainer
import dev.localai.agent.data.hf.HfException
import dev.localai.agent.data.hf.HfFile
import dev.localai.agent.data.hf.HfModel
import dev.localai.agent.data.model.DownloadEvent
import dev.localai.agent.data.model.InstalledModel
import dev.localai.agent.data.model.MemoryGuard
import dev.localai.agent.data.model.ModelSidecar
import dev.localai.agent.data.settings.AppSettings
import dev.localai.agent.inference.EngineState
import dev.localai.agent.service.RuntimeForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class DownloadStatus { RUNNING, DONE, FAILED }

data class DownloadUi(
    val key: String,
    val fileName: String,
    val bytes: Long = 0,
    val total: Long = -1,
    val status: DownloadStatus = DownloadStatus.RUNNING,
    val message: String? = null,
) {
    val progress: Float?
        get() = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

data class ModelsUiState(
    val query: String = "qwen2.5 3b instruct",
    val searching: Boolean = false,
    val results: List<HfModel> = emptyList(),
    val error: String? = null,
    val selected: HfModel? = null,
    val loadingDetails: Boolean = false,
    val files: List<HfFile> = emptyList(),
    val downloads: Map<String, DownloadUi> = emptyMap(),
    val installed: List<InstalledModel> = emptyList(),
    val engineState: EngineState = EngineState.Unloaded,
    val loadedPath: String? = null,
    val settings: AppSettings = AppSettings(),
    val memoryEstimate: MemoryGuard.Estimate? = null,
    val pendingLoadPath: String? = null,
    val availableRamBytes: Long = 0,
)

class ModelsViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private val downloadJobs = HashMap<String, Job>()
    private var serviceHeld = false

    init {
        viewModelScope.launch {
            app.settings.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            app.runtime.modelState.collect { engine ->
                _state.update {
                    it.copy(
                        engineState = engine,
                        loadedPath = (engine as? EngineState.Ready)?.info?.path,
                    )
                }
                val ready = engine is EngineState.Ready
                if (ready && !serviceHeld) {
                    serviceHeld = true
                    RuntimeForegroundService.acquire(app.appContext, SERVICE_KEY)
                } else if (!ready && serviceHeld) {
                    serviceHeld = false
                    RuntimeForegroundService.release(app.appContext, SERVICE_KEY)
                }
            }
        }
        refreshInstalled()
        refreshAvailableRam()
        search()
    }

    /** Available RAM is a moving target; re-read it whenever the screen refreshes. */
    private fun refreshAvailableRam() {
        _state.update { it.copy(availableRamBytes = app.memoryGuard.availableRam()) }
    }

    /** Opens a featured catalog entry without a round-trip through search. */
    fun selectRepo(repoId: String) {
        select(HfModel(id = repoId))
    }

    /**
     * Pre-download RAM fit for a remote file: no GGUF header exists yet, so the
     * KV cache uses the conservative per-token heuristic.
     */
    fun remoteFileLevel(sizeBytes: Long?): MemoryGuard.Estimate.Level? {
        val size = sizeBytes ?: return null
        if (size <= 0 || _state.value.availableRamBytes <= 0) return null
        return app.memoryGuard
            .estimateForSize(size, _state.value.settings.model.contextSize, _state.value.availableRamBytes)
            .level
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        refreshAvailableRam()
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            try {
                val results = withContext(Dispatchers.IO) { app.hf.searchModels(query) }
                _state.update { it.copy(results = results, searching = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        searching = false,
                        error = e.message ?: "Search failed",
                    )
                }
            }
        }
    }

    fun select(model: HfModel) {
        val requestedRepo = model.repoId
        _state.update { it.copy(selected = model, loadingDetails = true, files = emptyList(), error = null) }
        viewModelScope.launch {
            val details = try {
                withContext(Dispatchers.IO) { app.hf.modelDetails(requestedRepo) }
            } catch (e: Exception) {
                if (_state.value.selected?.repoId == requestedRepo) {
                    _state.update {
                        it.copy(
                            loadingDetails = false,
                            error = if (e is HfException) e.message else "Could not load files: ${e.message}",
                        )
                    }
                }
                return@launch
            }
            if (_state.value.selected?.repoId != requestedRepo) return@launch
            val files = details.ggufFiles().map {
                HfFile(
                    modelId = details.repoId,
                    fileName = it.rfilename,
                    size = it.effectiveSize,
                    quant = HfModel.quantLabel(it.rfilename),
                )
            }
            _state.update { it.copy(loadingDetails = false, files = files) }
        }
    }

    fun closeSelection() {
        _state.update { it.copy(selected = null, files = emptyList(), loadingDetails = false) }
    }

    fun download(file: HfFile) {
        val key = "${file.modelId}/${file.fileName}"
        if (downloadJobs[key]?.isActive == true) return
        _state.update {
            it.copy(downloads = it.downloads + (key to DownloadUi(key, file.fileName)))
        }
        RuntimeForegroundService.acquire(app.appContext, downloadKey(key))
        downloadJobs[key] = viewModelScope.launch {
            try {
                app.downloader.download(file.modelId, file.fileName, file.size).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> _state.update { s ->
                            val d = s.downloads[key] ?: return@update s
                            s.copy(
                                downloads = s.downloads + (key to d.copy(
                                    bytes = event.bytes,
                                    total = event.totalBytes,
                                )),
                            )
                        }
                        DownloadEvent.Verifying -> _state.update { s ->
                            val d = s.downloads[key] ?: return@update s
                            s.copy(downloads = s.downloads + (key to d.copy(message = "Verifying…")))
                        }
                        is DownloadEvent.Done -> {
                            withContext(Dispatchers.IO) {
                                app.modelStore.writeSidecar(
                                    event.file,
                                    ModelSidecar(
                                        repoId = file.modelId,
                                        fileName = file.fileName,
                                        quant = file.quant,
                                        sizeBytes = event.bytes,
                                        downloadedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            _state.update { s ->
                                val d = s.downloads[key] ?: return@update s
                                s.copy(downloads = s.downloads + (key to d.copy(
                                    bytes = event.bytes,
                                    total = event.bytes,
                                    status = DownloadStatus.DONE,
                                    message = null,
                                )))
                            }
                            refreshInstalled()
                        }
                    }
                }
            } catch (e: CancellationException) {
                _state.update { s -> s.copy(downloads = s.downloads - key) }
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    val d = s.downloads[key] ?: DownloadUi(key, file.fileName)
                    s.copy(
                        downloads = s.downloads + (key to d.copy(
                            status = DownloadStatus.FAILED,
                            message = e.message ?: "Download failed",
                        )),
                    )
                }
            } finally {
                downloadJobs.remove(key)
                RuntimeForegroundService.release(app.appContext, downloadKey(key))
            }
        }
    }

    fun cancelDownload(key: String) {
        downloadJobs[key]?.cancel()
        downloadJobs.remove(key)
        _state.update { it.copy(downloads = it.downloads - key) }
    }

    fun dismissDownload(key: String) {
        _state.update { it.copy(downloads = it.downloads - key) }
    }

    fun requestLoad(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.isFile) {
                _state.update { it.copy(error = "Model file is missing: $path") }
                return@launch
            }
            val estimate = withContext(Dispatchers.IO) {
                app.memoryGuard.estimate(
                    file,
                    _state.value.settings.model.contextSize,
                    _state.value.settings.model.kvCacheQ8,
                )
            }
            if (estimate.level == MemoryGuard.Estimate.Level.OK) {
                doLoad(path)
            } else {
                _state.update { it.copy(memoryEstimate = estimate, pendingLoadPath = path) }
            }
        }
    }

    fun confirmPendingLoad() {
        val path = _state.value.pendingLoadPath ?: return
        _state.update { it.copy(pendingLoadPath = null) }
        doLoad(path)
    }

    fun cancelPendingLoad() {
        _state.update { it.copy(pendingLoadPath = null, memoryEstimate = null) }
    }

    private fun doLoad(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            app.settings.selectModel(path, File(path).name)
            app.runtime.loadModel(path).onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Failed to load model") }
            }
        }
    }

    fun unload() {
        viewModelScope.launch { app.runtime.unloadModel() }
    }

    fun delete(model: InstalledModel) {
        viewModelScope.launch {
            if (_state.value.loadedPath == model.path) app.runtime.unloadModel()
            withContext(Dispatchers.IO) { app.modelStore.delete(model.file) }
            refreshInstalled()
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun refreshInstalled() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { app.modelStore.installed() }
            _state.update { it.copy(installed = installed) }
        }
    }

    override fun onCleared() {
        if (serviceHeld) {
            serviceHeld = false
            RuntimeForegroundService.release(app.appContext, SERVICE_KEY)
        }
        // Jobs may be cancelled before their finally block runs; release their keys.
        downloadJobs.keys.toList().forEach {
            RuntimeForegroundService.release(app.appContext, downloadKey(it))
        }
        downloadJobs.clear()
        super.onCleared()
    }

    private companion object {
        const val SERVICE_KEY = "model:models"

        fun downloadKey(key: String) = "download:$key"
    }
}
