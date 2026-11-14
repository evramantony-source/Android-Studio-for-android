package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.GEMINI_MODEL_ID
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.ProjectFile
import com.example.model.ProjectMetadata
import com.example.model.ProjectTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val Context.editorDataStore by preferencesDataStore(name = "droidforge_editor_prefs")

class ProjectRepository(
    private val context: Context,
) {
    private val projectsRoot: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    private object PrefKeys {
        val FONT_SIZE = intPreferencesKey("editor_font_size")
        val WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        val AUTO_INDENT = booleanPreferencesKey("editor_auto_indent")
        val DARK_THEME = booleanPreferencesKey("editor_dark_theme")
        val CUSTOM_GEMINI_API_KEY = stringPreferencesKey("custom_gemini_api_key")
        val GEMINI_MODEL_ID_KEY = stringPreferencesKey("gemini_model_id")
    }

    val customGeminiApiKey: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.CUSTOM_GEMINI_API_KEY].orEmpty()
    }

    val geminiModelId: Flow<String> = context.editorDataStore.data.map { prefs ->
        prefs[PrefKeys.GEMINI_MODEL_ID_KEY]?.takeIf { it.isNotBlank() } ?: GEMINI_MODEL_ID
    }

    suspend fun saveCustomGeminiApiKey(apiKey: String) {
        context.editorDataStore.edit { prefs ->
            val cleaned = apiKey.trim()
            if (cleaned.isEmpty()) {
                prefs.remove(PrefKeys.CUSTOM_GEMINI_API_KEY)
            } else {
                prefs[PrefKeys.CUSTOM_GEMINI_API_KEY] = cleaned
            }
        }
    }

    suspend fun saveGeminiModelId(modelId: String) {
        context.editorDataStore.edit { prefs ->
            val cleaned = modelId.trim().removePrefix("models/")
            if (cleaned.isEmpty()) {
                prefs.remove(PrefKeys.GEMINI_MODEL_ID_KEY)
            } else {
                prefs[PrefKeys.GEMINI_MODEL_ID_KEY] = cleaned
            }
        }
    }

    val editorSettings: Flow<EditorSettings> = context.editorDataStore.data.map { prefs ->
        EditorSettings(
            fontSizeSp = prefs[PrefKeys.FONT_SIZE] ?: 13,
            wordWrap = prefs[PrefKeys.WORD_WRAP] ?: false,
            autoIndent = prefs[PrefKeys.AUTO_INDENT] ?: true,
            darkTheme = prefs[PrefKeys.DARK_THEME] ?: true,
        )
    }

    suspend fun updateEditorSettings(transform: (EditorSettings) -> EditorSettings) {
        context.editorDataStore.edit { prefs ->
            val current = EditorSettings(
                fontSizeSp = prefs[PrefKeys.FONT_SIZE] ?: 13,
                wordWrap = prefs[PrefKeys.WORD_WRAP] ?: false,
                autoIndent = prefs[PrefKeys.AUTO_INDENT] ?: true,
                darkTheme = prefs[PrefKeys.DARK_THEME] ?: true,
            )
            val updated = transform(current)
            prefs[PrefKeys.FONT_SIZE] = updated.fontSizeSp.coerceIn(10, 22)
            prefs[PrefKeys.WORD_WRAP] = updated.wordWrap
            prefs[PrefKeys.AUTO_INDENT] = updated.autoIndent
            prefs[PrefKeys.DARK_THEME] = updated.darkTheme
        }
    }

    suspend fun listProjects(): List<ProjectMetadata> = withContext(Dispatchers.IO) {
        val dirs = projectsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.isEmpty()) {
            val starter = createProjectInternal(
                name = ProjectTemplate.COUNTER_APP.defaultAppName,
                packageName = ProjectTemplate.COUNTER_APP.defaultPackage,
                template = ProjectTemplate.COUNTER_APP,
            )
            return@withContext listOf(starter)
        }
        dirs.mapNotNull { readMetadata(it) }
            .sortedByDescending { it.updatedAtMillis }
    }

    suspend fun createProject(
        name: String,
        packageName: String,
        template: ProjectTemplate,
    ): ProjectMetadata = withContext(Dispatchers.IO) {
        createProjectInternal(name, packageName, template)
    }

    private fun createProjectInternal(
        name: String,
        packageName: String,
        template: ProjectTemplate,
    ): ProjectMetadata {
        val id = UUID.randomUUID().toString().take(8)
        val projectDir = File(projectsRoot, id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val files = TemplateProvider.generateFiles(template, name, packageName)
        files.forEach { (relPath, content) ->
            val file = File(projectDir, relPath)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        val meta = ProjectMetadata(
            id = id,
            name = name,
            packageName = packageName,
            template = template,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        writeMetadata(projectDir, meta)
        return meta
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val dir = File(projectsRoot, projectId)
        if (dir.exists()) dir.deleteRecursively()
    }

    fun getProjectDir(projectId: String): File = File(projectsRoot, projectId)

    suspend fun loadProjectFiles(projectId: String): List<ProjectFile> = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                rel != "project.json" && !rel.startsWith("build/")
            }
            .map { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                ProjectFile(
                    relativePath = rel,
                    content = file.readText(),
                    language = detectLanguage(rel),
                )
            }
            .sortedWith(
                compareBy<ProjectFile>(
                    { if (it.fileName == "MainActivity.java") 0 else 1 },
                    { if (it.relativePath.startsWith("src/")) 0 else 1 },
                    { it.relativePath },
                ),
            )
            .toList()
    }

    fun resolveProjectFile(projectId: String, relativePath: String): File? {
        val dir = getProjectDir(projectId).canonicalFile
        val cleanRel = relativePath.trim().replace('\\', '/').trimStart('/')
        if (cleanRel.isEmpty()) return null
        val candidate = File(dir, cleanRel).canonicalFile
        val dirPrefix = dir.path + File.separator
        if (candidate.path != dir.path && !candidate.path.startsWith(dirPrefix)) {
            return null
        }
        return candidate
    }

    suspend fun listAllProjectPaths(
        projectId: String,
        includeBuild: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        if (!dir.exists()) return@withContext emptyList()
        dir.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                rel != "project.json" && (includeBuild || !rel.startsWith("build/"))
            }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    suspend fun saveFile(projectId: String, relativePath: String, content: String) = withContext(Dispatchers.IO) {
        val target = resolveProjectFile(projectId, relativePath) ?: return@withContext
        target.parentFile?.mkdirs()
        target.writeText(content)
        val dir = getProjectDir(projectId)
        readMetadata(dir)?.let { meta ->
            writeMetadata(dir, meta.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun deleteFile(projectId: String, relativePath: String) = withContext(Dispatchers.IO) {
        val target = resolveProjectFile(projectId, relativePath) ?: return@withContext
        if (target.exists() && target.isFile) target.delete()
    }

    suspend fun recordBuildResult(
        projectId: String,
        success: Boolean,
        apkSizeBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val dir = getProjectDir(projectId)
        readMetadata(dir)?.let { meta ->
            writeMetadata(
                dir,
                meta.copy(
                    updatedAtMillis = System.currentTimeMillis(),
                    lastBuildSuccess = success,
                    lastApkSizeBytes = if (success) apkSizeBytes else meta.lastApkSizeBytes,
                    lastBuildTimestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun detectLanguage(path: String): FileLanguage = when {
        path.endsWith(".java", ignoreCase = true) -> FileLanguage.JAVA
        path.endsWith(".xml", ignoreCase = true) -> FileLanguage.XML
        path.endsWith(".json", ignoreCase = true) -> FileLanguage.JSON
        else -> FileLanguage.TEXT
    }

    private fun writeMetadata(dir: File, meta: ProjectMetadata) {
        val obj = JSONObject().apply {
            put("id", meta.id)
            put("name", meta.name)
            put("packageName", meta.packageName)
            put("template", meta.template.name)
            put("createdAtMillis", meta.createdAtMillis)
            put("updatedAtMillis", meta.updatedAtMillis)
            meta.lastBuildSuccess?.let { put("lastBuildSuccess", it) }
            meta.lastApkSizeBytes?.let { put("lastApkSizeBytes", it) }
            meta.lastBuildTimestamp?.let { put("lastBuildTimestamp", it) }
        }
        File(dir, "project.json").writeText(obj.toString(2))
    }

    private fun readMetadata(dir: File): ProjectMetadata? {
        val metaFile = File(dir, "project.json")
        if (!metaFile.exists()) return null
        return runCatching {
            val obj = JSONObject(metaFile.readText())
            ProjectMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                packageName = obj.getString("packageName"),
                template = ProjectTemplate.valueOf(
                    obj.optString("template", ProjectTemplate.BLANK_ACTIVITY.name),
                ),
                createdAtMillis = obj.optLong("createdAtMillis", dir.lastModified()),
                updatedAtMillis = obj.optLong("updatedAtMillis", dir.lastModified()),
                lastBuildSuccess = if (obj.has("lastBuildSuccess")) obj.getBoolean("lastBuildSuccess") else null,
                lastApkSizeBytes = if (obj.has("lastApkSizeBytes")) obj.getLong("lastApkSizeBytes") else null,
                lastBuildTimestamp = if (obj.has("lastBuildTimestamp")) obj.getLong("lastBuildTimestamp") else null,
            )
        }.getOrNull()
    }
}
