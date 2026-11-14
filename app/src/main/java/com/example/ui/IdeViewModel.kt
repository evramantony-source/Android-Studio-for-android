package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AgentToolResult
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.GeminiCodeAssistant
import com.example.ai.ProjectToolExecutor
import com.example.ai.SuggestedCodeBlock
import com.example.compiler.OfflineApkBuilder
import com.example.data.ProjectRepository
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.EditorSettings
import com.example.model.ProjectFile
import com.example.model.ProjectMetadata
import com.example.model.ProjectTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IdeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val apkBuilder = OfflineApkBuilder(application)
    private val geminiAssistant = GeminiCodeAssistant()

    private val _projects = MutableStateFlow<List<ProjectMetadata>>(emptyList())
    val projects: StateFlow<List<ProjectMetadata>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<ProjectMetadata?>(null)
    val activeProject: StateFlow<ProjectMetadata?> = _activeProject.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
    val projectFiles: StateFlow<List<ProjectFile>> = _projectFiles.asStateFlow()

    private val _openTabs = MutableStateFlow<List<String>>(emptyList())
    val openTabs: StateFlow<List<String>> = _openTabs.asStateFlow()

    private val _activeFilePath = MutableStateFlow<String?>(null)
    val activeFilePath: StateFlow<String?> = _activeFilePath.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    private val _lastBuildResult = MutableStateFlow<BuildResult?>(null)
    val lastBuildResult: StateFlow<BuildResult?> = _lastBuildResult.asStateFlow()

    private val _jumpToLine = MutableStateFlow<Pair<String, Int>?>(null)
    val jumpToLine: StateFlow<Pair<String, Int>?> = _jumpToLine.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val editorSettings: StateFlow<EditorSettings> = repository.editorSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditorSettings(),
    )

    val customGeminiApiKey: StateFlow<String> = repository.customGeminiApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "",
    )

    val aiMessages: StateFlow<List<ChatMessage>> = geminiAssistant.messages
    val aiState: StateFlow<AiUiState> = geminiAssistant.uiState

    private var saveJob: Job? = null
    private var aiJob: Job? = null

    init {
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = repository.listProjects()
        }
    }

    fun createProject(name: String, packageName: String, template: ProjectTemplate) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { template.defaultAppName }
            val cleanPkg = sanitizePackageName(packageName.trim().ifBlank { template.defaultPackage })
            val meta = repository.createProject(cleanName, cleanPkg, template)
            _projects.value = repository.listProjects()
            openProject(meta)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            if (_activeProject.value?.id == projectId) {
                _activeProject.value = null
            }
            _projects.value = repository.listProjects()
        }
    }

    fun openProject(meta: ProjectMetadata) {
        viewModelScope.launch {
            val files = repository.loadProjectFiles(meta.id)
            _activeProject.value = meta
            _projectFiles.value = files
            val initialTabs = files.take(3).map { it.relativePath }
            _openTabs.value = initialTabs
            _activeFilePath.value = initialTabs.firstOrNull()
            _lastBuildResult.value = null
            _liveLogs.value = emptyList()
            geminiAssistant.clearConversation()
        }
    }

    fun closeProject() {
        saveAllImmediately()
        _activeProject.value = null
        _projectFiles.value = emptyList()
        _openTabs.value = emptyList()
        _activeFilePath.value = null
        _lastBuildResult.value = null
        refreshProjects()
    }

    fun openFile(relativePath: String) {
        if (relativePath !in _openTabs.value) {
            _openTabs.value = _openTabs.value + relativePath
        }
        _activeFilePath.value = relativePath
    }

    fun closeTab(relativePath: String) {
        val nextTabs = _openTabs.value.filterNot { it == relativePath }
        _openTabs.value = nextTabs
        if (_activeFilePath.value == relativePath) {
            _activeFilePath.value = nextTabs.lastOrNull()
        }
    }

    fun createNewFile(relativePath: String) {
        val project = _activeProject.value ?: return
        val clean = relativePath.trim().trimStart('/')
        if (clean.isEmpty()) return
        if (_projectFiles.value.any { it.relativePath == clean }) {
            openFile(clean)
            return
        }
        val starter = buildStarterContentForNewFile(clean, project.packageName)
        viewModelScope.launch {
            repository.saveFile(project.id, clean, starter)
            val updated = repository.loadProjectFiles(project.id)
            _projectFiles.value = updated
            openFile(clean)
        }
    }

    fun deleteFile(relativePath: String) {
        val project = _activeProject.value ?: return
        viewModelScope.launch {
            repository.deleteFile(project.id, relativePath)
            closeTab(relativePath)
            _projectFiles.value = repository.loadProjectFiles(project.id)
        }
    }

    fun updateActiveFileContent(newContent: String) {
        val project = _activeProject.value ?: return
        val path = _activeFilePath.value ?: return
        _projectFiles.value = _projectFiles.value.map { file ->
            if (file.relativePath == path) file.copy(content = newContent) else file
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(250L)
            repository.saveFile(project.id, path, newContent)
        }
    }

    private fun saveAllImmediately() {
        val project = _activeProject.value ?: return
        val files = _projectFiles.value
        viewModelScope.launch {
            files.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }
        }
    }

    fun buildApk() {
        val project = _activeProject.value ?: return
        if (_isBuilding.value) return
        viewModelScope.launch {
            _isBuilding.value = true
            _liveLogs.value = listOf("Starting offline APK build for ${project.name}...")
            _lastBuildResult.value = null

            // Flush any pending unsaved edits to disk before compiling
            _projectFiles.value.forEach { file ->
                repository.saveFile(project.id, file.relativePath, file.content)
            }

            val result = apkBuilder.buildProject(
                projectDir = repository.getProjectDir(project.id),
                packageName = project.packageName,
                onProgress = { line ->
                    _liveLogs.value = _liveLogs.value + line
                },
            )
            _lastBuildResult.value = result
            _isBuilding.value = false
            repository.recordBuildResult(project.id, result.success, result.apkSizeBytes)
            _projects.value = repository.listProjects()
            _activeProject.value = _projects.value.find { it.id == project.id } ?: project
        }
    }

    fun jumpToDiagnostic(diagnostic: BuildDiagnostic) {
        val exists = _projectFiles.value.any { it.relativePath == diagnostic.filePath }
        if (exists) {
            openFile(diagnostic.filePath)
            _jumpToLine.value = diagnostic.filePath to diagnostic.line
        }
    }

    fun clearJumpRequest() {
        _jumpToLine.value = null
    }

    fun exportApkToUri(destinationUri: Uri) {
        val apkPath = _lastBuildResult.value?.apkPath ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(apkPath)
                    getApplication<Application>().contentResolver.openOutputStream(destinationUri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
            }
            _statusBanner.value = if (saved) {
                "APK exported successfully."
            } else {
                "Failed to export APK to selected destination."
            }
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun askGemini(prompt: String) {
        val project = _activeProject.value ?: return
        val active = _projectFiles.value.find { it.relativePath == _activeFilePath.value }
        val diagnostics = _lastBuildResult.value?.diagnostics.orEmpty()
        val apiKey = customGeminiApiKey.value
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            val executor = createToolExecutor(project.id, project.packageName)
            geminiAssistant.askGemini(
                userPrompt = prompt,
                apiKey = apiKey,
                projectName = project.name,
                packageName = project.packageName,
                activeFile = active,
                allFiles = _projectFiles.value,
                diagnostics = diagnostics,
                toolExecutor = executor,
            )
        }
    }

    fun cancelGemini() {
        aiJob?.cancel()
        aiJob = null
        geminiAssistant.markCancelled()
    }

    private fun createToolExecutor(
        projectId: String,
        packageName: String,
    ): ProjectToolExecutor = object : ProjectToolExecutor {

        private suspend fun flushInMemoryFiles() {
            saveJob?.cancel()
            _projectFiles.value.forEach { file ->
                repository.saveFile(projectId, file.relativePath, file.content)
            }
        }

        override suspend fun listFiles(): AgentToolResult {
            flushInMemoryFiles()
            val paths = repository.listAllProjectPaths(projectId, includeBuild = false)
            val arr = JSONArray().apply { paths.forEach { put(it) } }
            return AgentToolResult(
                success = true,
                stepDetail = "${paths.size} file(s) in project",
                payload = JSONObject()
                    .put("files", arr)
                    .put("count", paths.size),
            )
        }

        override suspend fun readFile(path: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            if (!target.exists() || !target.isFile) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            val content = withContext(Dispatchers.IO) { target.readText() }
            val lines = content.lines()
            val truncated = if (content.length > 50_000) content.take(50_000) + "\n...[truncated]" else content
            return AgentToolResult(
                success = true,
                stepDetail = "Read ${lines.size} lines",
                payload = JSONObject()
                    .put("path", cleanPath)
                    .put("lineCount", lines.size)
                    .put("content", truncated),
            )
        }

        override suspend fun writeFile(path: String, content: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            val existed = target.exists()
            repository.saveFile(projectId, cleanPath, content)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            if (!cleanPath.startsWith("build/") && cleanPath != "project.json") {
                openFile(cleanPath)
            }
            val verb = if (existed) "Updated" else "Created"
            return AgentToolResult(
                success = true,
                stepDetail = "$verb ${content.lines().size} lines",
                payload = JSONObject()
                    .put("status", verb.lowercase())
                    .put("path", cleanPath)
                    .put("lineCount", content.lines().size),
            )
        }

        override suspend fun editFile(path: String, oldText: String, newText: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory or empty: $path"),
                )
            if (!target.exists() || !target.isFile) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            val current = withContext(Dispatchers.IO) { target.readText() }
            val normalizedCurrent = current.replace("\r\n", "\n")
            val normalizedOld = oldText.replace("\r\n", "\n")
            val normalizedNew = newText.replace("\r\n", "\n")

            if (normalizedOld.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Empty old_text",
                    payload = JSONObject().put("error", "old_text must not be empty. Use write_file for full replacements."),
                )
            }

            val firstIdx = normalizedCurrent.indexOf(normalizedOld)
            if (firstIdx < 0) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Snippet not found in $cleanPath",
                    payload = JSONObject().put(
                        "error",
                        "old_text was not found in $cleanPath. Call read_file first to verify exact text, or use write_file.",
                    ),
                )
            }
            val secondIdx = normalizedCurrent.indexOf(normalizedOld, startIndex = firstIdx + normalizedOld.length)
            if (secondIdx >= 0) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Multiple matches in $cleanPath",
                    payload = JSONObject().put(
                        "error",
                        "old_text matched multiple locations in $cleanPath. Include more surrounding lines to make it unique.",
                    ),
                )
            }

            val updated = normalizedCurrent.replaceFirst(normalizedOld, normalizedNew)
            repository.saveFile(projectId, cleanPath, updated)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            if (!cleanPath.startsWith("build/") && cleanPath != "project.json") {
                openFile(cleanPath)
            }
            return AgentToolResult(
                success = true,
                stepDetail = "Edited ${newText.lines().size} line(s)",
                payload = JSONObject()
                    .put("status", "edited")
                    .put("path", cleanPath),
            )
        }

        override suspend fun deleteFile(path: String): AgentToolResult {
            flushInMemoryFiles()
            val cleanPath = path.trim().replace('\\', '/').trimStart('/')
            if (cleanPath == "AndroidManifest.xml" || cleanPath == "project.json") {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Protected file: $cleanPath",
                    payload = JSONObject().put("error", "$cleanPath cannot be deleted."),
                )
            }
            val target = repository.resolveProjectFile(projectId, cleanPath)
                ?: return AgentToolResult(
                    success = false,
                    stepDetail = "Invalid path: $path",
                    payload = JSONObject().put("error", "Path is outside project directory: $path"),
                )
            if (!target.exists()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "File not found: $cleanPath",
                    payload = JSONObject().put("error", "File does not exist: $cleanPath"),
                )
            }
            repository.deleteFile(projectId, cleanPath)
            closeTab(cleanPath)
            _projectFiles.value = repository.loadProjectFiles(projectId)
            return AgentToolResult(
                success = true,
                stepDetail = "Deleted $cleanPath",
                payload = JSONObject().put("status", "deleted").put("path", cleanPath),
            )
        }

        override suspend fun searchFiles(query: String): AgentToolResult {
            flushInMemoryFiles()
            val needle = query.trim()
            if (needle.isEmpty()) {
                return AgentToolResult(
                    success = false,
                    stepDetail = "Empty search query",
                    payload = JSONObject().put("error", "Search query cannot be empty."),
                )
            }
            val paths = repository.listAllProjectPaths(projectId, includeBuild = false)
            val matches = JSONArray()
            withContext(Dispatchers.IO) {
                for (relPath in paths) {
                    if (matches.length() >= 50) break
                    val file = repository.resolveProjectFile(projectId, relPath) ?: continue
                    file.readLines().forEachIndexed { idx, lineText ->
                        if (matches.length() < 50 && lineText.contains(needle, ignoreCase = true)) {
                            matches.put(
                                JSONObject()
                                    .put("path", relPath)
                                    .put("line", idx + 1)
                                    .put("text", lineText.trim().take(160)),
                            )
                        }
                    }
                }
            }
            return AgentToolResult(
                success = true,
                stepDetail = "${matches.length()} match(es)",
                payload = JSONObject()
                    .put("query", needle)
                    .put("matches", matches),
            )
        }

        override suspend fun buildApk(): AgentToolResult {
            flushInMemoryFiles()
            _isBuilding.value = true
            _liveLogs.value = listOf("Gemini triggered offline APK build...")
            _lastBuildResult.value = null

            val result = try {
                apkBuilder.buildProject(
                    projectDir = repository.getProjectDir(projectId),
                    packageName = packageName,
                    onProgress = { line ->
                        _liveLogs.value = _liveLogs.value + line
                    },
                )
            } finally {
                _isBuilding.value = false
            }

            _lastBuildResult.value = result
            repository.recordBuildResult(projectId, result.success, result.apkSizeBytes)
            _projects.value = repository.listProjects()
            _activeProject.value = _projects.value.find { it.id == projectId }

            val diagArray = JSONArray().apply {
                result.diagnostics.take(25).forEach { d ->
                    put(
                        JSONObject()
                            .put("severity", d.severity.name)
                            .put("filePath", d.filePath)
                            .put("line", d.line)
                            .put("message", d.message),
                    )
                }
            }
            val summary = if (result.success) {
                "APK built in ${result.durationMs}ms (${result.apkSizeBytes / 1024} KB)"
            } else {
                "Failed with ${result.diagnostics.size} diagnostic(s)"
            }
            return AgentToolResult(
                success = result.success,
                stepDetail = summary,
                payload = JSONObject()
                    .put("success", result.success)
                    .put("durationMs", result.durationMs)
                    .put("apkSizeBytes", result.apkSizeBytes)
                    .put("compiledClassCount", result.compiledClassCount)
                    .put("diagnostics", diagArray)
                    .put("logs", JSONArray().apply { result.logs.takeLast(20).forEach { put(it) } }),
            )
        }
    }

    fun saveCustomGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            repository.saveCustomGeminiApiKey(apiKey)
            _statusBanner.value = if (apiKey.isBlank()) {
                "Cleared custom Gemini API key."
            } else {
                "Saved Gemini API key."
            }
        }
    }

    fun clearAiChat() {
        geminiAssistant.clearConversation()
    }

    fun applySuggestedCode(block: SuggestedCodeBlock) {
        val project = _activeProject.value ?: return
        val targetPath = block.suggestedPath
            ?.takeIf { it.isNotBlank() }
            ?: _activeFilePath.value
            ?: return

        viewModelScope.launch {
            repository.saveFile(project.id, targetPath, block.code)
            _projectFiles.value = repository.loadProjectFiles(project.id)
            openFile(targetPath)
            _statusBanner.value = "Applied Gemini code to $targetPath"
        }
    }

    fun updateEditorSettings(transform: (EditorSettings) -> EditorSettings) {
        viewModelScope.launch {
            repository.updateEditorSettings(transform)
        }
    }

    private fun buildStarterContentForNewFile(relPath: String, packageName: String): String {
        return when {
            relPath.endsWith(".java") -> {
                val className = relPath.substringAfterLast('/').removeSuffix(".java")
                val pkgSub = relPath.removePrefix("src/").substringBeforeLast('/', "")
                    .replace('/', '.')
                    .ifBlank { packageName }
                """
                package $pkgSub;

                public class $className {
                    public $className() {
                    }
                }
                """.trimIndent() + "\n"
            }
            relPath.endsWith(".xml") -> """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                </resources>
            """.trimIndent() + "\n"
            else -> ""
        }
    }

    private fun sanitizePackageName(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9._]"), "")
            .split('.')
            .filter { it.isNotBlank() }
            .map { seg -> if (seg.first().isDigit()) "a$seg" else seg }
            .joinToString(".")
        return if (cleaned.contains('.')) cleaned else "com.example.${cleaned.ifBlank { "app" }}"
    }
}
