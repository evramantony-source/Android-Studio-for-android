package com.example.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AgentStep
import com.example.ai.AgentStepStatus
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import com.example.ai.SuggestedCodeBlock
import com.example.model.BuildDiagnostic
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun GeminiPanel(
    activeFileName: String?,
    customApiKey: String,
    diagnostics: List<BuildDiagnostic>,
    messages: List<ChatMessage>,
    aiState: AiUiState,
    onSendPrompt: (String) -> Unit,
    onStopGemini: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onApplyCode: (SuggestedCodeBlock) -> Unit,
    onClearChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var promptInput by remember { mutableStateOf("") }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val hasEffectiveKey = remember(customApiKey) {
        GeminiCodeAssistant.resolveApiKey(customApiKey).isNotEmpty()
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, messages.lastOrNull()?.steps?.size) {
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
                        text = "Gemini Agent",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = buildString {
                            append(GEMINI_MODEL_ID)
                            append(" • ")
                            when {
                                !hasEffectiveKey -> append("API key needed")
                                activeFileName != null -> append("Auto-edit • $activeFileName")
                                else -> append("Auto-edit ready")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasEffectiveKey) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { showApiKeyDialog = true },
                    modifier = Modifier.testTag("gemini_api_key_button"),
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Configure Gemini API key",
                        tint = if (hasEffectiveKey) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
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
                            onSendPrompt("Inspect the build errors, edit the broken files directly, and rebuild the APK until it succeeds.")
                        },
                        label = { Text("Auto-Fix Errors (${diagnostics.size})") },
                        leadingIcon = {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                AssistChip(
                    onClick = {
                        onSendPrompt("Inspect the project files and explain the app architecture.")
                    },
                    label = { Text("Inspect Project") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Add a dark-themed Settings / About AlertDialog button to MainActivity.java and rebuild the APK.")
                    },
                    label = { Text("Add Dialog & Build") },
                )
                AssistChip(
                    onClick = {
                        onSendPrompt("Refactor the active Java file cleanly, keep behavior identical, and verify with build_apk.")
                    },
                    label = { Text("Refactor & Build") },
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
                if (!hasEffectiveKey) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Connect Gemini API Key",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Add your Google AI Studio Gemini API key to use $GEMINI_MODEL_ID autonomously on your device, or set GEMINI_API_KEY in AI Studio / GitHub Secrets.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Button(
                                    onClick = { showApiKeyDialog = true },
                                    modifier = Modifier.testTag("open_api_key_dialog_button"),
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Set API Key")
                                }
                            }
                        }
                    }
                } else if (messages.isEmpty()) {
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
                                    text = "Autonomous Coding Agent ($GEMINI_MODEL_ID)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Gemini reads, creates, edits, and deletes any project file automatically without asking permission, compiles the APK to check its work, and shows every step live.",
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
                    placeholder = { Text("Tell Gemini what to build or change...") },
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gemini_prompt_input"),
                )
                val isBusy = aiState is AiUiState.Streaming
                if (isBusy) {
                    IconButton(
                        onClick = onStopGemini,
                        modifier = Modifier.testTag("gemini_stop_button"),
                    ) {
                        Icon(
                            Icons.Default.StopCircle,
                            contentDescription = "Stop Gemini agent",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (!hasEffectiveKey) {
                                showApiKeyDialog = true
                                return@IconButton
                            }
                            val p = promptInput.trim()
                            if (p.isNotEmpty()) {
                                promptInput = ""
                                onSendPrompt(p)
                            }
                        },
                        enabled = promptInput.isNotBlank(),
                        modifier = Modifier.testTag("gemini_send_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt to Gemini")
                    }
                }
            }
        }
    }

    if (showApiKeyDialog) {
        GeminiApiKeyDialog(
            currentCustomKey = customApiKey,
            hasBuiltInKey = GeminiCodeAssistant.hasBuiltInApiKey(),
            onSave = { newKey ->
                showApiKeyDialog = false
                onSaveApiKey(newKey)
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

@Composable
private fun GeminiApiKeyDialog(
    currentCustomKey: String,
    hasBuiltInKey: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyInput by remember(currentCustomKey) { mutableStateOf(currentCustomKey) }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Model: $GEMINI_MODEL_ID",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        currentCustomKey.isNotBlank() ->
                            "Using your custom API key saved securely on this device."
                        hasBuiltInKey ->
                            "Using the GEMINI_API_KEY configured at build time. Paste a key below to override it."
                        else ->
                            "Paste your Google AI Studio Gemini API key below. It stays saved locally on this device."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("AIza...") },
                    singleLine = true,
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                imageVector = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealKey) "Hide API key" else "Show API key",
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_api_key_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(keyInput) },
                modifier = Modifier.testTag("save_gemini_api_key_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (currentCustomKey.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
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

    var stepsExpanded by remember(message.id) { mutableStateOf(true) }

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
                if (message.steps.isNotEmpty()) {
                    AgentStepsCard(
                        steps = message.steps,
                        isRunning = message.isStreaming,
                        expanded = stepsExpanded,
                        onToggleExpanded = { stepsExpanded = !stepsExpanded },
                    )
                }

                if (message.text.isEmpty() && message.isStreaming) {
                    if (message.steps.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Working...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (message.text.isNotEmpty()) {
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

@Composable
private fun AgentStepsCard(
    steps: List<AgentStep>,
    isRunning: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val activeStep = steps.lastOrNull { it.status == AgentStepStatus.RUNNING }
    val failedCount = steps.count { it.status == AgentStepStatus.FAILED }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_steps_card"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isRunning && activeStep != null -> {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    failedCount > 0 -> {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    else -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = if (isRunning && activeStep != null) {
                        activeStep.title
                    } else {
                        "Agent Steps (${steps.size})"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse steps" else "Expand steps",
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    steps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier.padding(top = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (step.status) {
                                    AgentStepStatus.RUNNING -> CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    AgentStepStatus.COMPLETED -> Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Completed",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    AgentStepStatus.FAILED -> Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = "Failed",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (step.detail.isNotBlank()) {
                                    Text(
                                        text = step.detail,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (step.status == AgentStepStatus.FAILED) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
