package com.evram.androidstudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val bg = Color.rgb(30, 30, 30)
    private val panel = Color.rgb(37, 37, 38)
    private val panel2 = Color.rgb(45, 45, 48)
    private val textColor = Color.rgb(230, 230, 230)
    private val muted = Color.rgb(150, 150, 150)
    private val accent = Color.rgb(70, 130, 180)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(v: String, s: Float = 14f, c: Int = textColor) = TextView(this).apply { text=v; textSize=s; setTextColor(c); gravity=Gravity.CENTER_VERTICAL }
    private fun button(v: String) = Button(this).apply { text=v; textSize=12f; isAllCaps=false; setTextColor(textColor); minHeight=dp(40) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg; window.navigationBarColor=bg
        val prefs=getSharedPreferences("workspace", MODE_PRIVATE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val status=label("  Ready",12f,muted).apply { setBackgroundColor(panel2) }
        val editor=EditText(this).apply { setTextColor(textColor); setHintTextColor(muted); textSize=14f; typeface=Typeface.MONOSPACE; gravity=Gravity.TOP or Gravity.START; setPadding(dp(16),dp(12),dp(16),dp(12)); setBackgroundColor(bg); isSingleLine=false }
        val tabTitle=label("  MainActivity.kt",13f)
        val files=linkedMapOf("MainActivity.kt" to "package com.evram.androidstudio\n\nclass MainActivity : Activity() {\n    // Your code goes here...\n}\n", "AndroidManifest.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest>\n</manifest>\n", "build.gradle.kts" to "plugins {\n    id(\"com.android.application\")\n}\n", "README.md" to "# Android Studio for Android\n")
        val selected=ArrayList<TextView>()
        fun save(){ val n=tabTitle.text.toString().trim(); if(n.isNotEmpty()) { files[n]=editor.text.toString(); prefs.edit().putString("file_$n",editor.text.toString()).apply() } }
        fun open(n:String,row:TextView){ save(); editor.setText(files[n] ?: prefs.getString("file_$n","") ?: ""); editor.setSelection(editor.length()); tabTitle.text="  $n"; status.text="  Editing $n     Ready"; selected.forEach{it.setBackgroundColor(Color.TRANSPARENT)}; selected.clear(); row.setBackgroundColor(accent); selected.add(row) }
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(8),0); setBackgroundColor(panel2) }
        toolbar.addView(label("AS4A",16f).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(dp(62),-1)); toolbar.addView(label("Android Studio for Android",14f),LinearLayout.LayoutParams(0,-1,1f))
        val run=button("▶ Run"); val saveBtn=button("💾"); toolbar.addView(run,LinearLayout.LayoutParams(dp(72),dp(42))); toolbar.addView(saveBtn,LinearLayout.LayoutParams(dp(52),dp(42))); root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(52)))
        val main=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}; val project=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(panel)}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(4),0)}; header.addView(label("PROJECT",12f,muted),LinearLayout.LayoutParams(0,dp(44),1f)); val plus=button("+"); header.addView(plus,LinearLayout.LayoutParams(dp(48),dp(42))); project.addView(header)
        val tree=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(8))}; tree.addView(label("▾  AndroidStudioForAndroid",14f),LinearLayout.LayoutParams(-1,dp(38))); tree.addView(label("    ▾  app",14f),LinearLayout.LayoutParams(-1,dp(34))); tree.addView(label("        ▾  src",14f),LinearLayout.LayoutParams(-1,dp(34)))
        fun addRow(n:String){ val r=label("            📄  $n",13f); r.setPadding(dp(4),0,0,0); r.setOnClickListener{open(n,r)}; tree.addView(r,LinearLayout.LayoutParams(-1,dp(38))) }
        files.keys.forEach{addRow(it)}; val scroll=ScrollView(this); scroll.addView(tree); project.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val area=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; area.addView(tabTitle,LinearLayout.LayoutParams(-1,dp(42))); area.addView(editor,LinearLayout.LayoutParams(-1,0,1f)); area.addView(status,LinearLayout.LayoutParams(-1,dp(30))); main.addView(project,LinearLayout.LayoutParams(dp(280),-1)); main.addView(area,LinearLayout.LayoutParams(0,-1,1f)); root.addView(main,LinearLayout.LayoutParams(-1,0,1f))
        editor.setText(prefs.getString("file_MainActivity.kt",files["MainActivity.kt"]))
        saveBtn.setOnClickListener{save();status.text="  Saved ${tabTitle.text.toString().trim()}     Ready";Toast.makeText(this,"File saved",Toast.LENGTH_SHORT).show()}
        run.setOnClickListener{status.text="  Run requested     Build engine: coming later"}
        plus.setOnClickListener{ val input=EditText(this); input.hint="Project name"; AlertDialog.Builder(this).setTitle("Create Project").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_-> val n=input.text.toString().trim().ifEmpty{"MyProject"}; getSharedPreferences("projects",MODE_PRIVATE).edit().putString("last",n).apply(); status.text="  Project '$n' created     Workspace ready"; Toast.makeText(this,"Project created: $n",Toast.LENGTH_SHORT).show() }.show() }
        setContentView(root)
    }
}
