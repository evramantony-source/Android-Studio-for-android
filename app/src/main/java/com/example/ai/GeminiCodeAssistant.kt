package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.model.BuildDiagnostic
import com.example.model.ProjectFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

private const val TAG = "GeminiCodeAssistant"
const val GEMINI_MODEL_ID = "gemini-4-hydrogen"
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val MAX_AGENT_TURNS = 12
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 180_000
private const val TURN_TIMEOUT_MS = 180_000L

data class SuggestedCodeBlock(
    val language: String,
    val suggestedPath: String?,
    val code: String,
)

enum class AgentStepStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class AgentStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: AgentStepStatus = AgentStepStatus.RUNNING,
)

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val steps: List<AgentStep> = emptyList(),
    val codeBlocks: List<SuggestedCodeBlock> = emptyList(),
)

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Streaming : AiUiState
    data class Error(
        val message: String,
        val isRetryable: Boolean = true,
        val isModelOverloaded: Boolean = false,
    ) : AiUiState
}

data class AgentToolResult(
    val success: Boolean,
    val stepDetail: String,
    val payload: JSONObject,
)

interface ProjectToolExecutor {
    suspend fun listFiles(): AgentToolResult
    suspend fun readFile(path: String): AgentToolResult
    suspend fun writeFile(path: String, content: String): AgentToolResult
    suspend fun editFile(path: String, oldText: String, newText: String): AgentToolResult
    suspend fun deleteFile(path: String): AgentToolResult
    suspend fun searchFiles(query: String): AgentToolResult
    suspend fun buildApk(): AgentToolResult
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

    fun popLastPromptForRetry(): String? {
        val current = _messages.value
        val lastUserIdx = current.indexOfLast { it.isUser }
        if (lastUserIdx < 0) return null
        val prompt = current[lastUserIdx].text
        _messages.value = current.take(lastUserIdx)
        _uiState.value = AiUiState.Idle
        return prompt
    }

