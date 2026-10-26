package dev.obdmaru.scanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.File

class MainActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var devices: Spinner
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var scan: Button
    private lateinit var demo: Button
    private lateinit var stop: Button
    private lateinit var report: Button
    private lateinit var raw: Button
    private var addresses=emptyList<String>()
    private var rendered: ScanSnapshot?=null
    private lateinit var catalog: DtcCatalog
    private val ink=Color.rgb(220,231,242)
    private val accent=Color.rgb(84,217,190)
    private val update=object: Runnable {
        override fun run() {
            val s=ScannerService.snapshot
            scan.isEnabled=!ScannerService.running; demo.isEnabled=!ScannerService.running
            stop.isEnabled=ScannerService.running; report.isEnabled=!ScannerService.running; raw.isEnabled=!ScannerService.running
            if(s!=rendered) {
                status.text=(if(s.demo)"SYNTHETIC DEMO · " else "")+s.state
                results.removeAllViews()
                s.replies.forEach { reply ->
                    val card=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)); setBackgroundColor(Color.rgb(23,36,53)) }
                    card.addView(label(reply.query.title,17f,accent))
                    card.addView(label(reply.text.substringAfter('\n',"No results yet"),14f))
                    results.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
                }
                if(s.replies.isEmpty()) {
                    val saved=latest("txt")
                    results.addView(label(if(saved!=null && !ScannerService.running && !s.finished)"Last saved report\n\n${saved.readText()}" else if(s.finished)s.state else "Scan results will appear here.\n\nNo reply means unknown—not zero faults.",14f))
                }
                rendered=s
            }
            handler.postDelayed(this,250)
        }
    }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float=14f, color: Int=ink)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); setPadding(0,dp(4),0,dp(4)); setTextIsSelectable(true)
    }
    private fun button(value: String, parent: LinearLayout, action: ()->Unit): Button=Button(this).apply {
        text=value; isAllCaps=false; textSize=13f; minHeight=dp(48)
        parent.addView(this,LinearLayout.LayoutParams(0,-2,1f)); setOnClickListener{action()}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog=DtcCatalog.load(assets.open("dtc-generic.tsv").reader())
        window.statusBarColor=Color.rgb(12,20,33); window.navigationBarColor=Color.rgb(12,20,33)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,20,33)) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(dp(16)+bars.left,dp(10)+bars.top,dp(16)+bars.right,dp(8)+bars.bottom); insets
        }
        setContentView(root)
        root.addView(label("OBDMaru Scanner",25f).apply { typeface=Typeface.DEFAULT_BOLD })
        root.addView(label("FAULT CODES · READINESS · FREEZE FRAME",10f,accent))
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(label("Park, apply the parking brake and switch the ignition ON. Engine may be off. Stop Dash, Watcher and other OBD apps first.",13f))
        devices=Spinner(this); body.addView(devices,LinearLayout.LayoutParams(-1,dp(52)))
        val actions=LinearLayout(this)
        button("Refresh",actions){refresh()}
        scan=button("Scan car",actions){start(false)}
        demo=button("Demo",actions){start(true)}
        body.addView(actions)
        status=label("Ready",15f,accent); body.addView(status)
        body.addView(label("Coverage: standard emissions OBD-II. Full Hyundai ABS, airbag and body-module diagnostics are not included. Read-only; no fault clearing.",12f))
        results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(12),0,dp(10)) }; body.addView(results)
        body.addView(label("Offline code lookup",18f,accent))
        val lookupRow=LinearLayout(this)
        val input=EditText(this).apply { hint="e.g. P0401"; setTextColor(ink); setHintTextColor(Color.GRAY); isSingleLine=true; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        lookupRow.addView(input,LinearLayout.LayoutParams(0,dp(52),1.5f))
        button("Look up",lookupRow) {
            val code=input.text.toString().trim().uppercase()
            if(!Regex("[PCBU][0-3][0-9A-F]{3}").matches(code)) { input.error="Use a five-character code such as P0401" }
            else AlertDialog.Builder(this).setTitle(code).setMessage(catalog.explain(code)+"\n\nA code describes a detected condition, not a confirmed failed part. Exact TL manufacturer definitions are not bundled.")
                .setPositiveButton("OK",null).setNeutralButton("Search web") { _,_ -> openUrl("https://www.google.com/search?q="+Uri.encode("Hyundai Tucson TL 1.7 CRDi "+code)) }.show()
        }
        body.addView(lookupRow)
        body.addView(label("Descriptions are an offline community generic reference, not a Hyundai service manual. Unknown codes remain visible with their reporting ECU.",12f))
        val about=LinearLayout(this)
        button("Saved scans",about){savedScans()}
        button("Data & license",about) {
            AlertDialog.Builder(this).setTitle("Diagnostic reference data").setMessage("Generic descriptions: Wal33D/dtc-database\nRevision 04c43d72e7db7197658b6f72fe582c5076d9eee8\nFiltered to generic code ranges. No exact-TL manufacturer definitions bundled.\n\n"+assets.open("DTC-LICENSE.txt").bufferedReader().use{it.readText()})
                .setPositiveButton("OK",null).setNeutralButton("Source"){_,_->openUrl("https://github.com/Wal33D/dtc-database")}.show()
        }
        body.addView(about)
        val footer=LinearLayout(this)
        stop=button("Stop",footer){if(ScannerService.running)startService(Intent(this,ScannerService::class.java).setAction("stop"))}
        report=button("Share report",footer){share(latest("txt"))}
        raw=button("Share raw",footer){share(latest("jsonl"))}
        root.addView(footer)
        refresh()
    }
    override fun onResume(){super.onResume();handler.post(update)}
    override fun onPause(){handler.removeCallbacks(update);super.onPause()}
    @Suppress("MissingPermission") private fun refresh() {
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            val permissions=mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
            if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(permissions.toTypedArray(),1); return
        }
        try {
            val paired=getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.sortedBy{it.address}?:emptyList()
            addresses=paired.map{it.address}
            devices.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,paired.map{"${it.name?:"Device"} · ${it.address}"}.ifEmpty{listOf("Pair adapter in Bluetooth settings")})
        }catch(e:Exception){status.text="Bluetooth unavailable: ${e.message}"}
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==1 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)refresh()
    }
    private fun start(demo: Boolean) {
        if(ScannerService.running)return
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){refresh();return}
        if(!demo && addresses.isEmpty()){openBluetooth();return}
        rendered=null
        try { startForegroundService(Intent(this,ScannerService::class.java).putExtra("demo",demo).putExtra("address",if(demo)"demo" else addresses[devices.selectedItemPosition])) }
        catch(e:Exception){status.text="Could not start: ${e.message}"}
    }
    private fun latest(extension: String)=File(filesDir,"sessions").listFiles()?.filter{it.extension==extension}?.maxByOrNull{it.lastModified()}
    private fun share(file: File?) {
        if(ScannerService.running){Toast.makeText(this,"Wait for the scan to finish, or stop it first",Toast.LENGTH_SHORT).show();return}
        if(file==null){Toast.makeText(this,"No saved scan yet",Toast.LENGTH_SHORT).show();return}
        val uri=Uri.parse("content://dev.obdmaru.scanner.reports/${file.name}")
        val send=Intent(Intent.ACTION_SEND).setType(if(file.extension=="txt")"text/plain" else "application/x-ndjson")
            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData=ClipData.newRawUri("Scanner export",uri) }
        startActivity(Intent.createChooser(send,"Share scanner ${if(file.extension=="txt")"report" else "raw log"}"))
    }
    private fun savedScans() {
        val files=File(filesDir,"sessions").listFiles()?.filter{it.extension=="txt"}?.sortedByDescending{it.lastModified()}?:emptyList()
        if(files.isEmpty()){Toast.makeText(this,"No saved reports yet",Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(this).setTitle("Saved scans").setItems(files.map{it.name}.toTypedArray()){_,i ->
            val file=files[i]
            val text=label(file.readText(),13f).apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
            AlertDialog.Builder(this).setTitle(file.nameWithoutExtension).setView(ScrollView(this).apply{addView(text)})
                .setPositiveButton("Close",null).setNeutralButton("Share report"){_,_->share(file)}
                .setNegativeButton("Share raw"){_,_->share(File(file.parentFile,file.nameWithoutExtension+".jsonl").takeIf{it.isFile})}.show()
        }.setNegativeButton("Close",null).show()
    }
    private fun openBluetooth(){startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}
    private fun openUrl(url: String){try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:ActivityNotFoundException){Toast.makeText(this,"No browser available",Toast.LENGTH_SHORT).show()}}
}
