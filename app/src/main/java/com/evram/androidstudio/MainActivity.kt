package com.evram.androidstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val bg=Color.rgb(30,30,30); private val panel=Color.rgb(37,37,38); private val panel2=Color.rgb(45,45,48); private val fgColor=Color.rgb(230,230,230); private val muted=Color.rgb(150,150,150); private val accent=Color.rgb(70,130,180)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun tv(s:String,size:Float=14f,c:Int=fgColor)=TextView(this).apply{text=s;textSize=size;setTextColor(c);gravity=Gravity.CENTER_VERTICAL}
    private fun btn(s:String)=Button(this).apply{text=s;textSize=12f;isAllCaps=false;setTextColor(fgColor);minHeight=dp(40)}
    private lateinit var status:TextView
    private var currentFile:File?=null
    private var editor:EditText?=null

    override fun onCreate(b:Bundle?){super.onCreate(b);showManager()}

    private fun showManager(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(10),0);setBackgroundColor(panel2)}
        bar.addView(tv("AS4A",18f).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(dp(65),-1));bar.addView(tv("Project Manager",16f),LinearLayout.LayoutParams(0,-1,1f));bar.addView(btn("⚙ Settings"),LinearLayout.LayoutParams(dp(100),dp(44))).also{it.setOnClickListener{Toast.makeText(this@MainActivity,"Global settings coming soon",Toast.LENGTH_SHORT).show()}};root.addView(bar,LinearLayout.LayoutParams(-1,dp(56)))
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24))};content.addView(tv("Projects",22f),LinearLayout.LayoutParams(-1,dp(50)));content.addView(tv("Choose a project to open its IDE workspace.",14f,muted),LinearLayout.LayoutParams(-1,dp(36)))
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val prefs=getSharedPreferences("projects",MODE_PRIVATE);val names=prefs.getStringSet("names",emptySet())!!.toMutableSet();names.sorted().forEach{addProjectRow(list,it)}
        val newBtn=btn("＋  New Project");newBtn.setOnClickListener{createProject(list,names)};content.addView(newBtn,LinearLayout.LayoutParams(-1,dp(52)));val scroll=ScrollView(this);scroll.addView(list);content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(content,LinearLayout.LayoutParams(-1,0,1f));status=tv("  Ready",12f,muted);status.setBackgroundColor(panel2);root.addView(status,LinearLayout.LayoutParams(-1,dp(30)));setContentView(root)
    }

    private fun createProject(list:LinearLayout,names:MutableSet<String>){
        val input=EditText(this).apply{hint="Project name"}
        AlertDialog.Builder(this).setTitle("Create Project").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_->val n=input.text.toString().trim().ifEmpty{"MyProject"};if(names.add(n)){getSharedPreferences("projects",MODE_PRIVATE).edit().putStringSet("names",names).putString("last",n).apply();val dir=File(filesDir,"projects/$n");dir.mkdirs();createStarterFiles(dir);addProjectRow(list,n);openWorkspace(n)}else Toast.makeText(this,"Project already exists",Toast.LENGTH_SHORT).show()}.show()
    }

    private fun createStarterFiles(dir:File){
        val main=File(dir,"app/src/main");main.mkdirs();File(main,"java").mkdirs();File(main,"res").mkdirs()
        writeIfMissing(File(dir,"settings.gradle.kts"),"rootProject.name = \"${dir.name}\"\ninclude(\":app\")\n")
        writeIfMissing(File(dir,"build.gradle.kts"),"// Root Gradle build file\n")
        writeIfMissing(File(dir,"app/build.gradle.kts"),"plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\nandroid {\n    namespace = \"com.example.app\"\n    compileSdk = 35\n}\n")
        writeIfMissing(File(main,"AndroidManifest.xml"),"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"${dir.name}\" />\n</manifest>\n")
        writeIfMissing(File(main,"MainActivity.kt"),"package com.example.app\n\nclass MainActivity {\n    // Start building your app here.\n}\n")
        writeIfMissing(File(dir,"README.md"),"# ${dir.name}\n\nCreated with Android Studio for Android.\n")
    }
    private fun writeIfMissing(f:File,s:String){if(!f.exists()){f.parentFile?.mkdirs();f.writeText(s)}}

    private fun addProjectRow(list:LinearLayout,n:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(4),dp(8),dp(4));setBackgroundColor(panel)};row.addView(tv("📁  $n",15f),LinearLayout.LayoutParams(0,dp(58),1f));val del=btn("Delete");row.addView(del,LinearLayout.LayoutParams(dp(76),dp(44)));row.setOnClickListener{openWorkspace(n)}
        del.setOnClickListener{AlertDialog.Builder(this).setTitle("Delete Project?").setMessage("Delete $n from this app?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->val p=getSharedPreferences("projects",MODE_PRIVATE);p.edit().putStringSet("names",p.getStringSet("names",emptySet())!!.filter{it!=n}.toSet()).apply();File(filesDir,"projects/$n").deleteRecursively();list.removeView(row);status.text="  Deleted $n"}.show()};list.addView(row,LinearLayout.LayoutParams(-1,dp(66)).apply{setMargins(0,dp(6),0,0)})
    }

    private fun openWorkspace(projectName:String){
        getSharedPreferences("projects",MODE_PRIVATE).edit().putString("last",projectName).apply();currentFile=null
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(panel2)};val back=btn("← Projects");bar.addView(back,LinearLayout.LayoutParams(dp(105),dp(48)));bar.addView(tv("  📁 $projectName",16f),LinearLayout.LayoutParams(0,dp(48),1f));bar.addView(btn("▶ Run").apply{setOnClickListener{Toast.makeText(this@MainActivity,"Build engine coming later",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(dp(75),dp(48)));root.addView(bar)
        val body=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val tree=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(panel);setPadding(dp(8),dp(8),dp(8),dp(8))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};header.addView(tv("PROJECT",12f,muted),LinearLayout.LayoutParams(0,dp(42),1f));header.addView(btn("＋").apply{setOnClickListener{showNewItemDialog(projectName,tree)}},LinearLayout.LayoutParams(dp(50),dp(42)));header.addView(btn("↻").apply{setOnClickListener{refreshTree(tree,projectName)}},LinearLayout.LayoutParams(dp(50),dp(42)));tree.addView(header)
        val projectDir=File(filesDir,"projects/$projectName");projectDir.mkdirs();renderTree(tree,projectDir,"",projectDir)
        val editArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val tab=tv("  No file selected",13f);tab.setBackgroundColor(panel2);editArea.addView(tab,LinearLayout.LayoutParams(-1,dp(42)))
        editor=EditText(this).apply{setTextColor(fgColor);setHintTextColor(muted);textSize=14f;typeface=Typeface.MONOSPACE;gravity=Gravity.TOP or Gravity.START;setPadding(dp(16),dp(12),dp(16),dp(12));setBackgroundColor(bg);hint="Select a file to edit";isSingleLine=false};editArea.addView(editor,LinearLayout.LayoutParams(-1,0,1f));val save=btn("💾 Save");save.setOnClickListener{currentFile?.let{it.writeText(editor?.text?.toString()?:"");tab.text="  ${it.name}";status.text="  Saved ${it.name}"}};editArea.addView(save,LinearLayout.LayoutParams(-1,dp(46)))
        body.addView(ScrollView(this).apply{addView(tree)},LinearLayout.LayoutParams(dp(320),-1));body.addView(editArea,LinearLayout.LayoutParams(0,-1,1f));root.addView(body,LinearLayout.LayoutParams(-1,0,1f));back.setOnClickListener{currentFile=null;editor=null;showManager()};setContentView(root);status=tv("  $projectName ready",12f,muted);status.setBackgroundColor(panel2)
    }

    private fun refreshTree(tree:LinearLayout,projectName:String){if(tree.childCount>1)tree.removeViews(1,tree.childCount-1);renderTree(tree,File(filesDir,"projects/$projectName"),"",File(filesDir,"projects/$projectName"));status.text="  Project refreshed"}

    private fun renderTree(tree:LinearLayout,dir:File,prefix:String,rootDir:File){
        dir.listFiles()?.sortedWith(compareBy({!it.isDirectory},{it.name.lowercase()}))?.forEach{f->
            val row=tv(prefix+if(f.isDirectory)"📁  ${f.name}" else "📄  ${f.name}",13f);row.setPadding(dp(4),0,0,0)
            if(f.isDirectory){row.setOnClickListener{val children=tree.indexOfChild(row);if(row.tag==true){var i=children+1;while(i<tree.childCount && (tree.getChildAt(i).tag as? String)?.startsWith(f.absolutePath+"/")==true){tree.removeViewAt(i)};row.tag=false}else{val items=f.listFiles()?.sortedWith(compareBy({!it.isDirectory},{it.name.lowercase()}))?:emptyList();var i=children+1;items.reversed().forEach{child->val childRow=tv("$prefix    "+if(child.isDirectory)"📁  ${child.name}" else "📄  ${child.name}",13f);childRow.tag=f.absolutePath+"/";childRow.setPadding(dp(4),0,0,0);childRow.setOnClickListener{if(child.isFile)openFile(child)};tree.addView(childRow,i)};row.tag=true}}}else row.setOnClickListener{openFile(f)}
            row.tag=row.tag?:false;tree.addView(row,LinearLayout.LayoutParams(-1,dp(38)))
        }
    }
    private fun openFile(f:File){if(!f.isFile)return;currentFile=f;editor?.setText(runCatching{f.readText()}.getOrDefault(""));editor?.setSelection(editor?.length()?:0);status.text="  Opened ${f.name}"}

    private fun showNewItemDialog(projectName:String,tree:LinearLayout){
        val input=EditText(this).apply{hint="Name"};AlertDialog.Builder(this).setTitle("New item").setView(input).setItems(arrayOf("Create file","Create folder")){_,which->val n=input.text.toString().trim();if(n.isEmpty()){Toast.makeText(this,"Enter a name",Toast.LENGTH_SHORT).show()}else{val base=File(filesDir,"projects/$projectName");val f=File(base,n);if(which==0){f.parentFile?.mkdirs();f.createNewFile()}else f.mkdirs();refreshTree(tree,projectName);Toast.makeText(this,"Created $n",Toast.LENGTH_SHORT).show()}}.show()
    }
}