    fun markCancelled() {
        _messages.value = _messages.value.map { msg ->
            if (!msg.isStreaming) return@map msg
            val updatedSteps = msg.steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING) {
                    step.copy(status = AgentStepStatus.FAILED, detail = "Stopped by user")
                } else {
                    step
                }
            }
            msg.copy(
                isStreaming = false,
                steps = updatedSteps,
                text = msg.text.ifBlank { "Stopped." },
            )
        }
        _uiState.value = AiUiState.Idle
    }

    suspend fun askGemini(
        userPrompt: String,
        apiKey: String,
        modelId: String = GEMINI_MODEL_ID,
        projectName: String,
        packageName: String,
        activeFile: ProjectFile?,
        allFiles: List<ProjectFile>,
        diagnostics: List<BuildDiagnostic>,
        toolExecutor: ProjectToolExecutor,
    ) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val activeModel = modelId.trim().removePrefix("models/").ifBlank { GEMINI_MODEL_ID }

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
        val steps = mutableListOf<AgentStep>()
        val assistantPlaceholder = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true,
            steps = emptyList(),
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

        val conversationContents = buildInitialContents(
            history = priorHistory,
            latestPromptWithContext = promptWithContext,
        )

        val accumulatedVisibleText = StringBuilder()

        fun addOrUpdateStep(step: AgentStep) {
            val idx = steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) {
                steps[idx] = step
            } else {
                steps.add(step)
            }
            updateAssistantMessage(
                id = assistantMsgId,
                text = accumulatedVisibleText.toString(),
                isStreaming = true,
                steps = steps.toList(),
                codeBlocks = emptyList(),
            )
        }

        try {
            var completed = false
            for (turn in 0 until MAX_AGENT_TURNS) {
                coroutineContext.ensureActive()

                val thinkStepId = "think_$turn"
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = if (turn == 0) "Sending request to $activeModel" else "Analyzing tool results",
                        detail = "Waiting for model...",
                        status = AgentStepStatus.RUNNING,
                    ),
                )

                val requestJson = buildRequestPayload(
                    contents = conversationContents,
                    modelId = activeModel,
                )
                val turnPrefixLength = accumulatedVisibleText.length
                if (accumulatedVisibleText.isNotEmpty() && !accumulatedVisibleText.endsWith("\n")) {
                    accumulatedVisibleText.append("\n\n")
                }
                val textBeforeTurn = accumulatedVisibleText.toString()
                val turnVisibleText = StringBuilder()

                val turnOutcome = withTimeout(TURN_TIMEOUT_MS) {
                    callModelWithRetryAndFallback(
                        modelId = activeModel,
                        apiKey = resolvedKey,
                        requestBody = requestJson,
                        onConnected = {
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = if (turn == 0) "Streaming from $activeModel" else "Analyzing tool results",
                                    detail = "Connected, receiving response...",
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                        },
                        onRetryStatus = { detail ->
                            addOrUpdateStep(
                                AgentStep(
                                    id = thinkStepId,
                                    title = "Retrying $activeModel",
                                    detail = detail,
                                    status = AgentStepStatus.RUNNING,
                                ),
                            )
                        },
                        onTextDelta = { delta ->
                            turnVisibleText.append(delta)
                            accumulatedVisibleText.clear()
                            accumulatedVisibleText.append(textBeforeTurn)
                            accumulatedVisibleText.append(turnVisibleText)
                            updateAssistantMessage(
                                id = assistantMsgId,
                                text = accumulatedVisibleText.toString(),
                                isStreaming = true,
                                steps = steps.toList(),
                                codeBlocks = emptyList(),
                            )
                        },
                    )
                }

                // Roll back trailing blank spacing if this turn produced no visible text
                if (turnVisibleText.isEmpty()) {
                    accumulatedVisibleText.setLength(turnPrefixLength)
                }

                val functionCalls = turnOutcome.functionCalls
                if (functionCalls.isEmpty()) {
                    val toolActionCount = steps.count { it.status == AgentStepStatus.COMPLETED && !it.id.startsWith("think_") }
                    addOrUpdateStep(
                        AgentStep(
                            id = thinkStepId,
                            title = if (turn == 0) "Responded via $activeModel" else "Completed task",
                            detail = if (toolActionCount > 0) "$toolActionCount action(s) executed" else "Done",
                            status = AgentStepStatus.COMPLETED,
                        ),
                    )

                    val finalText = accumulatedVisibleText.toString().trim()
                    if (finalText.isEmpty() && steps.none { !it.id.startsWith("think_") }) {
                        val reasonMsg = when {
                            !turnOutcome.blockReason.isNullOrBlank() ->
                                "Response blocked by Gemini (${turnOutcome.blockReason})."
                            !turnOutcome.finishReason.isNullOrBlank() && turnOutcome.finishReason != "STOP" ->
                                "Gemini returned no text (finishReason: ${turnOutcome.finishReason})."
                            else -> "Gemini returned an empty response."
                        }
                        finalizeFailedSteps(assistantMsgId, steps, "", reasonMsg)
                        _uiState.value = AiUiState.Error(reasonMsg)
                    } else {
                        val displayText = finalText.ifBlank { "All requested actions finished." }
                        updateAssistantMessage(
                            id = assistantMsgId,
                            text = displayText,
                            isStreaming = false,
                            steps = steps.toList(),
                            codeBlocks = extractCodeBlocks(displayText),
                        )
                        _uiState.value = AiUiState.Idle
                    }
                    completed = true
                    break
                }

                // Model requested one or more autonomous tool calls
                addOrUpdateStep(
                    AgentStep(
                        id = thinkStepId,
                        title = if (turn == 0) "Planned ${functionCalls.size} action(s)" else "Next: ${functionCalls.size} action(s)",
                        detail = functionCalls.joinToString(", ") { it.name },
                        status = AgentStepStatus.COMPLETED,
                    ),
                )

                // Preserve exact model parts (including thoughtSignature) in conversation history
                conversationContents.put(
                    JSONObject().apply {
                        put("role", "model")
                        put("parts", turnOutcome.modelParts)
                    },
                )

                val functionResponseParts = JSONArray()
                for ((callIdx, call) in functionCalls.withIndex()) {
                    coroutineContext.ensureActive()
                    val stepId = "tool_${turn}_$callIdx"
                    val stepTitle = describeToolCall(call)

                    addOrUpdateStep(
                        AgentStep(
                            id = stepId,
                            title = stepTitle,
                            detail = "Running...",
                            status = AgentStepStatus.RUNNING,
                        ),
                    )

                    val toolResult = executeToolCall(call, toolExecutor)

                    addOrUpdateStep(
                        AgentStep(
                            id = stepId,
                            title = stepTitle,
                            detail = toolResult.stepDetail,
                            status = if (toolResult.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                        ),
                    )

                    functionResponseParts.put(
                        JSONObject().apply {
                            put(
                                "functionResponse",
                                JSONObject().apply {
                                    put("name", call.name)
                                    put("response", toolResult.payload)
                                },
                            )
                        },
                    )
                }

                conversationContents.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", functionResponseParts)
                    },
                )
            }

            if (!completed) {
                val summary = accumulatedVisibleText.toString().trim()
                    .ifBlank { "Stopped after reaching the maximum autonomous step limit ($MAX_AGENT_TURNS turns)." }
                updateAssistantMessage(
                    id = assistantMsgId,
                    text = summary,
                    isStreaming = false,
                    steps = steps.toList(),
                    codeBlocks = extractCodeBlocks(summary),
                )
                _uiState.value = AiUiState.Idle
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini request timed out", e)
            val msg = "Timed out waiting for $activeModel after ${TURN_TIMEOUT_MS / 1000}s."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), msg)
            _uiState.value = AiUiState.Error(msg)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Socket timeout talking to Gemini ($activeModel)", e)
            val msg = "Network timeout reaching $activeModel (${e.message ?: "socket timeout"}). Check your internet connection or tap the Key icon to switch/verify the model."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "Socket timeout")
            _uiState.value = AiUiState.Error(msg)
        } catch (e: CancellationException) {
            markCancelled()
            throw e
        } catch (e: GeminiApiException) {
            Log.e(TAG, "Gemini API HTTP ${e.httpCode}: ${e.message}", e)
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "API error ${e.httpCode}")
            val overloaded = e.httpCode == 503 || e.httpCode == 429
            val friendly = when (e.httpCode) {
                400, 401, 403 -> "Gemini API error (${e.httpCode}): ${e.message}"
                404 -> "Model '$activeModel' was not found for this API key (404): ${e.message}. Tap Switch Model to pick another."
                429 -> "Gemini API rate limit or quota exceeded (429): ${e.message}"
                503 -> "$activeModel is temporarily overloaded (503): ${e.message}"
                else -> "Gemini API error (${e.httpCode}): ${e.message}"
            }
            _uiState.value = AiUiState.Error(
                message = friendly,
                isRetryable = e.httpCode !in listOf(400, 401, 403),
                isModelOverloaded = overloaded || e.httpCode == 404,
            )
        } catch (e: UnknownHostException) {
            Log.e(TAG, "No network connection for Gemini", e)
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), "Offline")
            _uiState.value = AiUiState.Error("Unable to reach generativelanguage.googleapis.com. Check your internet connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation failed", e)
            val msg = e.localizedMessage?.ifBlank { null } ?: "Failed to reach Gemini API."
            finalizeFailedSteps(assistantMsgId, steps, accumulatedVisibleText.toString(), msg)
            _uiState.value = AiUiState.Error(msg)
        }
    }

    private suspend fun callModelWithRetryAndFallback(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onRetryStatus: (String) -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        val backoffMs = longArrayOf(1_500L, 3_000L, 5_000L)
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return callModelWithSseFallback(
                    modelId = modelId,
                    apiKey = apiKey,
                    requestBody = requestBody,
                    onConnected = onConnected,
                    onFallback = {
                        onRetryStatus("SSE timed out, trying standard request...")
                    },
                    onTextDelta = onTextDelta,
                )
            } catch (e: GeminiApiException) {
                if (!isTransientHttpStatus(e.httpCode) || attempt >= backoffMs.size) {
                    throw e
                }
                val waitMs = backoffMs[attempt]
                attempt++
                onRetryStatus("High demand (${e.httpCode}) — retrying in ${waitMs / 1000.0}s (${attempt}/${backoffMs.size})...")
                delay(waitMs)
            }
        }
    }

    private suspend fun callModelWithSseFallback(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onFallback: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome {
        var receivedAnyBytes = false
        return try {
            streamGenerateContent(
                modelId = modelId,
                apiKey = apiKey,
                requestBody = requestBody,
                onConnected = {
                    receivedAnyBytes = true
                    onConnected()
                },
                onTextDelta = { delta ->
                    receivedAnyBytes = true
                    onTextDelta(delta)
                },
            )
        } catch (e: SocketTimeoutException) {
            if (receivedAnyBytes) throw e
            Log.w(TAG, "SSE timed out before receiving response; retrying non-streaming generateContent", e)
            onFallback()
            generateContentUnary(
                modelId = modelId,
                apiKey = apiKey,
                requestBody = requestBody,
                onText = onTextDelta,
            )
        }
    }

    private fun finalizeFailedSteps(
        assistantMsgId: String,
        steps: MutableList<AgentStep>,
        currentText: String,
        failureDetail: String,
    ) {
        val updated = steps.map { step ->
            if (step.status == AgentStepStatus.RUNNING) {
                step.copy(status = AgentStepStatus.FAILED, detail = failureDetail)
            } else {
                step
            }
        }
        updateAssistantMessage(
            id = assistantMsgId,
            text = currentText,
            isStreaming = false,
            steps = updated,
            codeBlocks = extractCodeBlocks(currentText),
        )
    }

    private suspend fun executeToolCall(
        call: ParsedFunctionCall,
        executor: ProjectToolExecutor,
    ): AgentToolResult {
        return try {
            when (call.name) {
                "list_files" -> executor.listFiles()
                "read_file" -> executor.readFile(call.args.optString("path"))
                "write_file" -> executor.writeFile(
                    path = call.args.optString("path"),
                    content = call.args.optString("content"),
                )
                "edit_file" -> executor.editFile(
                    path = call.args.optString("path"),
                    oldText = call.args.optString("old_text"),
                    newText = call.args.optString("new_text"),
                )
                "delete_file" -> executor.deleteFile(call.args.optString("path"))
                "search_files" -> executor.searchFiles(call.args.optString("query"))
                "build_apk" -> executor.buildApk()
                else -> AgentToolResult(
                    success = false,
                    stepDetail = "Unknown tool '${call.name}'",
                    payload = JSONObject().put("error", "Unknown tool '${call.name}'"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentToolResult(
                success = false,
                stepDetail = e.localizedMessage ?: "Tool execution failed",
                payload = JSONObject().put("error", e.localizedMessage ?: "Tool execution failed"),
            )
        }
    }

    private fun describeToolCall(call: ParsedFunctionCall): String {
        val path = call.args.optString("path").ifBlank { "file" }
        return when (call.name) {
            "list_files" -> "Listing project files"
            "read_file" -> "Reading $path"
            "write_file" -> "Writing $path"
            "edit_file" -> "Editing $path"
            "delete_file" -> "Deleting $path"
            "search_files" -> "Searching '${call.args.optString("query").take(28)}'"
            "build_apk" -> "Building APK (ECJ + DX + ApkSigner)"
            else -> "Running ${call.name}"
        }
    }

    private suspend fun streamGenerateContent(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onConnected: () -> Unit,
        onTextDelta: (String) -> Unit,
    ): TurnStreamOutcome = withContext(Dispatchers.IO) {
        val endpoint = "$GEMINI_API_BASE/$modelId:streamGenerateContent?alt=sse"
        val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                Thread { runCatching { connection.disconnect() } }.start()
            }
        }

        val assembledParts = mutableListOf<JSONObject>()
        val functionCalls = mutableListOf<ParsedFunctionCall>()
        var blockReason: String? = null
        var finishReason: String? = null

        fun handlePayload(payload: String, status: Int) {
            val parsed = parseSsePayload(payload)
            if (parsed.errorMessage != null) {
                throw GeminiApiException(status, parsed.errorMessage)
            }
            if (!parsed.blockReason.isNullOrBlank()) blockReason = parsed.blockReason
            if (!parsed.finishReason.isNullOrBlank()) finishReason = parsed.finishReason

            if (parsed.text.isNotEmpty()) {
                onTextDelta(parsed.text)
            }

            parsed.rawParts.forEach { rawPart ->
                appendRawPart(assembledParts, rawPart)
                val fc = rawPart.optJSONObject("functionCall")
                if (fc != null) {
                    val fnName = fc.optString("name")
                    if (fnName.isNotBlank()) {
                        functionCalls.add(
                            ParsedFunctionCall(
                                name = fnName,
                                args = fc.optJSONObject("args") ?: JSONObject(),
                            ),
                        )
                    }
                }
            }
        }

        try {
            connection.outputStream.use { os ->
                os.write(bodyBytes)
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

            onConnected()

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
                                    handlePayload(payload, status)
                                }
                            }
                        }
                    }
                }
                if (dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        handlePayload(payload, status)
                    }
                }
            }

            TurnStreamOutcome(
                modelParts = JSONArray().apply { assembledParts.forEach { put(it) } },
                functionCalls = functionCalls,
                blockReason = blockReason,
                finishReason = finishReason,
            )
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    private suspend fun generateContentUnary(
        modelId: String,
        apiKey: String,
        requestBody: String,
        onText: (String) -> Unit,
    ): TurnStreamOutcome = withContext(Dispatchers.IO) {
        val endpoint = "$GEMINI_API_BASE/$modelId:generateContent"
        val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                Thread { runCatching { connection.disconnect() } }.start()
            }
        }

        try {
            connection.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
            val rawResponse = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val parsedMsg = extractApiErrorMessage(rawResponse).ifBlank {
                    connection.responseMessage.orEmpty().ifBlank { "HTTP $status" }
                }
                throw GeminiApiException(status, parsedMsg)
            }

            val parsed = parseSsePayload(rawResponse)
            if (parsed.errorMessage != null) {
                throw GeminiApiException(status, parsed.errorMessage)
            }
            if (parsed.text.isNotEmpty()) {
                onText(parsed.text)
            }

            val assembledParts = mutableListOf<JSONObject>()
            val functionCalls = mutableListOf<ParsedFunctionCall>()
            parsed.rawParts.forEach { rawPart ->
                appendRawPart(assembledParts, rawPart)
                val fc = rawPart.optJSONObject("functionCall")
                if (fc != null) {
                    val fnName = fc.optString("name")
                    if (fnName.isNotBlank()) {
                        functionCalls.add(
                            ParsedFunctionCall(
                                name = fnName,
                                args = fc.optJSONObject("args") ?: JSONObject(),
                            ),
                        )
                    }
                }
            }

            TurnStreamOutcome(
                modelParts = JSONArray().apply { assembledParts.forEach { put(it) } },
                functionCalls = functionCalls,
                blockReason = parsed.blockReason,
                finishReason = parsed.finishReason,
            )
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    internal fun buildInitialContents(
        history: List<ChatMessage>,
        latestPromptWithContext: String,
    ): JSONArray {
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
        return contentsArray
    }

    internal fun buildRequestPayload(
        contents: JSONArray,
        modelId: String = GEMINI_MODEL_ID,
    ): String {
        val systemInstructionText = """
            You are an autonomous coding agent powered by $modelId inside DroidForge Studio, an offline-capable Android Java IDE and APK builder.

            Execution rules:
            - Act autonomously WITHOUT asking the user for permission before reading, creating, editing, or deleting files, or building the APK.
            - You have full read and write access to EVERY file in the active project via your tools: list_files, read_file, write_file, edit_file, delete_file, search_files, and build_apk.
            - Always inspect existing files with read_file / list_files before modifying unfamiliar code.
            - Prefer edit_file for targeted changes and write_file for creating new files or full rewrites. Changes are immediately saved and opened in the user's editor.
            - After making code or XML changes, call build_apk to verify compilation (ECJ + DEX + ApkSigner). If build_apk reports compiler errors, fix them immediately and re-run build_apk until it succeeds.
            - Do NOT use AndroidX, Jetpack Compose, Kotlin syntax, or external Gradle dependencies. Use classic Android SDK APIs (android.app.Activity, android.os.Bundle, android.view.*, android.widget.*, android.graphics.*, android.content.*, android.webkit.*, java.*).
            - Compiles Java 8 (.java), AndroidManifest.xml, res/values/strings.xml, and res/values/colors.xml into R.java and resources.arsc.
            - In your final summary to the user, briefly state what files you changed and whether the APK build passed.
        """.trimIndent()

        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstructionText)),
                ),
            )
            put("contents", contents)
            put("tools", buildToolDeclarations())
            put(
                "generationConfig",
                JSONObject().put("temperature", 0.3),
            )
        }.toString()
    }

    private fun buildToolDeclarations(): JSONArray {
        fun stringProp(desc: String) = JSONObject().apply {
            put("type", "STRING")
            put("description", desc)
        }

        val declarations = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "list_files")
                    put("description", "Lists all file paths in the active Android project.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject())
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "read_file")
                    put("description", "Reads the complete text content of any file in the active project.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().put(
                                    "path",
                                    stringProp("Relative file path inside the project, e.g. src/com/example/counter/MainActivity.java, AndroidManifest.xml, res/values/strings.xml"),
                                ),
                            )
                            put("required", JSONArray().put("path"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "write_file")
                    put("description", "Creates a new file or completely overwrites an existing file in the project immediately without confirmation.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject()
                                    .put("path", stringProp("Relative file path to create or overwrite."))
                                    .put("content", stringProp("Complete file text content to write.")),
                            )
                            put("required", JSONArray().put("path").put("content"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "edit_file")
                    put("description", "Replaces an exact text snippet inside an existing project file immediately without confirmation.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject()
                                    .put("path", stringProp("Relative file path to edit."))
                                    .put("old_text", stringProp("Exact existing text snippet to find and replace."))
                                    .put("new_text", stringProp("Replacement text snippet.")),
                            )
                            put("required", JSONArray().put("path").put("old_text").put("new_text"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "delete_file")
                    put("description", "Deletes a file from the active project immediately without confirmation.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().put("path", stringProp("Relative file path to delete.")))
                            put("required", JSONArray().put("path"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "search_files")
                    put("description", "Searches for text across all files in the active project and returns matching lines.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().put("query", stringProp("Case-insensitive text query to search for.")))
                            put("required", JSONArray().put("query"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("name", "build_apk")
                    put("description", "Compiles the active project into a signed APK using the on-device toolchain and returns compiler diagnostics.")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject())
                        },
                    )
                },
            )
        }

        return JSONArray().put(
            JSONObject().put("functionDeclarations", declarations),
        )
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
            appendLine("Currently open editor tab: ${activeFile.relativePath}")
        }
        if (diagnostics.isNotEmpty()) {
            appendLine()
            appendLine("=== Latest Compiler Diagnostics ===")
            diagnostics.take(15).forEach { d ->
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
        steps: List<AgentStep>,
        codeBlocks: List<SuggestedCodeBlock>,
    ) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(
                    text = text,
                    isStreaming = isStreaming,
                    steps = steps,
                    codeBlocks = codeBlocks,
                )
            } else {
                msg
            }
        }
    }

    internal data class ParsedFunctionCall(
        val name: String,
        val args: JSONObject,
    )

    private data class TurnStreamOutcome(
        val modelParts: JSONArray,
        val functionCalls: List<ParsedFunctionCall>,
        val blockReason: String?,
        val finishReason: String?,
    )

    internal data class SseParseResult(
        val text: String = "",
        val rawParts: List<JSONObject> = emptyList(),
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

        internal fun isTransientHttpStatus(httpCode: Int): Boolean {
            return httpCode == 500 || httpCode == 502 || httpCode == 503 || httpCode == 504
        }

        internal fun appendRawPart(target: MutableList<JSONObject>, incoming: JSONObject) {
            val copy = JSONObject(incoming.toString())
            val hasFc = copy.has("functionCall")
            val hasSig = copy.has("thoughtSignature")
            val isThought = copy.optBoolean("thought", false)
            val hasText = copy.has("text")

            if (!hasFc && !hasSig && hasText && target.isNotEmpty()) {
                val last = target.last()
                val lastCanMerge = !last.has("functionCall") &&
                    !last.has("thoughtSignature") &&
                    last.has("text") &&
                    last.optBoolean("thought", false) == isThought
                if (lastCanMerge) {
                    last.put("text", last.optString("text", "") + copy.optString("text", ""))
                    return
                }
            }
            target.add(copy)
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
                val rawPartsList = mutableListOf<JSONObject>()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        rawPartsList.add(JSONObject(part.toString()))
                        if (part.optBoolean("thought", false)) continue
                        val partText = part.optString("text", "")
                        if (partText.isNotEmpty()) {
                            textBuilder.append(partText)
                        }
                    }
                }
                SseParseResult(
                    text = textBuilder.toString(),
                    rawParts = rawPartsList,
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
