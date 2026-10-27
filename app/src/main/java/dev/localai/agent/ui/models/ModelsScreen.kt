package dev.localai.agent.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localai.agent.data.hf.HfFile
import dev.localai.agent.data.hf.HfModel
import dev.localai.agent.data.hf.HfShardGroup
import dev.localai.agent.data.model.InstalledModel
import dev.localai.agent.data.model.MemoryGuard
import dev.localai.agent.inference.EngineState
import dev.localai.agent.ui.AppViewModelFactory
import dev.localai.agent.ui.Badge
import dev.localai.agent.ui.ScreenHeader
import dev.localai.agent.ui.formatBytes
import dev.localai.agent.ui.formatCount

@Composable
fun ModelsScreen(
    factory: AppViewModelFactory,
    vm: ModelsViewModel = viewModel(factory = factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Models",
            subtitle = "Hugging Face · GGUF · stored in app-private storage",
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search GGUF models, e.g. qwen2.5 coder 3b") },
                    trailingIcon = {
                        IconButton(onClick = { vm.search() }, enabled = !state.searching) {
                            if (state.searching) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Search, contentDescription = "Search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                )
            }

            state.error?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::clearError) { Text("OK") }
                        }
                    }
                }
            }

            item(key = "featured-header") {
                Text(
                    "Featured",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "featured-chips") {
                FeaturedRow(
                    enabled = state.selected == null && !state.loadingDetails,
                    onSelect = vm::selectRepo,
                )
            }

            state.selected?.let { model ->
                item(key = "selected-header") {
                    SelectedHeader(model, onClose = vm::closeSelection)
                }
                if (state.loadingDetails) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                items(state.files, key = { it.modelId + "/" + it.fileName }) { file ->
                    FileRow(
                        file = file,
                        download = state.downloads["${file.modelId}/${file.fileName}"],
                        ramLevel = vm.remoteFileLevel(file.size),
                        onDownload = { vm.download(file) },
                        onCancel = { vm.cancelDownload("${file.modelId}/${file.fileName}") },
                        onDismiss = { vm.dismissDownload("${file.modelId}/${file.fileName}") },
                    )
                }
                items(state.shardGroups, key = { "shard:" + it.modelId + "/" + it.baseName }) { group ->
                    ShardGroupRow(
                        group = group,
                        download = state.downloads["shard:${group.modelId}/${group.baseName}"],
                        ramLevel = vm.remoteFileLevel(group.totalSize),
                        onDownload = { vm.downloadShardGroup(group) },
                        onCancel = { vm.cancelDownload("shard:${group.modelId}/${group.baseName}") },
                        onDismiss = { vm.dismissDownload("shard:${group.modelId}/${group.baseName}") },
                    )
                }
                if (!state.loadingDetails && state.files.isEmpty() && state.shardGroups.isEmpty()) {
                    item {
                        Text(
                            "No loadable GGUF found in this repo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "installed-header") {
                Text(
                    "Installed on device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.installed.isEmpty()) {
                item {
                    Text(
                        "No models downloaded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.installed, key = { it.path }) { model ->
                InstalledRow(
                    model = model,
                    loaded = state.loadedPath == model.path,
                    loading = state.engineState is EngineState.Loading,
                    onLoad = { vm.requestLoad(model.path) },
                    onDelete = { vm.delete(model) },
                )
            }

            item(key = "results-header") {
                Text(
                    "Search results",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.results, key = { it.repoId }) { model ->
                ResultRow(model = model, onClick = { vm.select(model) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    state.pendingLoadPath?.let { path ->
        val estimate = state.memoryEstimate
        AlertDialog(
            onDismissRequest = vm::cancelPendingLoad,
            title = { Text("Memory warning") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (estimate?.level == MemoryGuard.Estimate.Level.DANGER) {
                            "This model is likely too large for the RAM currently available."
                        } else {
                            "This model will use a large share of available RAM."
                        },
                    )
                    if (estimate != null) {
                        Text(
                            "Weights + KV cache ≈ ${formatBytes(estimate.totalBytes)}\n" +
                                "Available RAM ≈ ${formatBytes(estimate.availableBytes)}\n" +
                                "Estimated ratio: ${(estimate.ratio * 100).toInt()}% of available\n" +
                                "KV estimate source: ${estimate.kvCacheSource}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        "Android may kill the app if other apps need memory. " +
                            "Close background apps or reduce context size in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmPendingLoad) { Text("Load anyway") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPendingLoad) { Text("Cancel") }
            },
        )
    }
}

/**
 * Curated single-file GGUF repos, ungated, covering the 1B–4B range that fits
 * mid-range phones. Tapping opens the repo file list via the normal details flow.
 */
private val featuredRepos = listOf(
    "Qwen/Qwen2.5-1.5B-Instruct-GGUF" to "Qwen2.5 1.5B",
    "Qwen/Qwen2.5-3B-Instruct-GGUF" to "Qwen2.5 3B",
    "bartowski/Llama-3.2-3B-Instruct-GGUF" to "Llama 3.2 3B",
    "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF" to "DeepSeek-R1 1.5B",
    "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF" to "SmolLM2 1.7B",
    "bartowski/Phi-3.5-mini-instruct-GGUF" to "Phi-3.5 mini",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeaturedRow(enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        featuredRepos.forEach { (repoId, label) ->
            AssistChip(
                onClick = { if (enabled) onSelect(repoId) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun SelectedHeader(model: HfModel, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.repoId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("↓ ${formatCount(model.downloads)}")
                Badge("♥ ${formatCount(model.likes)}")
                if (model.isGated) Badge("gated", MaterialTheme.colorScheme.error)
            }
            if (model.isGated) {
                Text(
                    "Gated repo: accept the license on huggingface.co and set an HF token in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HfFile,
    download: DownloadUi?,
    ramLevel: MemoryGuard.Estimate.Level?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(file.quant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        file.size?.let { formatBytes(it) } ?: "size unknown",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ramLevel?.let { level ->
                    val (label, color) = when (level) {
                        MemoryGuard.Estimate.Level.OK -> "fits" to MaterialTheme.colorScheme.primary
                        MemoryGuard.Estimate.Level.WARN -> "tight" to MaterialTheme.colorScheme.secondary
                        MemoryGuard.Estimate.Level.DANGER -> "risky" to MaterialTheme.colorScheme.error
                    }
                    Badge(label, color)
                    Spacer(Modifier.width(6.dp))
                }
                when (download?.status) {
                    DownloadStatus.RUNNING -> IconButton(onClick = onCancel) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    DownloadStatus.DONE -> TextButton(onClick = onDismiss) { Text("Done") }
                    DownloadStatus.FAILED -> TextButton(onClick = onDownload) { Text("Retry") }
                    null -> IconButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download")
                    }
                }
            }
            if (download != null && download.status == DownloadStatus.RUNNING) {
                if (download.progress != null) {
                    LinearProgressIndicator(
                        progress = { download.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(download.bytes)} / ${formatBytes(download.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                download.message?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (download?.status == DownloadStatus.FAILED) {
                Text(
                    download.message ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (download?.status == DownloadStatus.DONE) {
                Text(
                    "Downloaded and verified (GGUF magic + size).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ShardGroupRow(
    group: HfShardGroup,
    download: DownloadUi?,
    ramLevel: MemoryGuard.Estimate.Level?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(group.quant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.baseName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${group.partCount} parts" + (group.totalSize?.let { ", ${formatBytes(it)} total" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ramLevel?.let { level ->
                    val (label, color) = when (level) {
                        MemoryGuard.Estimate.Level.OK -> "fits" to MaterialTheme.colorScheme.primary
                        MemoryGuard.Estimate.Level.WARN -> "tight" to MaterialTheme.colorScheme.secondary
                        MemoryGuard.Estimate.Level.DANGER -> "risky" to MaterialTheme.colorScheme.error
                    }
                    Badge(label, color)
                    Spacer(Modifier.width(6.dp))
                }
                when (download?.status) {
                    DownloadStatus.RUNNING -> IconButton(onClick = onCancel) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    DownloadStatus.DONE -> TextButton(onClick = onDismiss) { Text("Done") }
                    DownloadStatus.FAILED -> TextButton(onClick = onDownload) { Text("Retry") }
                    null -> IconButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download shards")
                    }
                }
            }
            if (download != null && download.status == DownloadStatus.RUNNING) {
                if (download.progress != null) {
                    LinearProgressIndicator(
                        progress = { download.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(download.bytes)} / ${formatBytes(download.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                download.message?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (download?.status == DownloadStatus.FAILED) {
                Text(
                    download.message ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (download?.status == DownloadStatus.DONE) {
                Text(
                    "All parts downloaded; loads from the first shard.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun InstalledRow(
    model: InstalledModel,
    loaded: Boolean,
    loading: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (loaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatBytes(model.sizeBytes))
                        model.quant?.let { append(" · ").append(it) }
                        model.repoId?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (loaded) {
                Badge("loaded")
            } else {
                TextButton(onClick = onLoad) { Text("Load") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ResultRow(model: HfModel, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                model.repoId,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("↓ ${formatCount(model.downloads)}")
                Badge("♥ ${formatCount(model.likes)}", MaterialTheme.colorScheme.secondary)
                if (model.tags.any { it.equals("gguf", true) }) Badge("GGUF")
                if (model.isGated) Badge("gated", MaterialTheme.colorScheme.error)
            }
        }
    }
}
