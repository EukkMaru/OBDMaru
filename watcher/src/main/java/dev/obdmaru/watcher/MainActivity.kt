package dev.obdmaru.watcher

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: ByteChart
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var devices: Spinner
    private lateinit var setup: LinearLayout
    private var addresses = listOf<String>()
    private val update = object : Runnable {
        override fun run() {
            val s = WatcherService.snapshot
            val age = if (s.at == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - s.at
            val fresh = WatcherService.running && age <= 1500 && s.block == WatcherService.selected && s.values != null
            status.text = (if (s.demo) "SYNTHETIC DEMO · " else "") + if (age > 1500 && s.values != null && WatcherService.running) "Stale · waiting for response" else s.status
            metrics.text = if (fresh) String.format(Locale.US, "%.1f Hz actual / %d Hz target  ·  %d ms reply  ·  %d ms old", s.rate, WatcherService.targetHz, s.latency, age) else "${WatcherService.targetHz} Hz target · no fresh sample"
            chart.setSample(if (fresh) s.values else null)
            handler.postDelayed(this, 100)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(12, 18, 28); window.navigationBarColor = Color.rgb(12, 18, 28)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(6)); setBackgroundColor(Color.rgb(12,18,28)) }
        root.setOnApplyWindowInsetsListener { v, inset ->
            val bars = inset.getInsets(WindowInsets.Type.systemBars()); v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(6) + bars.bottom); inset
        }
        setContentView(root)
        fun text(value: String, size: Float = 13f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(205,218,232)); setPadding(0,dp(3),0,dp(3)) }
        fun button(label: String, parent: LinearLayout, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; minHeight = dp(40); minimumHeight = dp(40)
            parent.addView(this, LinearLayout.LayoutParams(0, dp(46), 1f)); setOnClickListener { action() }
        }
        val head = LinearLayout(this)
        head.addView(text("OBDMaru Watcher", 21f), LinearLayout.LayoutParams(0,-2,1f))
        val connection = Button(this).apply { text = "Connect"; isAllCaps = false; textSize = 12f }
        head.addView(connection, LinearLayout.LayoutParams(dp(105),dp(46))); root.addView(head)
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices = Spinner(this); setup.addView(devices)
        val controls = LinearLayout(this)
        button("Refresh", controls) { refreshDevices() }
        button("Start", controls) { start(false) }
        button("Demo", controls) { start(true) }
        setup.addView(controls); root.addView(setup)
        connection.setOnClickListener { setup.visibility = if (setup.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val groupRow = LinearLayout(this)
        val groups = Spinner(this)
        groups.adapter = compactAdapter(WatchCatalog.blocks.map { it.toString() })
        groups.setSelection(WatchCatalog.blocks.indexOf(WatcherService.selected))
        groupRow.addView(groups, LinearLayout.LayoutParams(0,dp(48),1f))
        val rate = Spinner(this); rate.adapter = compactAdapter(listOf("5 Hz","10 Hz")); rate.setSelection(if(WatcherService.targetHz==10)1 else 0)
        groupRow.addView(rate,LinearLayout.LayoutParams(dp(88),dp(48))); root.addView(groupRow)
        groups.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { WatcherService.selected = WatchCatalog.blocks[position]; chart.reset(); }
        }
        rate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { WatcherService.targetHz = if(position==0)5 else 10 }
        }
        status = text("Ready",14f); metrics = text("5 Hz target · no fresh sample",11f); root.addView(status); root.addView(metrics)
        root.addView(text("Unknown raw bytes · fixed bar scale 0–255 · tap a byte for bits",11f))
        val scroll = ScrollView(this).apply { isFillViewport = false }
        chart = ByteChart(this); scroll.addView(chart); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val hide = CheckBox(this).apply { text = "Hide bytes only observed as zero"; textSize = 12f; setTextColor(Color.LTGRAY) }
        hide.setOnCheckedChangeListener { _, checked -> chart.hideZero = checked; chart.requestLayout(); chart.invalidate() }; root.addView(hide)
        root.addView(text("Indices start at 0 after service + identifier. NONZERO does not prove a binary signal. One block polls at a time.",10f))
        val footer = LinearLayout(this)
        button("Stop",footer) { if(WatcherService.running) startService(Intent(this,WatcherService::class.java).setAction("stop")) }
        button("Share log",footer) { share() }
        root.addView(footer)
        refreshDevices(); handler.post(update)
    }
    private fun compactAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position,convertView,parent).also { (it as TextView).textSize = 12f }
    }
    @Suppress("MissingPermission") private fun refreshDevices() {
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions((listOf(Manifest.permission.BLUETOOTH_CONNECT) + if(Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray(),1); return
        }
        val paired = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.sortedBy { it.address } ?: emptyList()
        addresses = paired.map { it.address }
        devices.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,paired.map { "${it.name ?: "Device"} · ${it.address}" }.ifEmpty { listOf("Pair adapter in Bluetooth settings") })
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==1 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED) refreshDevices() }
    private fun start(demo: Boolean) {
        if(WatcherService.running) { Toast.makeText(this,"Stop the current session first",Toast.LENGTH_SHORT).show(); return }
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) { refreshDevices(); return }
        if(!demo && addresses.isEmpty()) { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); return }
        chart.reset()
        startForegroundService(Intent(this,WatcherService::class.java).putExtra("demo",demo).putExtra("address",if(demo)"demo" else addresses[devices.selectedItemPosition]))
        setup.visibility = View.GONE
    }
    private fun share() {
        if(WatcherService.running) { Toast.makeText(this,"Stop to finish the log before sharing",Toast.LENGTH_SHORT).show(); return }
        val file = WatcherService.lastFile ?: java.io.File(filesDir,"sessions").listFiles()?.filter { it.extension=="jsonl" }?.maxByOrNull { it.lastModified() }
        if(file==null) { Toast.makeText(this,"No log yet",Toast.LENGTH_SHORT).show(); return }
        val uri = Uri.parse("content://dev.obdmaru.watcher.logs/${file.name}")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share watcher log"))
    }
    override fun onDestroy() { handler.removeCallbacks(update); super.onDestroy() }
}

