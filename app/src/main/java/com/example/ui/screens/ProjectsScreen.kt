package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.EditorSettings
import com.example.model.ProjectMetadata
import com.example.model.ProjectTemplate
import com.example.ui.panels.formatBytes
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<ProjectMetadata>,
    editorSettings: EditorSettings,
    onOpenProject: (ProjectMetadata) -> Unit,
    onCreateProject: (String, String, ProjectTemplate) -> Unit,
    onDeleteProject: (String) -> Unit,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var createDialogTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }
    var deleteCandidate by remember { mutableStateOf<ProjectMetadata?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                        Column {
                            Text("DroidForge Studio", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Offline APK IDE • ECJ + DX + ApkSigner",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("open_settings_button"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "IDE Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createDialogTemplate = ProjectTemplate.COUNTER_APP },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("new_project_fab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Android project")
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 880.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Hero Banner
                item {
                    val heroBrush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(heroBrush)
                            .padding(20.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.OfflineBolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = "100% OFFLINE COMPILER",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = "GEMINI COPILOT",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Code, compile, and sign real Android APKs directly on your device.",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Write Java & XML resources in an intelligent editor with syntax highlighting, clickable compiler diagnostics, APK inspection, and Gemini assistance.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Starter Templates
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Start From Template",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(ProjectTemplate.entries) { template ->
                                Card(
                                    onClick = { createDialogTemplate = template },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                    modifier = Modifier
                                        .width(220.dp)
                                        .testTag("template_card_${template.name.lowercase()}"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = when (template) {
                                                ProjectTemplate.CANVAS_DRAW -> Icons.Default.Palette
                                                ProjectTemplate.CALCULATOR -> Icons.Default.Build
                                                else -> Icons.Default.Code
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = template.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = template.subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Recent Projects
                item {
                    Text(
                        text = "Your Projects (${projects.size})",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                items(projects, key = { it.id }) { project ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenProject(project) }
                            .testTag("project_card_${project.id}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
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
                                if (project.lastBuildSuccess == true && project.lastApkSizeBytes != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = "APK ready (${formatBytes(project.lastApkSizeBytes)})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { deleteCandidate = project },
                                modifier = Modifier.testTag("delete_project_${project.id}"),
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete ${project.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    createDialogTemplate?.let { template ->
        CreateProjectDialog(
            initialTemplate = template,
            onDismiss = { createDialogTemplate = null },
            onConfirm = { name, pkg, selectedTemplate ->
                createDialogTemplate = null
                onCreateProject(name, pkg, selectedTemplate)
            },
        )
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Project?") },
            text = { Text("Delete \"${target.name}\" and all its source files and compiled APKs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleteCandidate = null
                        onDeleteProject(id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
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
private fun CreateProjectDialog(
    initialTemplate: ProjectTemplate,
    onDismiss: () -> Unit,
    onConfirm: (String, String, ProjectTemplate) -> Unit,
) {
    var selectedTemplate by remember { mutableStateOf(initialTemplate) }
    var appName by remember { mutableStateOf(initialTemplate.defaultAppName) }
    var packageName by remember { mutableStateOf(initialTemplate.defaultPackage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Android Java Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_name_input"),
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_pkg_input"),
                )
                Text("Template", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectTemplate.entries.forEach { t ->
                        val selected = t == selectedTemplate
                        Surface(
                            onClick = {
                                selectedTemplate = t
                                appName = t.defaultAppName
                                packageName = t.defaultPackage
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = t.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(appName, packageName, selectedTemplate) },
                modifier = Modifier.testTag("confirm_create_project_button"),
            ) {
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

@Composable
fun EditorSettingsDialog(
    settings: EditorSettings,
    onUpdateSettings: ((EditorSettings) -> EditorSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("IDE & Editor Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(
                        text = "Editor Font Size: ${settings.fontSizeSp} sp",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = settings.fontSizeSp.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(fontSizeSp = v.toInt()) }
                        },
                        valueRange = 10f..20f,
                        steps = 9,
                    )
                }
                SettingsToggleRow(
                    label = "Dark IDE Theme",
                    checked = settings.darkTheme,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(darkTheme = checked) }
                    },
                )
                SettingsToggleRow(
                    label = "Word Wrap",
                    checked = settings.wordWrap,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(wordWrap = checked) }
                    },
                )
                SettingsToggleRow(
                    label = "Auto-Indent on Newline",
                    checked = settings.autoIndent,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(autoIndent = checked) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
