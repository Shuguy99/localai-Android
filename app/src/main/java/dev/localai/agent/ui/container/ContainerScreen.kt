package dev.localai.agent.ui.container

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localai.agent.BuildConfig
import dev.localai.agent.container.ContainerState
import dev.localai.agent.ui.AppViewModelFactory
import dev.localai.agent.ui.Badge
import dev.localai.agent.ui.KeyValueRow
import dev.localai.agent.ui.ScreenHeader
import dev.localai.agent.ui.SectionCard
import dev.localai.agent.ui.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ContainerScreen(
    factory: AppViewModelFactory,
    vm: ContainerViewModel = viewModel(factory = factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val outputScroll = rememberScrollState()

    LaunchedEffect(state.output) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Container",
            subtitle = "Alpine · PRoot · simulated root (no host root needed)",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionCard(title = "Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (text, color) = when (val s = state.status) {
                        ContainerState.NotInstalled -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
                        is ContainerState.Installing -> "Installing ${(s.progress * 100).toInt()}%" to MaterialTheme.colorScheme.secondary
                        ContainerState.Ready -> "Ready" to MaterialTheme.colorScheme.primary
                        is ContainerState.Error -> "Unavailable" to MaterialTheme.colorScheme.error
                    }
                    Badge(text, color)
                }
                when (val s = state.status) {
                    is ContainerState.Installing -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text(s.message, style = MaterialTheme.typography.labelSmall)
                    }
                    is ContainerState.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                KeyValueRow("Rootfs", state.settings.container.rootfsDirName)
                KeyValueRow("Working dir", state.settings.container.workingDir)
                KeyValueRow("Timeout", "${state.settings.container.timeoutSeconds}s")
                KeyValueRow("Output cap", formatBytes(state.settings.container.maxOutputBytes.toLong()))
                KeyValueRow("Snapshots", if (state.settings.container.snapshotEnabled) "on (per turn)" else "off")
                KeyValueRow("UID inside", if (state.settings.container.simulatedRoot) "0 (simulated)" else "app uid")
                Text(
                    "PRoot uses ptrace to emulate root and path remapping. It is not kernel root and " +
                        "cannot modify /system. The container lives entirely in app-private storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::install,
                        enabled = BuildConfig.PROOT_EXEC_ALLOWED &&
                            state.status !is ContainerState.Installing &&
                            state.status != ContainerState.Ready,
                    ) {
                        Text(
                            when {
                                !BuildConfig.PROOT_EXEC_ALLOWED -> "Container disabled in this build"
                                state.status == ContainerState.Ready -> "Installed"
                                state.status is ContainerState.Error -> "Retry install"
                                else -> "Install Alpine"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        enabled = !state.running && state.status !is ContainerState.Installing,
                    ) { Text("Reset") }
                }
            }

            SectionCard(title = "Shell", subtitle = "runs as /bin/sh -lc via PRoot") {
                OutlinedTextField(
                    value = state.command,
                    onValueChange = vm::onCommandChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Command") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !state.running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.run() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::run, enabled = !state.running && state.status == ContainerState.Ready) {
                        if (state.running) {
                            CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        }
                        Text(if (state.running) "Running" else "Run")
                    }
                    TextButton(onClick = vm::clearOutput) { Text("Clear") }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .height(200.dp)
                            .verticalScroll(outputScroll)
                            .padding(8.dp),
                    ) {
                        Text(
                            state.output.ifBlank { "Output appears here. Try: echo hello && uname -a" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            SectionCard(title = "Snapshots", subtitle = "/workspace") {
                if (state.snapshots.isEmpty()) {
                    Text(
                        "No snapshots yet. One is taken automatically before each agent turn when enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.snapshots.forEach { snap ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(snap.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    formatBytes(snap.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.restore(snap) }) { Text("Restore") }
                        }
                    }
                }
            }

            Text(
                "Need different limits? Container settings are in the Settings tab.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset container?") },
            text = {
                Text("This deletes the Alpine rootfs, /workspace, /shared and all snapshots. Downloaded models are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.reset()
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    state.notice?.let {
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            title = { Text("Container") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
        )
    }
}
