package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.SuggestedCodeBlock
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.model.ProjectFile
import com.example.model.ProjectMetadata
import com.example.ui.editor.CodeEditor
import com.example.ui.panels.BuildPanel
import com.example.ui.panels.GeminiPanel
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeWorkspaceScreen(
    project: ProjectMetadata,
    files: List<ProjectFile>,
    openTabs: List<String>,
    activeFilePath: String?,
    editorSettings: EditorSettings,
    isBuilding: Boolean,
    buildLogs: List<String>,
    lastBuildResult: BuildResult?,
    jumpToLine: Pair<String, Int>?,
    statusBanner: String?,
    aiMessages: List<ChatMessage>,
    aiState: AiUiState,
    onSelectFile: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onUpdateContent: (String) -> Unit,
    onBuildApk: () -> Unit,
    onJumpToDiagnostic: (BuildDiagnostic) -> Unit,
    onClearJump: () -> Unit,
    onExportApkUri: (android.net.Uri) -> Unit,
    onClearStatusBanner: () -> Unit,
    onAskGemini: (String) -> Unit,
    onApplyGeminiCode: (SuggestedCodeBlock) -> Unit,
    onClearAiChat: () -> Unit,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onBackToProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    var showBuildPanel by remember { mutableStateOf(false) }
    var showGeminiPanel by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val exportApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        if (uri != null) {
            onExportApkUri(uri)
        }
    }

    LaunchedEffect(isBuilding, lastBuildResult) {
        if (isBuilding || lastBuildResult != null) {
            showBuildPanel = true
        }
    }

    LaunchedEffect(statusBanner) {
        if (!statusBanner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusBanner)
            onClearStatusBanner()
        }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            showGeminiPanel -> showGeminiPanel = false
            showBuildPanel -> showBuildPanel = false
            else -> onBackToProjects()
        }
    }

    fun shareBuiltApk(apkPath: String) {
        runCatching {
            val file = File(apkPath)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share or Open APK"))
        }
    }

    val activeFile = files.find { it.relativePath == activeFilePath }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

        val mainScaffold: @Composable () -> Unit = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = onBackToProjects,
                                modifier = Modifier.testTag("back_to_projects_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = project.packageName,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (!isExpanded) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.testTag("open_files_drawer_button"),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Project files")
                                }
                            }
                            IconButton(
                                onClick = { showBuildPanel = !showBuildPanel },
                                modifier = Modifier.testTag("toggle_console_button"),
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = "Toggle build console",
                                    tint = if (showBuildPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = { showGeminiPanel = !showGeminiPanel },
                                modifier = Modifier.testTag("toggle_gemini_button"),
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Toggle Gemini assistant",
                                    tint = if (showGeminiPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Editor settings")
                            }
                            Button(
                                onClick = onBuildApk,
                                enabled = !isBuilding,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .testTag("build_apk_button"),
                            ) {
                                if (isBuilding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(if (isBuilding) "Building" else "Build APK")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                ) {
                    // Persistent Explorer Sidebar on Expanded Screens
                    if (isExpanded) {
                        ProjectExplorerPane(
                            files = files,
                            activeFilePath = activeFilePath,
                            onSelectFile = onSelectFile,
                            onCreateFile = { showNewFileDialog = true },
                            onDeleteFile = onDeleteFile,
                            modifier = Modifier
                                .width(230.dp)
                                .fillMaxHeight(),
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // Center Editor + Bottom Console Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        // File Tabs Strip
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(openTabs, key = { it }) { tabPath ->
                                        val isSelected = tabPath == activeFilePath
                                        val fileName = tabPath.substringAfterLast('/')
                                        Surface(
                                            onClick = { onSelectFile(tabPath) },
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = fileName,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                                IconButton(
                                                    onClick = { onCloseTab(tabPath) },
                                                    modifier = Modifier.size(20.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Close $fileName",
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { showNewFileDialog = true },
                                    modifier = Modifier.testTag("new_file_button"),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Create new file")
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Code Editor Area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            if (activeFile != null) {
                                CodeEditor(
                                    filePath = activeFile.relativePath,
                                    content = activeFile.content,
                                    language = activeFile.language,
                                    settings = editorSettings,
                                    diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                                    jumpToLineRequest = jumpToLine,
                                    onJumpHandled = onClearJump,
                                    onContentChange = onUpdateContent,
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("No file open", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Open a Java or XML file from the Project Explorer.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Collapsible Bottom Build Console & APK Inspector
                        AnimatedVisibility(visible = showBuildPanel) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                BuildPanel(
                                    isBuilding = isBuilding,
                                    logs = buildLogs,
                                    buildResult = lastBuildResult,
                                    onDiagnosticClick = { diag ->
                                        onJumpToDiagnostic(diag)
                                    },
                                    onAskGeminiToFix = {
                                        showGeminiPanel = true
                                        onAskGemini("Fix the compiler errors reported in the latest build and return the corrected full Java code.")
                                    },
                                    onExportApk = {
                                        val safeName = project.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                                        exportApkLauncher.launch("$safeName-debug.apk")
                                    },
                                    onShareApk = { apkPath -> shareBuiltApk(apkPath) },
                                    onClose = { showBuildPanel = false },
                                    modifier = Modifier.height(260.dp),
                                )
                            }
                        }
                    }

                    // Persistent Gemini Side Pane on Expanded Screens
                    if (isExpanded && showGeminiPanel) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        GeminiPanel(
                            activeFileName = activeFile?.relativePath,
                            diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                            messages = aiMessages,
                            aiState = aiState,
                            onSendPrompt = onAskGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                            modifier = Modifier
                                .width(340.dp)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        if (isExpanded) {
            mainScaffold()
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                        ProjectExplorerPane(
                            files = files,
                            activeFilePath = activeFilePath,
                            onSelectFile = { path ->
                                onSelectFile(path)
                                scope.launch { drawerState.close() }
                            },
                            onCreateFile = {
                                scope.launch { drawerState.close() }
                                showNewFileDialog = true
                            },
                            onDeleteFile = onDeleteFile,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                content = mainScaffold,
            )

            if (showGeminiPanel) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showGeminiPanel = false },
                    sheetState = sheetState,
                ) {
                    Box(modifier = Modifier.height(540.dp)) {
                        GeminiPanel(
                            activeFileName = activeFile?.relativePath,
                            diagnostics = lastBuildResult?.diagnostics.orEmpty(),
                            messages = aiMessages,
                            aiState = aiState,
                            onSendPrompt = onAskGemini,
                            onApplyCode = onApplyGeminiCode,
                            onClearChat = onClearAiChat,
                            onClose = { showGeminiPanel = false },
                        )
                    }
                }
            }
        }
    }

    if (showNewFileDialog) {
        NewFileDialog(
            packageName = project.packageName,
            onDismiss = { showNewFileDialog = false },
            onCreate = { path ->
                showNewFileDialog = false
                onCreateFile(path)
            },
        )
    }

    if (showSettingsDialog) {
        EditorSettingsDialog(
            settings = editorSettings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showSettingsDialog = false },
        )
    }
}

@Composable
private fun ProjectExplorerPane(
    files: List<ProjectFile>,
    activeFilePath: String?,
    onSelectFile: (String) -> Unit,
    onCreateFile: () -> Unit,
    onDeleteFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Project Files", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onCreateFile) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New file")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(files, key = { it.relativePath }) { file ->
                    val isSelected = file.relativePath == activeFilePath
                    val isProtected = file.relativePath == "AndroidManifest.xml"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelectFile(file.relativePath) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (file.language) {
                                FileLanguage.JAVA -> Icons.Default.Code
                                else -> Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = if (file.language == FileLanguage.JAVA) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = file.relativePath,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!isProtected) {
                            IconButton(
                                onClick = { onDeleteFile(file.relativePath) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete ${file.fileName}",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun NewFileDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val pkgPath = packageName.replace('.', '/')
    var pathInput by remember { mutableStateOf("src/$pkgPath/Helper.java") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a relative project path (Java under src/ or XML under res/values/).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(pathInput) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
