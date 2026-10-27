package dev.localai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localai.agent.ui.AppViewModelFactory
import dev.localai.agent.ui.ScreenHeader
import dev.localai.agent.ui.formatBytes

@Composable
fun ChatScreen(
    factory: AppViewModelFactory,
    onOpenModels: () -> Unit,
    vm: ChatViewModel = viewModel(factory = factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            // Instant scroll: animating on every streamed token cancels/restarts the
            // animation continuously and makes long answers janky.
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ScreenHeader(
                    title = "Chat",
                    subtitle = state.modelLabel ?: "No model loaded",
                )
            }
            if (state.generating) {
                Text(
                    "step ${state.step}/${state.maxSteps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(onClick = { vm.newChat() }, enabled = !state.generating) {
                Icon(Icons.Outlined.AddComment, contentDescription = "New chat")
            }
        }

        if (!state.modelReady) {
            NoModelBanner(onOpenModels)
        }

        state.error?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp).weight(1f),
                    )
                    TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyChatHint(
                        modelReady = state.modelReady,
                        onOpenModels = onOpenModels,
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                when (message.role) {
                    MessageRole.USER -> UserBubble(message)
                    MessageRole.ASSISTANT -> AssistantBubble(message)
                    MessageRole.TOOL -> ToolBubble(message)
                    MessageRole.NOTICE -> NoticeLine(message)
                }
            }
        }

        state.pendingCommand?.let { pending ->
            CommandApprovalCard(
                pending = pending,
                onAllow = vm::allowCommand,
                onDeny = vm::denyCommand,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (state.modelReady) "Ask the agent to build something…" else "Load a model to start")
                },
                maxLines = 5,
                enabled = state.modelReady && !state.generating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send() }),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (state.generating) {
                IconButton(
                    onClick = vm::stop,
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)),
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = vm::send,
                    enabled = state.input.isNotBlank() && state.modelReady,
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandApprovalCard(
    pending: PendingCommand,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "The agent wants to run this command",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    pending.command.take(1200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAllow) { Text("Allow") }
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Text(
                    "read-only commands run without asking",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NoModelBanner(onOpenModels: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "No model loaded. Download a GGUF (Q4_K_M fits 4–8 GB phones) and load it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenModels) { Text("Browse") }
        }
    }
}

@Composable
private fun EmptyChatHint(modelReady: Boolean, onOpenModels: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Local agent", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Chat with a GGUF model on-device. When the model needs to act, it calls " +
                "read_file, write_file, list_dir or exec_shell inside the PRoot container.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!modelReady) {
            TextButton(onClick = onOpenModels) { Text("Choose a model →") }
        } else {
            Text(
                "Try: “Write a shell script in /workspace that prints the first 20 primes, then run it.”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        modifier = Modifier.fillMaxWidth(0.94f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                message.text + if (message.streaming) " ▍" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (message.streaming) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun ToolBubble(message: ChatMessage) {
    val statusColor = when (message.toolStatus) {
        ToolStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ToolStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.toolStatus == ToolStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = statusColor,
                    )
                } else {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(statusColor, RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    message.toolName ?: "tool",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (message.toolStatus) {
                        ToolStatus.RUNNING -> "running"
                        ToolStatus.SUCCESS -> "ok"
                        ToolStatus.FAILED -> "failed"
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                message.text.take(4000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.text.length > 4000) {
                Text(
                    "… ${formatBytes(message.text.length.toLong())} of text hidden",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoticeLine(message: ChatMessage) {
    Text(
        message.text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}
