package com.example.ai

import android.util.Log
import com.example.model.BuildDiagnostic
import com.example.model.ProjectFile
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiCodeAssistant"

data class SuggestedCodeBlock(
    val language: String,
    val suggestedPath: String?,
    val code: String,
)

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val codeBlocks: List<SuggestedCodeBlock> = emptyList(),
)

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Streaming : AiUiState
    data class Error(val message: String) : AiUiState
}

class GeminiCodeAssistant {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    """
                    You are Gemini inside DroidForge Studio, an offline-capable Android Java IDE and APK builder.
                    Constraints of the user's offline compiler toolchain:
                    - Compiles Java 8 (.java) against standard Android SDK classes (android.app.Activity, android.os.Bundle, android.view.*, android.widget.*, android.graphics.*, android.content.*, android.util.*, android.webkit.*, java.*).
                    - Compiles AndroidManifest.xml and res/values/strings.xml + res/values/colors.xml into R.java and resources.arsc.
                    - Do NOT use AndroidX, Jetpack Compose, Kotlin syntax, lambdas requiring external libraries, or Gradle dependencies. Use classic Android SDK APIs (e.g., Activity, LinearLayout, TextView, Button, new View.OnClickListener()).
                    - When providing full file replacements or new files, include the relative path as a comment on the first line inside the fenced code block or right above it (e.g., // file: src/com/example/app/MainActivity.java or <!-- file: res/values/strings.xml -->) so the user can apply it with one tap.
                    - Keep explanations crisp, technical, and concise like Android Studio's Gemini assistant.
                    """.trimIndent(),
                )
            },
        )
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    fun clearConversation() {
        _messages.value = emptyList()
        _uiState.value = AiUiState.Idle
    }

    suspend fun askGemini(
        userPrompt: String,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
    ) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val userMsg = ChatMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = trimmed,
        )
        val assistantMsgId = "a_${System.currentTimeMillis()}"
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true,
        )

        _messages.value = _messages.value + userMsg + assistantPlaceholder
        _uiState.value = AiUiState.Streaming

        val promptWithContext = buildPrompt(
            userPrompt = trimmed,
            projectName = projectName,
            packageName = packageName,
            activeFile = activeFile,
            allFiles = allFiles,
            diagnostics = diagnostics,
        )

        val accumulated = StringBuilder()
        try {
            withTimeout(60_000L) {
                model.generateContentStream(promptWithContext).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        val partial = accumulated.toString()
                        updateAssistantMessage(
                            id = assistantMsgId,
                            text = partial,
                            isStreaming = true,
                            codeBlocks = emptyList(),
                        )
                    }
                }
            }

            val finalText = accumulated.toString().trim()
            if (finalText.isEmpty()) {
                removeMessage(assistantMsgId)
                _uiState.value = AiUiState.Error("Gemini returned an empty response.")
            } else {
                updateAssistantMessage(
                    id = assistantMsgId,
                    text = finalText,
                    isStreaming = false,
                    codeBlocks = extractCodeBlocks(finalText),
                )
                _uiState.value = AiUiState.Idle
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini request timed out", e)
            removeMessageIfBlank(assistantMsgId)
            _uiState.value = AiUiState.Error("Request timed out after 60 seconds. Check your connection and retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation failed", e)
            removeMessageIfBlank(assistantMsgId)
            val rawMsg = e.localizedMessage.orEmpty()
            val friendlyMsg = if (rawMsg.contains("Default FirebaseApp is not initialized", ignoreCase = true)) {
                "Firebase is not configured in this APK (google-services.json was missing during the build). Install the APK exported from AI Studio, or add GOOGLE_SERVICES_JSON to your GitHub Actions Secrets."
            } else {
                rawMsg.ifBlank { "Failed to reach Gemini via Firebase AI Logic." }
            }
            _uiState.value = AiUiState.Error(friendlyMsg)
        }
    }

    private fun buildPrompt(
        userPrompt: String,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
    ): String = buildString {
        appendLine("Project: $projectName (package: $packageName)")
        appendLine("Files: ${allFiles.joinToString(", ") { it.relativePath }}")
        if (activeFile != null) {
            appendLine()
            appendLine("=== Active File: ${activeFile.relativePath} ===")
            appendLine(activeFile.content.take(8000))
            appendLine("=== End Active File ===")
        }
        if (diagnostics.isNotEmpty()) {
            appendLine()
            appendLine("=== Latest Compiler Diagnostics ===")
            diagnostics.take(10).forEach { d ->
                appendLine("${d.severity.name} ${d.filePath}:${d.line} -> ${d.message}")
            }
        }
        appendLine()
        appendLine("User request:")
        appendLine(userPrompt)
    }

    private fun updateAssistantMessage(
        id: String,
        text: String,
        isStreaming: Boolean,
        codeBlocks: List<SuggestedCodeBlock>,
    ) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(text = text, isStreaming = isStreaming, codeBlocks = codeBlocks)
            } else {
                msg
            }
        }
    }

    private fun removeMessage(id: String) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    private fun removeMessageIfBlank(id: String) {
        _messages.value = _messages.value.filterNot { it.id == id && it.text.isBlank() }
    }

    private fun extractCodeBlocks(markdown: String): List<SuggestedCodeBlock> {
        val regex = Regex("""```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```""")
        return regex.findAll(markdown).mapNotNull { match ->
            val lang = match.groupValues[1].lowercase().ifBlank { "java" }
            val rawCode = match.groupValues[2].trimEnd()
            if (rawCode.isBlank()) return@mapNotNull null

            val firstLine = rawCode.lineSequence().firstOrNull()?.trim().orEmpty()
            val pathHint = when {
                firstLine.startsWith("// file:") -> firstLine.removePrefix("// file:").trim()
                firstLine.startsWith("<!-- file:") -> firstLine.removePrefix("<!-- file:").removeSuffix("-->").trim()
                else -> null
            }
            val cleanedCode = if (pathHint != null) {
                rawCode.substringAfter('\n', rawCode).trimStart('\n')
            } else {
                rawCode
            }
            SuggestedCodeBlock(
                language = lang,
                suggestedPath = pathHint,
                code = cleanedCode,
            )
        }.toList()
    }
}
