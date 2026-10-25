package dev.obdmaru.dashboard
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import android.graphics.Color

class MainActivity:Activity() {
 private val handler=Handler(Looper.getMainLooper())
 private lateinit var dash:DashView
 private lateinit var setup:LinearLayout
 private lateinit var devices:Spinner
 private lateinit var benchmarkStatus:TextView
 private var addresses=listOf<String>()
 private val update=object:Runnable {override fun run(){dash.invalidate();benchmarkStatus.text=DashboardService.benchmarkProgress;benchmarkStatus.visibility=if(benchmarkStatus.text.isEmpty())View.GONE else View.VISIBLE;handler.postDelayed(this,100)}}
 private fun dp(x:Int)=(resources.displayMetrics.density*x).toInt()
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(10,16,25))}
  root.setOnApplyWindowInsetsListener {v,i->val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);i}
  setContentView(root)
  val top=LinearLayout(this)
  val title=TextView(this).apply {text="OBDMaru Dash";textSize=20f;setTextColor(Color.WHITE);setPadding(dp(12),dp(10),0,0)}
  top.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
  fun button(label:String,parent:LinearLayout,action:()->Unit) {parent.addView(Button(this).apply {text=label;textSize=12f;isAllCaps=false;setOnClickListener{action()}},LinearLayout.LayoutParams(0,dp(46),1f))}
  val connect=Button(this).apply {text="Connect";isAllCaps=false;textSize=12f;setOnClickListener {setup.visibility=if(setup.visibility==View.VISIBLE)View.GONE else View.VISIBLE}}
  top.addView(connect,LinearLayout.LayoutParams(dp(104),dp(48)));root.addView(top)
  setup=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  devices=Spinner(this);setup.addView(devices)
  val actions=LinearLayout(this);button("Refresh",actions){refresh()};button("Start",actions){start(false)};button("Demo",actions){start(true)};setup.addView(actions)
  setup.addView(Button(this).apply{text="Parked bottleneck test";isAllCaps=false;setOnClickListener{
   AlertDialog.Builder(this@MainActivity).setTitle("Parked bottleneck test")
    .setMessage("Park in P, engage the parking brake and keep the engine idling in a ventilated place. Stop other OBD apps. Leave the car controls alone during the test.\n\nThe automatic test usually takes several minutes (12-minute limit). Progress appears above the dash. It saves raw replies and failures; use Share log when it finishes. You can stop early and keep the partial log.")
    .setPositiveButton("Start parked test"){_,_->start(false,true)}.setNegativeButton("Cancel",null).show()
  }});root.addView(setup)
  benchmarkStatus=TextView(this).apply{textSize=15f;setTextColor(Color.WHITE);setPadding(dp(12),dp(8),dp(12),dp(8));visibility=View.GONE};root.addView(benchmarkStatus)
  if(DashboardService.running)setup.visibility=View.GONE
  dash=DashView(this);root.addView(dash,LinearLayout.LayoutParams(-1,0,1f))
  val footer=LinearLayout(this);button("Stop",footer){if(DashboardService.running)startService(Intent(this,DashboardService::class.java).setAction("stop"))};button("Share log",footer){share()};root.addView(footer)
  refresh();handler.post(update)
 }
 @Suppress("MissingPermission") private fun refresh() {
  if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
   val perms=mutableListOf(Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33)perms.add(Manifest.permission.POST_NOTIFICATIONS);requestPermissions(perms.toTypedArray(),1);return
  }
  val paired=getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.sortedBy{it.address}?:emptyList()
  addresses=paired.map{it.address};devices.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,paired.map{"${it.name?:"Device"} · ${it.address}"}.ifEmpty{listOf("Pair adapter in Bluetooth settings")})
 }
 override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==1 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)refresh()}
 private fun start(demo:Boolean,benchmark:Boolean=false) {
  if(DashboardService.running){Toast.makeText(this,"Stop the current session first",Toast.LENGTH_SHORT).show();return}
  if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){refresh();return}
  if(!demo && addresses.isEmpty()){startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));return}
  startForegroundService(Intent(this,DashboardService::class.java).putExtra("demo",demo).putExtra("benchmark",benchmark).putExtra("address",if(demo)"demo"else addresses[devices.selectedItemPosition]));setup.visibility=View.GONE
 }
 private fun share(){
  if(DashboardService.running){Toast.makeText(this,"Stop before sharing the log",Toast.LENGTH_SHORT).show();return}
  val file=DashboardService.lastFile?:java.io.File(filesDir,"sessions").listFiles()?.filter{it.extension=="jsonl"}?.maxByOrNull{it.lastModified()}
  if(file==null){Toast.makeText(this,"No log yet",Toast.LENGTH_SHORT).show();return}
  val uri=Uri.parse("content://dev.obdmaru.dashboard.logs/${file.name}")
  startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share dashboard log"))
 }
 override fun onDestroy(){handler.removeCallbacks(update);super.onDestroy()}
}
