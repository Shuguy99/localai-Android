package dev.localai.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localai.agent.data.settings.PromptFormat
import dev.localai.agent.ui.AppViewModelFactory
import dev.localai.agent.ui.Badge
import dev.localai.agent.ui.ScreenHeader
import dev.localai.agent.ui.SectionCard
import dev.localai.agent.ui.formatBytes
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    factory: AppViewModelFactory,
    vm: SettingsViewModel = viewModel(factory = factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val model = settings.model
    val container = settings.container

    var tokenInput by remember { mutableStateOf("") }
    var systemPrompt by remember(model.systemPrompt) { mutableStateOf(model.systemPrompt) }
    var formatMenu by remember { mutableStateOf(false) }
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val maxThreads = cores.coerceAtLeast(2)

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Settings", subtitle = "Model, prompt and container limits")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionCard(title = "Hugging Face", subtitle = if (state.tokenConfigured) "token saved" else "no token") {
                if (state.tokenConfigured) Badge("authenticated", MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HF token (optional)") },
                    placeholder = { Text("hf_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.saveToken(tokenInput)
                        tokenInput = ""
                    }, enabled = tokenInput.isNotBlank()) { Text("Save token") }
                    OutlinedButton(onClick = vm::clearToken, enabled = state.tokenConfigured) { Text("Clear") }
                }
                Text(
                    "Only needed for gated/private repos. Stored encrypted with Android Keystore; " +
                        "sent only to huggingface.co as a Bearer header.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Model generation") {
                SliderSetting(
                    label = "Temperature",
                    value = model.temperature,
                    range = 0f..2f,
                    steps = 39,
                    display = { String.format(Locale.US, "%.2f", it) },
                    onChange = { v -> vm.updateModel { it.copy(temperature = v) } },
                )
                SliderSetting(
                    label = "Top-p",
                    value = model.topP,
                    range = 0.05f..1f,
                    steps = 18,
                    display = { String.format(Locale.US, "%.2f", it) },
                    onChange = { v -> vm.updateModel { it.copy(topP = v) } },
                )
                SliderSetting(
                    label = "Top-k",
                    value = model.topK.toFloat(),
                    range = 0f..100f,
                    steps = 99,
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(topK = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "Repeat penalty",
                    value = model.repeatPenalty,
                    range = 0.8f..1.6f,
                    steps = 15,
                    display = { String.format(Locale.US, "%.2f", it) },
                    onChange = { v -> vm.updateModel { it.copy(repeatPenalty = v) } },
                )
                SliderSetting(
                    label = "Context size",
                    value = model.contextSize.toFloat(),
                    range = 512f..8192f,
                    steps = 14,
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(contextSize = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "CPU threads",
                    value = model.threads.toFloat(),
                    range = 1f..maxThreads.toFloat(),
                    steps = (maxThreads - 2).coerceAtLeast(0),
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(threads = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "GPU layers (0 = CPU only)",
                    value = model.gpuLayers.toFloat(),
                    range = 0f..99f,
                    steps = 98,
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(gpuLayers = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "Max tokens per reply",
                    value = model.maxTokens.toFloat(),
                    range = 128f..2048f,
                    steps = 29,
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(maxTokens = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "Max agent tool steps",
                    value = model.maxAgentSteps.toFloat(),
                    range = 1f..12f,
                    steps = 10,
                    display = { it.roundToInt().toString() },
                    onChange = { v -> vm.updateModel { it.copy(maxAgentSteps = v.roundToInt()) } },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompt format", modifier = Modifier.weight(1f))
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { formatMenu = true }) { Text(model.promptFormat.name) }
                        DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                            PromptFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.name) },
                                    onClick = {
                                        formatMenu = false
                                        vm.updateModel { it.copy(promptFormat = format) }
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    "AUTO detects Qwen/ChatML, Llama 3, Gemma, Mistral and Phi-3 from the file name.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System prompt") },
                    minLines = 4,
                    maxLines = 10,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.updateModel { it.copy(systemPrompt = systemPrompt) } },
                        enabled = systemPrompt != model.systemPrompt,
                    ) { Text("Save prompt") }
                    TextButton(onClick = {
                        vm.resetModel()
                        systemPrompt = dev.localai.agent.data.settings.ModelSettings.DEFAULT_SYSTEM_PROMPT
                    }) { Text("Reset model settings") }
                }
            }

            SectionCard(title = "Container") {
                SliderSetting(
                    label = "Command timeout",
                    value = container.timeoutSeconds.toFloat(),
                    range = 5f..300f,
                    steps = 58,
                    display = { "${it.roundToInt()} s" },
                    onChange = { v -> vm.updateContainer { it.copy(timeoutSeconds = v.roundToInt()) } },
                )
                SliderSetting(
                    label = "Output cap",
                    value = (container.maxOutputBytes / 1024).toFloat(),
                    range = 4f..256f,
                    steps = 62,
                    display = { "${it.roundToInt()} KB" },
                    onChange = { v -> vm.updateContainer { it.copy(maxOutputBytes = v.roundToInt() * 1024) } },
                )
                SliderSetting(
                    label = "Memory limit (ulimit -v, 0 = off)",
                    value = container.memoryLimitMb.toFloat(),
                    range = 0f..4096f,
                    steps = 31,
                    display = { if (it < 1f) "off" else "${it.roundToInt()} MB" },
                    onChange = { v -> vm.updateContainer { it.copy(memoryLimitMb = v.roundToInt()) } },
                )
                SwitchRow(
                    label = "Snapshot /workspace before each agent turn",
                    checked = container.snapshotEnabled,
                    onChange = { v -> vm.updateContainer { it.copy(snapshotEnabled = v) } },
                )
                SwitchRow(
                    label = "Simulated root (-0)",
                    checked = container.simulatedRoot,
                    onChange = { v -> vm.updateContainer { it.copy(simulatedRoot = v) } },
                )
                SwitchRow(
                    label = "Disable PRoot seccomp acceleration",
                    checked = container.noSeccomp,
                    onChange = { v -> vm.updateContainer { it.copy(noSeccomp = v) } },
                )
                SwitchRow(
                    label = "Expose /storage/emulated/0 as /mnt/host",
                    checked = container.exposeSharedStorage,
                    onChange = { v -> vm.updateContainer { it.copy(exposeSharedStorage = v) } },
                )
                if (container.exposeSharedStorage) {
                    Text(
                        "Android grants shared-storage access only after the storage permission is " +
                            "enabled in system settings; Android 11+ scoped storage may still deny it. " +
                            "Use /shared (app-private) for reliable agent files.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Output cap ${formatBytes(container.maxOutputBytes.toLong())}. " +
                        "PRoot cannot enforce a hard memory limit; ulimit -v is advisory inside the guest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = vm::resetContainer) { Text("Reset container settings") }
            }

            Text(
                "Changes persist across restarts via DataStore. Reload the model for context/thread changes to take effect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                display(local),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onChange(local) },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
