package com.evram.androidstudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val bg = Color.rgb(30, 30, 30)
    private val panel = Color.rgb(37, 37, 38)
    private val panel2 = Color.rgb(45, 45, 48)
    private val textColor = Color.rgb(230, 230, 230)
    private val muted = Color.rgb(150, 150, 150)
    private val accent = Color.rgb(70, 130, 180)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 14f, color: Int = textColor): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(value: String): Button = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(textColor)
        minHeight = dp(40)
        minimumHeight = dp(40)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundColor(panel2)
        }
        toolbar.addView(label("AS4A", 16f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(dp(62), -1))
        toolbar.addView(label("Android Studio for Android", 14f), LinearLayout.LayoutParams(0, -1, 1f))
        val runButton = button("▶ Run")
        toolbar.addView(runButton, LinearLayout.LayoutParams(dp(72), dp(42)))
        toolbar.addView(button("⋮"), LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))

        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val projectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
        }
        val projectHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        projectHeader.addView(label("PROJECT", 12f, muted), LinearLayout.LayoutParams(0, dp(44), 1f))
        val newButton = button("+")
        projectHeader.addView(newButton, LinearLayout.LayoutParams(dp(48), dp(42)))
        projectPanel.addView(projectHeader)

        val treeScroll = ScrollView(this)
        val tree = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val project = label("▾  AndroidStudioForAndroid", 14f)
        project.setPadding(dp(4), 0, 0, 0)
        tree.addView(project, LinearLayout.LayoutParams(-1, dp(38)))
        tree.addView(label("    ▾  app", 14f), LinearLayout.LayoutParams(-1, dp(34)))
        tree.addView(label("        ▾  src", 14f), LinearLayout.LayoutParams(-1, dp(34)))
        tree.addView(label("            ▾  main", 14f), LinearLayout.LayoutParams(-1, dp(34)))

        val editor = EditText(this).apply {
            setTextColor(textColor)
            setHintTextColor(muted)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg)
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val status = label("  Ln 1, Col 1     UTF-8     Kotlin     Ready", 12f, muted)
        status.setBackgroundColor(panel2)

        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel)
        }
        val tabTitle = label("  MainActivity.kt", 13f)
        tab.addView(tabTitle, LinearLayout.LayoutParams(dp(220), dp(42)))

        val files = linkedMapOf(
            "MainActivity.kt" to "package com.evram.androidstudio\n\nclass MainActivity : Activity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        // Your code goes here...\n    }\n}\n",
            "AndroidManifest.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest>\n    <!-- Android app manifest -->\n</manifest>\n",
            "build.gradle.kts" to "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\nandroid {\n    // Module build configuration\n}\n",
            "settings.gradle.kts" to "pluginManagement {\n    // Plugin repositories\n}\n\ndependencyResolutionManagement {\n    // Dependency repositories\n}\n",
            "README.md" to "# Android Studio for Android\n\nA native Android development environment.\n"
        )

        fun openFile(fileName: String, row: TextView) {
            editor.setText(files[fileName] ?: "")
            editor.setSelection(0)
            tabTitle.text = "  $fileName"
            status.text = "  Ln 1, Col 1     UTF-8     ${if (fileName.endsWith(".kt")) "Kotlin" else "Text"}     $fileName"
            editor.requestFocus()
            tree.forEach { it.setBackgroundColor(Color.TRANSPARENT) }
            row.setBackgroundColor(accent)
        }

        fun addFileRow(fileName: String, text: String, indent: String): TextView {
            val row = label("$indent$fileName", 13f)
            row.setPadding(dp(4), 0, 0, 0)
            row.setOnClickListener { openFile(fileName, row) }
            tree.addView(row, LinearLayout.LayoutParams(-1, dp(38)))
            return row
        }

        addFileRow("MainActivity.kt", files["MainActivity.kt"]!!, "                Kotlin  ")
        addFileRow("AndroidManifest.xml", files["AndroidManifest.xml"]!!, "                XML  ")
        addFileRow("build.gradle.kts", files["build.gradle.kts"]!!, "                Gradle  ")
        addFileRow("settings.gradle.kts", files["settings.gradle.kts"]!!, "        Gradle  ")
        addFileRow("README.md", files["README.md"]!!, "        📄  ")

        treeScroll.addView(tree)
        projectPanel.addView(treeScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val editorArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        editorArea.addView(tab, LinearLayout.LayoutParams(-1, dp(42)))
        editorArea.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        editorArea.addView(status, LinearLayout.LayoutParams(-1, dp(30)))

        main.addView(projectPanel, LinearLayout.LayoutParams(dp(280), -1))
        main.addView(editorArea, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))

        runButton.setOnClickListener { status.text = "  Run requested     Build engine: not implemented yet     Ready" }
        newButton.setOnClickListener { status.text = "  New file requested     File creation: next milestone" }

        setContentView(root)
    }
}
