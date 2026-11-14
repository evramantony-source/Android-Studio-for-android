package com.example.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.model.FileLanguage
import com.example.ui.theme.SyntaxColors

class CodeSyntaxVisualTransformation(
    private val language: FileLanguage,
    private val colors: SyntaxColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = SyntaxHighlighter.highlight(text.text, language, colors)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

object SyntaxHighlighter {

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null",
    )

    private val JAVA_COMMON_TYPES = setOf(
        "String", "Object", "Integer", "Boolean", "Double", "Float", "Long", "Math",
        "System", "Activity", "Bundle", "Context", "Intent", "View", "ViewGroup",
        "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout",
        "TextView", "Button", "EditText", "ImageView", "CheckBox", "Switch", "SeekBar",
        "ListView", "ArrayAdapter", "Toast", "AlertDialog", "Canvas", "Paint", "Path",
        "Color", "Typeface", "GradientDrawable", "MotionEvent", "Gravity", "Log",
        "WebView", "SharedPreferences", "Handler", "Looper", "ArrayList", "List", "Map",
        "HashMap", "Override", "R",
    )

    fun highlight(
        code: String,
        language: FileLanguage,
        colors: SyntaxColors,
    ): AnnotatedString {
        return when (language) {
            FileLanguage.JAVA -> highlightJava(code, colors)
            FileLanguage.XML -> highlightXml(code, colors)
            FileLanguage.JSON -> highlightJson(code, colors)
            FileLanguage.TEXT -> AnnotatedString(code)
        }
    }

    private fun highlightJava(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    // Single-line comment
                    c == '/' && i + 1 < len && code[i + 1] == '/' -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) len else it }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    // Multi-line comment
                    c == '/' && i + 1 < len && code[i + 1] == '*' -> {
                        val end = code.indexOf("*/", i + 2).let { if (it == -1) len else it + 2 }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    // String or char literal
                    c == '"' || c == '\'' -> {
                        val quote = c
                        var j = i + 1
                        while (j < len) {
                            if (code[j] == '\\' && j + 1 < len) {
                                j += 2
                            } else if (code[j] == quote) {
                                j++
                                break
                            } else if (code[j] == '\n') {
                                break
                            } else {
                                j++
                            }
                        }
                        addStyle(SpanStyle(color = colors.string), i, j)
                        i = j
                    }
                    // Annotation
                    c == '@' -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        addStyle(SpanStyle(color = colors.annotation), i, j)
                        i = j
                    }
                    // Number
                    c.isDigit() -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                        addStyle(SpanStyle(color = colors.number), i, j)
                        i = j
                    }
                    // Identifier / Keyword / Type
                    c.isLetter() || c == '_' -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        val word = code.substring(i, j)
                        when {
                            word in JAVA_KEYWORDS -> addStyle(
                                SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium),
                                i,
                                j,
                            )
                            word in JAVA_COMMON_TYPES || (word[0].isUpperCase() && word.any { it.isLowerCase() }) ->
                                addStyle(SpanStyle(color = colors.type), i, j)
                        }
                        i = j
                    }
                    else -> i++
                }
            }
        }

    private fun highlightXml(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    code.startsWith("<!--", i) -> {
                        val end = code.indexOf("-->", i + 4).let { if (it == -1) len else it + 3 }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < len && code[j] != '"' && code[j] != '\n') j++
                        if (j < len && code[j] == '"') j++
                        addStyle(SpanStyle(color = colors.string), i, j)
                        i = j
                    }
                    c == '<' -> {
                        var j = i + 1
                        if (j < len && (code[j] == '/' || code[j] == '?')) j++
                        while (j < len && !code[j].isWhitespace() && code[j] != '>' && code[j] != '/') j++
                        addStyle(SpanStyle(color = colors.xmlTag, fontWeight = FontWeight.Medium), i, j)
                        i = j
                    }
                    c == '>' || (c == '/' && i + 1 < len && code[i + 1] == '>') -> {
                        val end = if (c == '/') i + 2 else i + 1
                        addStyle(SpanStyle(color = colors.xmlTag), i, end)
                        i = end
                    }
                    (c.isLetter() || c == '_') -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == ':')) j++
                        if (j < len && code[j] == '=') {
                            addStyle(SpanStyle(color = colors.xmlAttr), i, j)
                        }
                        i = j
                    }
                    else -> i++
                }
            }
        }

    private fun highlightJson(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    c == '"' -> {
                        var j = i + 1
                        while (j < len && code[j] != '"' && code[j] != '\n') {
                            if (code[j] == '\\' && j + 1 < len) j += 2 else j++
                        }
                        if (j < len && code[j] == '"') j++
                        val isKey = j < len && code.drop(j).trimStart().startsWith(":")
                        addStyle(
                            SpanStyle(color = if (isKey) colors.xmlAttr else colors.string),
                            i,
                            j,
                        )
                        i = j
                    }
                    c.isDigit() -> {
                        var j = i + 1
                        while (j < len && (code[j].isDigit() || code[j] == '.')) j++
                        addStyle(SpanStyle(color = colors.number), i, j)
                        i = j
                    }
                    else -> i++
                }
            }
        }
}
