package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.GeminiCodeAssistant
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
        viewModelScope.launch {
            geminiAssistant.askGemini(
                userPrompt = prompt,
                apiKey = apiKey,
                projectName = project.name,
                packageName = project.packageName,
                activeFile = active,
                allFiles = _projectFiles.value,
                diagnostics = diagnostics,
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
