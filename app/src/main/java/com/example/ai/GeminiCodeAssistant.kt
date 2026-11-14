package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.model.BuildDiagnostic
import com.example.model.ProjectFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

private const val TAG = "GeminiCodeAssistant"
const val GEMINI_MODEL_ID = "gemini-4-hydrogen"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

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

internal class GeminiApiException(
    val httpCode: Int,
    message: String,
) : Exception(message)

class GeminiCodeAssistant {

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
        apiKey: String,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
    ) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val resolvedKey = resolveApiKey(apiKey)
        if (resolvedKey.isEmpty()) {
            _uiState.value = AiUiState.Error(
                "Gemini API key is not configured. Tap the Key button above to enter your API key, or set GEMINI_API_KEY in AI Studio Secrets / GitHub Secrets.",
            )
            return
        }

        val priorHistory = _messages.value
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .takeLast(8)

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

        val requestJson = buildRequestPayload(
            history = priorHistory,
            latestPromptWithContext = promptWithContext,
        )

        val accumulated = StringBuilder()
        var blockReason: String? = null
        var finishReason: String? = null

        try {
            withTimeout(90_000L) {
                streamGenerateContent(
                    apiKey = resolvedKey,
                    requestBody = requestJson,
                    onChunk = { chunk ->
                        if (chunk.text.isNotEmpty()) {
                            accumulated.append(chunk.text)
                            val partial = accumulated.toString()
                            updateAssistantMessage(
                                id = assistantMsgId,
                                text = partial,
                                isStreaming = true,
                                codeBlocks = emptyList(),
                            )
                        }
                        if (!chunk.blockReason.isNullOrBlank()) {
                            blockReason = chunk.blockReason
                        }
                        if (!chunk.finishReason.isNullOrBlank()) {
                            finishReason = chunk.finishReason
                        }
                    },
                )
            }

            val finalText = accumulated.toString().trim()
            if (finalText.isEmpty()) {
                removeMessage(assistantMsgId)
                val reasonMsg = when {
                    !blockReason.isNullOrBlank() -> "Response blocked by Gemini ($blockReason)."
                    !finishReason.isNullOrBlank() && finishReason != "STOP" ->
                        "Gemini returned no text (finishReason: $finishReason)."
                    else -> "Gemini returned an empty response."
                }
                _uiState.value = AiUiState.Error(reasonMsg)
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
            _uiState.value = AiUiState.Error("Request timed out after 90 seconds. Check your connection and retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            Log.e(TAG, "Gemini API HTTP ${e.httpCode}: ${e.message}", e)
            removeMessageIfBlank(assistantMsgId)
            val friendly = when (e.httpCode) {
                400, 401, 403 -> "Invalid or unauthorized Gemini API key (${e.httpCode}): ${e.message}"
                404 -> "Model '$GEMINI_MODEL_ID' was not found for this API key (404): ${e.message}"
                429 -> "Gemini API rate limit or quota exceeded (429): ${e.message}"
                else -> "Gemini API error (${e.httpCode}): ${e.message}"
            }
            _uiState.value = AiUiState.Error(friendly)
        } catch (e: UnknownHostException) {
            Log.e(TAG, "No network connection for Gemini", e)
            removeMessageIfBlank(assistantMsgId)
            _uiState.value = AiUiState.Error("Unable to reach generativelanguage.googleapis.com. Check your internet connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation failed", e)
            removeMessageIfBlank(assistantMsgId)
            _uiState.value = AiUiState.Error(
                e.localizedMessage?.ifBlank { null } ?: "Failed to reach Gemini API.",
            )
        }
    }

    private suspend fun streamGenerateContent(
        apiKey: String,
        requestBody: String,
        onChunk: (SseParseResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val endpoint = "$GEMINI_API_BASE/$GEMINI_MODEL_ID:streamGenerateContent?alt=sse"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 75_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val errStream = connection.errorStream ?: connection.inputStream
                val rawError = errStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val parsedMsg = extractApiErrorMessage(rawError).ifBlank {
                    connection.responseMessage.orEmpty().ifBlank { "HTTP $status" }
                }
                throw GeminiApiException(status, parsedMsg)
            }

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(line.removePrefix("data:").trimStart())
                        }
                        line.isBlank() -> {
                            if (dataBuffer.isNotEmpty()) {
                                val payload = dataBuffer.toString().trim()
                                dataBuffer.clear()
                                if (payload.isNotEmpty() && payload != "[DONE]") {
                                    val parsed = parseSsePayload(payload)
                                    if (parsed.errorMessage != null) {
                                        throw GeminiApiException(status, parsed.errorMessage)
                                    }
                                    onChunk(parsed)
                                }
                            }
                        }
                    }
                }
                if (dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        val parsed = parseSsePayload(payload)
                        if (parsed.errorMessage != null) {
                            throw GeminiApiException(status, parsed.errorMessage)
                        }
                        onChunk(parsed)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestPayload(
        history: List<ChatMessage>,
        latestPromptWithContext: String,
    ): String {
        val systemInstructionText = """
            You are Gemini ($GEMINI_MODEL_ID) inside DroidForge Studio, an offline-capable Android Java IDE and APK builder.
            Constraints of the user's offline compiler toolchain:
            - Compiles Java 8 (.java) against standard Android SDK classes (android.app.Activity, android.os.Bundle, android.view.*, android.widget.*, android.graphics.*, android.content.*, android.util.*, android.webkit.*, java.*).
            - Compiles AndroidManifest.xml and res/values/strings.xml + res/values/colors.xml into R.java and resources.arsc.
            - Do NOT use AndroidX, Jetpack Compose, Kotlin syntax, or Gradle dependencies. Use classic Android SDK APIs (e.g., Activity, LinearLayout, TextView, Button, new View.OnClickListener()).
            - When providing full file replacements or new files, include the relative path as a comment on the first line inside the fenced code block (e.g., // file: src/com/example/app/MainActivity.java or <!-- file: res/values/strings.xml -->) so the user can apply it with one tap.
            - Keep explanations crisp, technical, and concise like Android Studio's Gemini assistant.
        """.trimIndent()

        val contentsArray = JSONArray()
        history.forEach { msg ->
            contentsArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "model")
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                },
            )
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", latestPromptWithContext)))
            },
        )

        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstructionText)),
                ),
            )
            put("contents", contentsArray)
            put(
                "generationConfig",
                JSONObject().put("temperature", 0.4),
            )
        }.toString()
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

    internal data class SseParseResult(
        val text: String = "",
        val blockReason: String? = null,
        val finishReason: String? = null,
        val errorMessage: String? = null,
    )

    internal companion object {
        fun resolveApiKey(customApiKey: String): String {
            val custom = customApiKey.trim()
            if (custom.isNotEmpty()) return custom
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            if (builtIn.isNotEmpty() && !builtIn.equals("GEMINI_API_KEY_DEFAULT_VALUE", ignoreCase = true)) {
                return builtIn
            }
            return ""
        }

        fun hasBuiltInApiKey(): Boolean {
            val builtIn = BuildConfig.GEMINI_API_KEY.trim()
            return builtIn.isNotEmpty() && !builtIn.equals("GEMINI_API_KEY_DEFAULT_VALUE", ignoreCase = true)
        }

        internal fun extractApiErrorMessage(rawBody: String): String {
            return runCatching {
                val obj = JSONObject(rawBody)
                obj.optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("").ifBlank { rawBody.take(240) }
        }

        internal fun parseSsePayload(jsonPayload: String): SseParseResult {
            return runCatching {
                val root = JSONObject(jsonPayload)
                val err = root.optJSONObject("error")
                if (err != null) {
                    return SseParseResult(errorMessage = err.optString("message", "Unknown API error"))
                }

                val promptFeedback = root.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason")?.takeIf { it.isNotBlank() }

                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return SseParseResult(blockReason = blockReason)
                }

                val firstCandidate = candidates.optJSONObject(0)
                val finishReason = firstCandidate?.optString("finishReason")?.takeIf { it.isNotBlank() }
                val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
                val textBuilder = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        val partText = part.optString("text", "")
                        if (partText.isNotEmpty()) {
                            textBuilder.append(partText)
                        }
                    }
                }
                SseParseResult(
                    text = textBuilder.toString(),
                    blockReason = blockReason,
                    finishReason = finishReason,
                )
            }.getOrElse {
                SseParseResult()
            }
        }

        internal fun extractCodeBlocks(markdown: String): List<SuggestedCodeBlock> {
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
}
