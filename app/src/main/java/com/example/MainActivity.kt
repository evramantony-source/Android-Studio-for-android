package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.IdeViewModel
import com.example.ui.screens.IdeWorkspaceScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.theme.DroidForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val viewModel: IdeViewModel = viewModel()
            val editorSettings by viewModel.editorSettings.collectAsStateWithLifecycle()
            val projects by viewModel.projects.collectAsStateWithLifecycle()
            val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
            val projectFiles by viewModel.projectFiles.collectAsStateWithLifecycle()
            val openTabs by viewModel.openTabs.collectAsStateWithLifecycle()
            val activeFilePath by viewModel.activeFilePath.collectAsStateWithLifecycle()
            val isBuilding by viewModel.isBuilding.collectAsStateWithLifecycle()
            val buildLogs by viewModel.liveLogs.collectAsStateWithLifecycle()
            val lastBuildResult by viewModel.lastBuildResult.collectAsStateWithLifecycle()
            val jumpToLine by viewModel.jumpToLine.collectAsStateWithLifecycle()
            val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
            val customGeminiApiKey by viewModel.customGeminiApiKey.collectAsStateWithLifecycle()
            val aiMessages by viewModel.aiMessages.collectAsStateWithLifecycle()
            val aiState by viewModel.aiState.collectAsStateWithLifecycle()

            DroidForgeTheme(darkTheme = editorSettings.darkTheme) {
                val currentProject = activeProject
                if (currentProject == null) {
                    ProjectsScreen(
                        projects = projects,
                        editorSettings = editorSettings,
                        onOpenProject = viewModel::openProject,
                        onCreateProject = viewModel::createProject,
                        onDeleteProject = viewModel::deleteProject,
                        onUpdateSettings = viewModel::updateEditorSettings,
                    )
                } else {
                    IdeWorkspaceScreen(
                        project = currentProject,
                        files = projectFiles,
                        openTabs = openTabs,
                        activeFilePath = activeFilePath,
                        editorSettings = editorSettings,
                        isBuilding = isBuilding,
                        buildLogs = buildLogs,
                        lastBuildResult = lastBuildResult,
                        jumpToLine = jumpToLine,
                        statusBanner = statusBanner,
                        customGeminiApiKey = customGeminiApiKey,
                        aiMessages = aiMessages,
                        aiState = aiState,
                        onSelectFile = viewModel::openFile,
                        onCloseTab = viewModel::closeTab,
                        onCreateFile = viewModel::createNewFile,
                        onDeleteFile = viewModel::deleteFile,
                        onUpdateContent = viewModel::updateActiveFileContent,
                        onBuildApk = viewModel::buildApk,
                        onJumpToDiagnostic = viewModel::jumpToDiagnostic,
                        onClearJump = viewModel::clearJumpRequest,
                        onExportApkUri = viewModel::exportApkToUri,
                        onClearStatusBanner = viewModel::clearStatusBanner,
                        onAskGemini = viewModel::askGemini,
                        onStopGemini = viewModel::cancelGemini,
                        onSaveGeminiApiKey = viewModel::saveCustomGeminiApiKey,
                        onApplyGeminiCode = viewModel::applySuggestedCode,
                        onClearAiChat = viewModel::clearAiChat,
                        onUpdateSettings = viewModel::updateEditorSettings,
                        onBackToProjects = viewModel::closeProject,
                    )
                }
            }
        }
    }
}
