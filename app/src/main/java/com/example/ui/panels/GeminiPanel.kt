package com.example.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.SuggestedCodeBlock
import com.example.model.BuildDiagnostic
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun GeminiPanel(
    activeFileName: String?,
    diagnostics: List<BuildDiagnostic>,
    messages: List<ChatMessage>,
    aiState: AiUiState,
    onSendPrompt: (String) -> Unit,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
    onClearChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var promptInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gemini in Studio",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = activeFileName?.let { "Context: $it" } ?: "Ready (online required for AI)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = onClearChat, modifier = Modifier.testTag("gemini_clear_button")) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Gemini chat")
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("gemini_close_button")) {
                    Icon(Icons.Default.Close, contentDescription = "Close Gemini panel")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Quick Action Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (diagnostics.isNotEmpty()) {
                    AssistChip(
                        onClick = {
                            onSendPrompt("Fix the build errors in this file and return the corrected full Java code.")
                        },
                        label = { Text("Fix Build Errors (${diagnostics.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                AssistChip(
                    onClick = {
                        onSendPrompt("Explain what the active file does and suggest improvements.")
                    },
                    label = { Text("Explain File") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Add a dark-themed Settings / About AlertDialog button to MainActivity.java.")
                    },
                    label = { Text("Add Dialog") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Refactor the active Java file to be cleaner and add helpful comments.")
                    },
                    label = { Text("Refactor") },
                )
            }

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Android Java Coding Copilot",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Ask Gemini to generate Activities, custom Canvas Views, XML strings, or fix ECJ compiler errors. Code blocks include a one-tap Apply button.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        activeFileName = activeFileName,
                        onApplyCode = onApplyCode,
                    )
                }

                if (aiState is AiUiState.Error) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = aiState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Prompt Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Ask Gemini to write or fix code...") },
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gemini_prompt_input"),
                )
                val isBusy = aiState is AiUiState.Streaming
                IconButton(
                    onClick = {
                        val p = promptInput.trim()
                        if (p.isNotEmpty() && !isBusy) {
                            promptInput = ""
                            onSendPrompt(p)
                        }
                    },
                    enabled = promptInput.isNotBlank() && !isBusy,
                    modifier = Modifier.testTag("gemini_send_button"),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt to Gemini")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    activeFileName: String?,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
) {
    val containerColor = if (message.isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.88f else 0.98f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.text.isEmpty() && message.isStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Thinking...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }

                message.codeBlocks.forEach { block ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = block.suggestedPath ?: (activeFileName ?: block.language.uppercase()),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { onApplyCode(block) },
                                    modifier = Modifier.testTag("apply_gemini_code_button"),
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Apply")
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.background,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = block.code.lines().take(8).joinToString("\n") +
                                        if (block.code.lines().size > 8) "\n..." else "",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