class ByteChart(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Int>? = null
    private val seenNonzero = mutableSetOf<Int>()
    private var previous: List<Int>? = null
    private var changed = setOf<Int>()
    private var selectedByte: Int? = null
    var hideZero = false
    private val density = resources.displayMetrics.density
    private val rowHeight get() = 54f * density
    private val shown get() = (0 until (values?.size ?: WatcherService.selected.length)).filter { !hideZero || it in seenNonzero }
    fun reset() { values=null; previous=null; changed=emptySet(); selectedByte=null; seenNonzero.clear(); requestLayout(); invalidate() }
    fun setSample(bytes: ByteArray?) {
        val next = bytes?.let { WatchModel.unsigned(it) }
        if(next == values) return
        changed = next?.indices?.filter { previous?.getOrNull(it)?.let { old -> old != next[it] } ?: false }?.toSet() ?: emptySet()
        values = next; if(next!=null) { next.forEachIndexed { i,v -> if(v!=0)seenNonzero.add(i) }; previous=next }
        requestLayout(); invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ((shown.size+1)/2*rowHeight + 64*density).toInt()) }
    private fun label(canvas: Canvas, text: String, x: Float, y: Float, color: Int, size: Float) { paint.color=color; paint.textSize=size*density; paint.typeface=Typeface.MONOSPACE; canvas.drawText(text,x,y,paint) }
    override fun onDraw(canvas: Canvas) {
        val cyan = Color.rgb(62,213,207); val muted=Color.rgb(139,156,176); val cell=width/2f
        shown.forEachIndexed { n,i ->
            val x=(n%2)*cell+4*density; val y=(n/2)*rowHeight; val v=values?.getOrNull(i)
            label(canvas,"b%02d".format(i),x,y+15*density,if(i in changed)Color.YELLOW else Color.WHITE,12f)
            label(canvas,if(v==null)"—" else "%3d %02X".format(v,v),x+39*density,y+15*density,cyan,12f)
            label(canvas,if(v==null)"?" else if(v==0)"ZERO" else "NONZERO",x,y+30*density,if(v==null||v==0)muted else cyan,9f)
            paint.color=Color.rgb(35,49,66); canvas.drawRoundRect(x,y+36*density,x+cell-12*density,y+43*density,3*density,3*density,paint)
            if(v!=null) { paint.color=if(v==0)muted else cyan; canvas.drawRoundRect(x,y+36*density,x+(cell-12*density)*v/255f,y+43*density,3*density,3*density,paint) }
        }
        val y=((shown.size+1)/2)*rowHeight+20*density
        val i=selectedByte; val v=i?.let { values?.getOrNull(it) }
        label(canvas,if(i==null)"Tap any byte to inspect bits 7 → 0" else "b%02d bits 7→0: %s".format(i,if(v==null)"unavailable" else WatchModel.bits(v).joinToString(" "){if(it)"1" else "0"}),4*density,y,Color.LTGRAY,10f)
        label(canvas,"Yellow index = changed on last update",4*density,y+20*density,muted,10f)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if(event.action==android.view.MotionEvent.ACTION_UP) { val n=(event.y/rowHeight).toInt()*2+if(event.x>=width/2f)1 else 0; selectedByte=shown.getOrNull(n); invalidate(); performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
