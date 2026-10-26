package dev.obdmaru.scanner

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.*
import dev.obdmaru.recorder.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ScanSnapshot(val state: String = "Ready", val demo: Boolean = false, val replies: List<ScanReply> = emptyList(), val finished: Boolean = false)

class ScannerService : Service() {
    private var worker: Thread?=null
    @Volatile private var transport: BluetoothElm?=null
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { running=false; transport?.close(); worker?.interrupt(); return START_NOT_STICKY }
        if(running || worker?.isAlive==true || intent==null) return START_NOT_STICKY
        val demo=intent.getBooleanExtra("demo",false)
        val address=intent.getStringExtra("address")?:""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("scan","Fault-code scan",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,ScannerService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(4,Notification.Builder(this,"scan").setContentTitle("OBDMaru Scanner")
            .setContentText("Reading diagnostic information").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build())
        running=true; snapshot=ScanSnapshot("Connecting…",demo)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OBDMaru:scanner").apply { acquire(10*60*1000L) }
        worker=Thread { scan(address,demo) }.apply { name="obd-scanner"; start() }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun scan(address: String, demo: Boolean) {
        var lease: ConnectionLease?=null
        var log: SessionLog?=null
        val replies=mutableListOf<ScanReply>()
        var end="Scan interrupted; remaining queries unknown"
        var completed=false
        try {
            if(!demo) lease=ObdConnectionGuard.acquire(this)
            log=SessionLog(this,address,"Scanner",demo,"scanner-0.1.0")
            lastLog=log.file; lastReport=File(log.file.parentFile,log.file.nameWithoutExtension+".txt")
            log.event("scanner_start","coverage" to "standard emissions OBD-II / CAN 11-bit 500k", "clear_enabled" to false)
            val catalog=DtcCatalog.load(assets.open("dtc-generic.tsv").reader())
            val decoder=ScanDecoder(catalog)
            val elm: ElmTransport=if(demo) ScannerDemo(log) else {
                var connected: BluetoothElm?=null
                var failure: Exception?=null
                for(insecure in listOf(false,true)) {
                    if(!running) throw InterruptedException()
                    val candidate=BluetoothElm(log,requestGapMs=0){running}; transport=candidate
                    try {
                        val adapter=getSystemService(BluetoothManager::class.java).adapter?:throw IOException("Bluetooth unavailable")
                        if(!adapter.isEnabled)throw IOException("Enable Bluetooth first")
                        candidate.connect(adapter.getRemoteDevice(address),insecure)
                        connected=candidate; break
                    } catch(e: Exception) { candidate.close(); failure=e }
                }
                connected?:throw IOException("Bluetooth connection failed",failure)
            }
            for(cmd in ScanPlan.setup) {
                if(!running) throw InterruptedException()
                snapshot=ScanSnapshot("Preparing adapter · $cmd",demo)
                val raw=elm.query(cmd,"scanner_setup")
                if(cmd=="ATZ") {
                    if(!listOf("ELM","OBD","STN").any{raw.uppercase().contains(it)})throw IOException("Adapter did not identify itself")
                } else if(raw.split('\r','\n').none{it.trim()=="OK"})throw IOException("Adapter rejected $cmd; scan stopped")
            }
            ScanRunner(decoder).run(query={ q ->
                snapshot=ScanSnapshot("Reading ${replies.size+1}/${ScanPlan.queries.size} · ${q.title}",demo,replies.toList())
                elm.query(q.command,"scanner_read",8000)
            },active={running},recover={demo || transport?.recoverPrompt()==true}) { reply ->
                replies.add(reply)
                log.event("scanner_result","command" to reply.query.command,"title" to reply.query.title,
                    "findings" to JSONArray(reply.findings.map { f -> JSONObject().put("ecu",f.ecu).put("status",f.status).put("codes",JSONArray(f.codes)).put("lines",JSONArray(f.lines)) }),
                    "problems" to JSONArray(reply.problems))
                // Persist partial results after each completed query; a failed scan remains useful.
                lastReport!!.writeText(ScanReport.render(replies,demo,"Scan in progress / incomplete"))
                snapshot=ScanSnapshot("Read ${replies.size}/${ScanPlan.queries.size}",demo,replies.toList())
            }
            completed=true
            end="Scan finished; inspect each category's coverage below"
        } catch(e: Exception) {
            end=if(!running || e is InterruptedException)"Stopped; remaining queries unknown" else "Scan interrupted: ${e.message}"
            try { log?.event("scanner_error","error" to e.toString()) }catch(_:Exception){}
        } finally {
            transport?.close(); transport=null
            try { lease?.close() }catch(_:Exception){}
            try {
                log?.event("scanner_end","completed" to completed,"queries_completed" to replies.size,"state" to end)
                log?.close(end)
            }catch(e: Exception){ end+=" · Raw-log save failed: ${e.message}" }
            if(log!=null)try { lastReport?.writeText(ScanReport.render(replies,demo,end)) }catch(e: Exception){end+=" · Report save failed: ${e.message}"}
            snapshot=ScanSnapshot(end,demo,replies.toList(),true)
            running=false
            stopSelf()
        }
    }
    override fun onDestroy() {
        running=false; transport?.close(); worker?.interrupt()
        if(wake?.isHeld==true)wake?.release()
        super.onDestroy()
    }
    companion object {
        @Volatile var running=false
        @Volatile var snapshot=ScanSnapshot()
        @Volatile var lastLog: File?=null
        @Volatile var lastReport: File?=null
    }
}

/** Demo runs the real decoder/report path. All responses and exported files are marked synthetic. */
private class ScannerDemo(private val log: SessionLog): ElmTransport {
    override fun query(command: String, phase: String, timeoutMs: Long): String {
        Thread.sleep(90)
        val raw=when(command) {
            "ATZ" -> "ELM327 SCANNER SYNTHETIC DEMO\r>"
            "0101" -> "7E8 06 41 01 82 0F EB 40\r>"
            "03" -> "7E8 06 43 02 04 01 02 99\r7E9 04 43 01 17 01\r>"
            "07" -> "7E8 04 47 01 20 02\r>"
            "0A" -> "7E8 02 4A 00\r>"
            "020200" -> "7E8 05 42 02 00 04 01\r>"
            "020400" -> "7E8 04 42 04 00 80\r>"
            "020500" -> "7E8 04 42 05 00 7D\r>"
            "020B00" -> "7E8 04 42 0B 00 98\r>"
            "020C00" -> "7E8 05 42 0C 00 1F 40\r>"
            "020D00" -> "7E8 04 42 0D 00 3C\r>"
            "020F00" -> "7E8 04 42 0F 00 41\r>"
            "021000" -> "7E8 05 42 10 00 0B B8\r>"
            "021100" -> "7E8 04 42 11 00 30\r>"
            "022300" -> "NO DATA\r>"
            "024200" -> "7E8 05 42 42 00 36 B0\r>"
            else -> "OK\r>"
        }
        log.event("scanner_demo_exchange","command" to command,"raw" to raw,"phase" to phase)
        return raw
    }
    override fun close() {}
}
