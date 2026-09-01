package com.evram.androidstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val bg = Color.rgb(30,30,30); private val panel=Color.rgb(37,37,38); private val panel2=Color.rgb(45,45,48); private val text=Color.rgb(230,230,230); private val muted=Color.rgb(150,150,150); private val accent=Color.rgb(70,130,180)
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun tv(s:String,size:Float=14f,c:Int=text)=TextView(this).apply{text=s;textSize=size;setTextColor(c);gravity=Gravity.CENTER_VERTICAL}
    private fun btn(s:String)=Button(this).apply{text=s;textSize=12f;isAllCaps=false;setTextColor(text);minHeight=dp(40)}
    private lateinit var root:LinearLayout; private lateinit var status:TextView
    override fun onCreate(b:Bundle?){super.onCreate(b);showManager()}
    private fun showManager(){
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(10),0);setBackgroundColor(panel2)}
        bar.addView(tv("AS4A",18f).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(dp(65),-1));bar.addView(tv("Project Manager",16f),LinearLayout.LayoutParams(0,-1,1f));val settings=btn("⚙ Settings");bar.addView(settings,LinearLayout.LayoutParams(dp(100),dp(44)));root.addView(bar,LinearLayout.LayoutParams(-1,dp(56)))
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24))};content.addView(tv("Projects",22f),LinearLayout.LayoutParams(-1,dp(50)));content.addView(tv("Open a project to enter the IDE workspace.",14f,muted),LinearLayout.LayoutParams(-1,dp(36)))
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val prefs=getSharedPreferences("projects",MODE_PRIVATE);val names=prefs.getStringSet("names",emptySet())!!.toMutableSet();names.forEach{addProjectRow(list,it)}
        val newBtn=btn("＋  New Project");newBtn.setOnClickListener{createProject(list,names)};content.addView(newBtn,LinearLayout.LayoutParams(-1,dp(52)));val scroll=ScrollView(this);scroll.addView(list);content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(content,LinearLayout.LayoutParams(-1,0,1f));status=tv("  Ready",12f,muted);status.setBackgroundColor(panel2);root.addView(status,LinearLayout.LayoutParams(-1,dp(30)));settings.setOnClickListener{Toast.makeText(this,"Global settings will be expanded in a later milestone",Toast.LENGTH_SHORT).show()};setContentView(root)
    }
    private fun createProject(list:LinearLayout,names:MutableSet<String>){val input=EditText(this);input.hint="Project name";AlertDialog.Builder(this).setTitle("Create Project").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_->val n=input.text.toString().trim().ifEmpty{"MyProject"};if(names.add(n)){getSharedPreferences("projects",MODE_PRIVATE).edit().putStringSet("names",names).putString("last",n).apply();File(filesDir,"projects/$n").mkdirs();addProjectRow(list,n);openWorkspace(n)}else Toast.makeText(this,"Project already exists",Toast.LENGTH_SHORT).show()}.show()}
    private fun addProjectRow(list:LinearLayout,n:String){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(4),dp(8),dp(4));setBackgroundColor(panel)};row.addView(tv("📁  $n",15f),LinearLayout.LayoutParams(0,dp(58),1f));val del=btn("Delete");row.addView(del,LinearLayout.LayoutParams(dp(76),dp(44)));row.setOnClickListener{openWorkspace(n)};del.setOnClickListener{AlertDialog.Builder(this).setTitle("Delete Project?").setMessage("Delete $n from this app?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->getSharedPreferences("projects",MODE_PRIVATE).edit().putStringSet("names",getSharedPreferences("projects",MODE_PRIVATE).getStringSet("names",emptySet())!!.filter{it!=n}.toSet()).apply();File(filesDir,"projects/$n").deleteRecursively();list.removeView(row);status.text="  Deleted $n"}.show()};list.addView(row,LinearLayout.LayoutParams(-1,dp(66)).apply{setMargins(0,dp(6),0,0)})}
    private fun openWorkspace(projectName:String){getSharedPreferences("projects",MODE_PRIVATE).edit().putString("last",projectName).apply();val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)};val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(panel2)};val back=btn("← Projects");bar.addView(back,LinearLayout.LayoutParams(dp(105),dp(48)));bar.addView(tv("  📁 $projectName",16f),LinearLayout.LayoutParams(0,dp(48),1f));bar.addView(btn("▶ Run"),LinearLayout.LayoutParams(dp(75),dp(48)));root.addView(bar);val body=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val tree=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(panel);setPadding(dp(8),dp(8),dp(8),dp(8))};tree.addView(tv("PROJECT",12f,muted));val projectDir=File(filesDir,"projects/$projectName");projectDir.mkdirs();renderTree(tree,projectDir);val editor=EditText(this).apply{setTextColor(text);setHintTextColor(muted);textSize=14f;typeface=Typeface.MONOSPACE;gravity=Gravity.TOP or Gravity.START;setPadding(dp(16),dp(12),dp(16),dp(12));setBackgroundColor(bg);hint="Select a file to edit";isSingleLine=false};body.addView(ScrollView(this).apply{addView(tree)},LinearLayout.LayoutParams(dp(280),-1));body.addView(editor,LinearLayout.LayoutParams(0,-1,1f));root.addView(body,LinearLayout.LayoutParams(-1,0,1f));back.setOnClickListener{showManager()};setContentView(root)}
    private fun renderTree(tree:LinearLayout,dir:File){dir.listFiles()?.sortedWith(compareBy({!it.isDirectory},{it.name.lowercase()}))?.forEach{f->val row=tv("  "+if(f.isDirectory)"📁 " else "📄 "+f.name,13f);row.setPadding(dp(4),0,0,0);row.setOnClickListener{if(f.isDirectory){Toast.makeText(this,"Folder: ${f.name}",Toast.LENGTH_SHORT).show()}else{try{val e=tree.parent?.parent}catch(_:Exception){};Toast.makeText(this,"Selected ${f.name}",Toast.LENGTH_SHORT).show()}};tree.addView(row,LinearLayout.LayoutParams(-1,dp(38)))}}
}
