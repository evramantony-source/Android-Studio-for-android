package com.example.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BuildDiagnostic
import com.example.model.DiagnosticSeverity
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSyntaxColors

@Composable
fun CodeEditor(
    filePath: String,
    content: String,
    language: FileLanguage,
    settings: EditorSettings,
    diagnostics: List<BuildDiagnostic>,
    jumpToLineRequest: Pair<String, Int>?,
    onJumpHandled: () -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val syntaxColors = LocalSyntaxColors.current
    var textValue by remember(filePath) {
        mutableStateOf(TextFieldValue(content))
    }
    val undoStack = remember(filePath) { mutableListOf<TextFieldValue>() }
    val redoStack = remember(filePath) { mutableListOf<TextFieldValue>() }

    // Keep editor synchronized when external actions (e.g. Gemini Apply Code) modify the file
    LaunchedEffect(content) {
        if (content != textValue.text) {
            undoStack.add(textValue)
            if (undoStack.size > 60) undoStack.removeAt(0)
            redoStack.clear()
            textValue = TextFieldValue(
                text = content,
                selection = TextRange(content.length.coerceAtMost(textValue.selection.start)),
            )
        }
    }

    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val lines = remember(textValue.text) { textValue.text.split('\n') }
    val errorLineNumbers = remember(diagnostics, filePath) {
        diagnostics
            .filter { it.filePath == filePath && it.severity == DiagnosticSeverity.ERROR }
            .map { it.line }
            .toSet()
    }
    val currentLine = remember(textValue.text, textValue.selection) {
        val cursor = textValue.selection.start.coerceIn(0, textValue.text.length)
        textValue.text.substring(0, cursor).count { it == '\n' } + 1
    }

    val lineHeightSp = (settings.fontSizeSp + 7).sp
    val lineHeightDp = (settings.fontSizeSp + 7).dp

    // Handle clicking a build error diagnostic to jump to line
    LaunchedEffect(jumpToLineRequest) {
        if (jumpToLineRequest != null && jumpToLineRequest.first == filePath) {
            val targetLine = jumpToLineRequest.second.coerceAtLeast(1)
            val offset = lineStartOffset(textValue.text, targetLine)
            textValue = textValue.copy(selection = TextRange(offset))
            val approxY = ((targetLine - 3).coerceAtLeast(0)) * (settings.fontSizeSp + 7)
            verticalScroll.animateScrollTo(approxY * 2)
            onJumpHandled()
        }
    }

    fun applyEdit(newValue: TextFieldValue) {
        if (newValue.text != textValue.text) {
            undoStack.add(textValue)
            if (undoStack.size > 60) undoStack.removeAt(0)
            redoStack.clear()
            onContentChange(newValue.text)
        }
        textValue = newValue
    }

    fun insertSnippet(snippet: String, cursorBackOffset: Int = 0) {
        val start = textValue.selection.min.coerceIn(0, textValue.text.length)
        val end = textValue.selection.max.coerceIn(0, textValue.text.length)
        val nextText = textValue.text.replaceRange(start, end, snippet)
        val nextCursor = (start + snippet.length - cursorBackOffset).coerceIn(0, nextText.length)
        applyEdit(TextFieldValue(nextText, TextRange(nextCursor)))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(syntaxColors.editorBackground),
    ) {
        // Collapsible Find & Replace bar
        AnimatedVisibility(visible = showFindBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            placeholder = { Text("Find...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("editor_find_input"),
                        )
                        val matchCount = remember(findQuery, textValue.text) {
                            if (findQuery.isEmpty()) 0
                            else Regex(Regex.escape(findQuery)).findAll(textValue.text).count()
                        }
                        Text(
                            text = "$matchCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val from = textValue.selection.max.coerceIn(0, textValue.text.length)
                                    val idx = textValue.text.indexOf(findQuery, from)
                                        .takeIf { it >= 0 }
                                        ?: textValue.text.indexOf(findQuery)
                                    if (idx >= 0) {
                                        textValue = textValue.copy(
                                            selection = TextRange(idx, idx + findQuery.length),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("Next")
                        }
                        IconButton(onClick = { showFindBar = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close find bar")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            placeholder = { Text("Replace with...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val replaced = textValue.text.replace(findQuery, replaceQuery)
                                    applyEdit(TextFieldValue(replaced, TextRange(0)))
                                }
                            },
                        ) {
                            Text("Replace All")
                        }
                    }
                }
            }
        }

        // Main Gutter + Code Canvas
        val codeTextStyle = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = settings.fontSizeSp.sp,
            lineHeight = lineHeightSp,
            color = syntaxColors.plainText,
        )

        val visualTransformation = remember(language, syntaxColors) {
            CodeSyntaxVisualTransformation(language, syntaxColors)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(verticalScroll),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Line Number Gutter
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .background(syntaxColors.gutterBackground)
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    lines.forEachIndexed { index, _ ->
                        val lineNum = index + 1
                        val isError = lineNum in errorLineNumbers
                        val isActive = lineNum == currentLine
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(lineHeightDp)
                                .background(
                                    when {
                                        isError -> syntaxColors.errorLineBackground
                                        isActive -> syntaxColors.activeLineBackground
                                        else -> syntaxColors.gutterBackground
                                    },
                                )
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (isError) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error),
                                    )
                                }
                                Text(
                                    text = "$lineNum",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = (settings.fontSizeSp - 2).coerceAtLeast(9).sp,
                                    color = when {
                                        isError -> MaterialTheme.colorScheme.error
                                        isActive -> MaterialTheme.colorScheme.primary
                                        else -> syntaxColors.gutterText
                                    },
                                    fontWeight = if (isActive || isError) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // Editable Code Text Field
                val fieldModifier = if (settings.wordWrap) {
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                }

                BasicTextField(
                    value = textValue,
                    onValueChange = { incoming ->
                        val processed = if (
                            settings.autoIndent &&
                            incoming.text.length == textValue.text.length + 1 &&
                            incoming.selection.collapsed &&
                            incoming.selection.start > 0 &&
                            incoming.text[incoming.selection.start - 1] == '\n'
                        ) {
                            applyAutoIndentOnNewline(incoming)
                        } else {
                            incoming
                        }
                        applyEdit(processed)
                    },
                    textStyle = codeTextStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = visualTransformation,
                    modifier = fieldModifier.testTag("code_editor_field"),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Symbol & Action Accessory Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        if (undoStack.isNotEmpty()) {
                            redoStack.add(textValue)
                            val prev = undoStack.removeAt(undoStack.lastIndex)
                            textValue = prev
                            onContentChange(prev.text)
                        }
                    },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.testTag("editor_undo_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(
                    onClick = {
                        if (redoStack.isNotEmpty()) {
                            undoStack.add(textValue)
                            val next = redoStack.removeAt(redoStack.lastIndex)
                            textValue = next
                            onContentChange(next.text)
                        }
                    },
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.testTag("editor_redo_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
                IconButton(
                    onClick = { showFindBar = !showFindBar },
                    modifier = Modifier.testTag("editor_find_button"),
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Find and replace")
                }
                IconButton(
                    onClick = {
                        val formatted = simpleIndentFormat(textValue.text, language)
                        applyEdit(TextFieldValue(formatted, TextRange(0)))
                    },
                    modifier = Modifier.testTag("editor_format_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, contentDescription = "Auto-indent file")
                }

                Spacer(Modifier.width(4.dp))

                val symbolChips = listOf(
                    "TAB" to { insertSnippet("    ") },
                    "{ }" to { insertSnippet("{\n    \n}", cursorBackOffset = 2) },
                    "( )" to { insertSnippet("()", cursorBackOffset = 1) },
                    ";" to { insertSnippet(";") },
                    "=" to { insertSnippet(" = ") },
                    "\"" to { insertSnippet("\"\"", cursorBackOffset = 1) },
                    "." to { insertSnippet(".") },
                    "//" to { insertSnippet("// ") },
                    "<>" to { insertSnippet("<>", cursorBackOffset = 1) },
                )
                symbolChips.forEach { (label, action) ->
                    Surface(
                        onClick = action,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lineStartOffset(text: String, targetLine: Int): Int {
    if (targetLine <= 1) return 0
    var current = 1
    for (i in text.indices) {
        if (text[i] == '\n') {
            current++
            if (current == targetLine) return i + 1
        }
    }
    return text.length
}

private fun applyAutoIndentOnNewline(incoming: TextFieldValue): TextFieldValue {
    val cursor = incoming.selection.start
    val beforeNewline = incoming.text.substring(0, cursor - 1)
    val prevLineStart = beforeNewline.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    val prevLine = beforeNewline.substring(prevLineStart)
    val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val extraIndent = if (prevLine.trimEnd().endsWith("{")) "    " else ""
    val indent = baseIndent + extraIndent
    if (indent.isEmpty()) return incoming
    val updated = incoming.text.substring(0, cursor) + indent + incoming.text.substring(cursor)
    return TextFieldValue(updated, TextRange(cursor + indent.length))
}

private fun simpleIndentFormat(code: String, language: FileLanguage): String {
    if (language != FileLanguage.JAVA) {
        return code.lines().joinToString("\n") { it.trimEnd() }
    }
    var depth = 0
    val out = mutableListOf<String>()
    for (raw in code.lines()) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            out.add("")
            continue
        }
        if (trimmed.startsWith("}")) {
            depth = (depth - 1).coerceAtLeast(0)
        }
        out.add("    ".repeat(depth) + trimmed)
        val openCount = trimmed.count { it == '{' }
        val closeCount = trimmed.count { it == '}' }
        val net = openCount - closeCount
        if (trimmed.startsWith("}")) {
            depth = (depth + net + 1).coerceAtLeast(0)
        } else {
            depth = (depth + net).coerceAtLeast(0)
        }
    }
    return out.joinToString("\n")
}
