package dev.obdmaru.recorder

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.WindowInsets
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var start: Button
    private lateinit var demo: Button
    private lateinit var stop: Button
    private lateinit var marker: Button
    private lateinit var share: Button
    private lateinit var save: Button
    private var devices = emptyList<BluetoothDevice>()
    private var exportFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = getString(R.string.capture_status, CaptureService.status, CaptureService.currentFile?.let { getString(R.string.capture_file, it.name, it.length()/1024) } ?: "")
            start.isEnabled = !CaptureService.running && devices.isNotEmpty()
            demo.isEnabled = !CaptureService.running
            stop.isEnabled = CaptureService.running
            marker.isEnabled = CaptureService.running
            share.isEnabled = !CaptureService.running
            save.isEnabled = !CaptureService.running
            deviceSpinner.isEnabled = !CaptureService.running
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24); setBackgroundColor(Color.rgb(12,20,28)) }
        fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setTextColor(Color.rgb(215,233,235)); setPadding(0,12,0,12); root.addView(this) }
        fun button(s: String, action: () -> Unit) = Button(this).apply { text=s; setOnClickListener { action() }; root.addView(this) }
        text("OBDMaru / RECORDER", 26f)
        text("2018 Tucson TL · 1.7D · dry DCT\nRaw capture, including failed requests. Configure while parked.")
        status = text("Ready", 15f)
        deviceSpinner = Spinner(this); root.addView(deviceSpinner)
        button("Bluetooth settings / pair adapter") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Refresh paired devices") { permissionsAndDevices() }
        start = button("Start vehicle capture") {
            val device = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return@button
            launch(false, device)
        }
        demo = button("Run demo capture (no car)") { launch(true, null) }
        stop = button("Stop and save") { startService(Intent(this, CaptureService::class.java).setAction("STOP")) }
        marker = button("Add timestamp marker") {
            val input = EditText(this).apply { hint = "e.g. engine started, traffic, hill" }
            AlertDialog.Builder(this).setTitle("Session marker").setView(input)
                .setPositiveButton("Mark") { _,_ -> startService(Intent(this, CaptureService::class.java).setAction("MARK").putExtra("label", input.text.toString())) }
                .setNegativeButton("Cancel", null).show()
        }
        share = button("Share a saved session") { chooseSession { f ->
            val uri = Uri.Builder().scheme("content").authority("dev.obdmaru.recorder.logs").appendPath(f.name).build()
            val send = Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri("OBD session", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(send, "Export raw session"))
        } }
        save = button("Save a session to Files") { chooseSession { f ->
            exportFile = f
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, f.name), 20)
        } }
        button("Diagnostic data notices") {
            val notices = assets.open("diagnostic-notices.txt").bufferedReader().use { it.readText() }
            val license = assets.open("OBDb-LICENSE.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("Diagnostic data attribution").setMessage(notices + "\n" + license)
                .setPositiveButton("Close", null).show()
        }
        text("Records adapter/ECU identifiers (possibly VIN), Bluetooth address, and diagnostic replies. Files stay on this phone until exported. Never clears codes or runs actuator tests.", 13f)
        window.setDecorFitsSystemWindows(false)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12,20,28)); isFillViewport = true; addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        state?.getString("export_name")?.let { exportFile = File(File(filesDir, "sessions"), it) }
        permissionsAndDevices()
    }
    private fun permissionsAndDevices() {
        val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else loadDevices()
    }
    @Suppress("MissingPermission") private fun loadDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            devices = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (devices.isEmpty()) listOf("No paired devices — pair in Settings") else devices.map { "${it.name ?: "Unnamed"} · ${it.address}" })
        } catch (e: Exception) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show() }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); loadDevices()
    }
    @Suppress("MissingPermission") private fun launch(demo: Boolean, device: BluetoothDevice?) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { permissionsAndDevices(); return }
        if (!demo && getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_LONG).show(); return
        }
        val intent = Intent(this, CaptureService::class.java).setAction("START").putExtra("demo", demo)
        device?.let { intent.putExtra("address", it.address).putExtra("name", it.name) }
        startForegroundService(intent)
    }
    private fun chooseSession(action: (File) -> Unit) {
        val files = File(filesDir, "sessions").listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.name } ?: emptyList()
        if (files.isEmpty()) { Toast.makeText(this, "No sessions yet", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Saved sessions (including interrupted captures)")
            .setItems(files.map { "${it.name} · ${it.length()/1024} KiB" }.toTypedArray()) { _, i -> action(files[i]) }.show()
    }
    @Deprecated("Platform callback") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val source = exportFile ?: return
            val uri = data?.data ?: return
            thread {
                val message = try {
                    source.inputStream().use { input -> requireNotNull(contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
                    "Session exported"
                } catch (e: Exception) { "Export failed: $e" }
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        exportFile?.let { out.putString("export_name", it.name) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); loadDevices(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
