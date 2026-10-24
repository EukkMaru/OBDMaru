package dev.obdmaru.dashboard

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.*
import dev.obdmaru.recorder.*
import java.io.File
import kotlin.math.sin

class DashboardService : Service() {
    private var worker: Thread? = null
    @Volatile private var transport: BluetoothElm? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { running = false; transport?.close(); worker?.interrupt(); return START_NOT_STICKY }
        if (running || worker?.isAlive == true) return START_NOT_STICKY
        val demo = intent?.getBooleanExtra("demo", false) ?: false
        val address = intent?.getStringExtra("address") ?: ""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("watch", "Dashboard", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DashboardService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this, "watch").setContentTitle("OBDMaru Dash")
            .setContentText("Experimental telemetry · tap to open").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        running = true; state = DashState(status = "Connecting…", demo = demo)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDMaru:dashboard").apply { acquire(2 * 60 * 60 * 1000L) }
        worker = Thread { capture(address, demo) }.apply { name = "dashboard-poll"; start() }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun capture(address: String, demo: Boolean) {
        var log: SessionLog? = null
        val samples = mutableMapOf<String,Sample>()
        val tasks = listOf(Task("tcm","7E1","2201A4",200),Task("brake","7D1","22C101",200),Task("speed","7DF","010D",1000))
        var currentHeader = ""
        var attempt = 0
        try {
            log = SessionLog(this,address,"Dashboard",demo,"dashboard-0.1.0");lastFile=log.file
            log.event("dashboard_start","mapping" to "U19 b08 pedal /254; U03 b32 brake /140; U19 low nibble b23 gear; U19 b29:31 and b31:33 BE *2-40 provisional temperatures; standard 010D speed")
            while(running) {
                try {
                    if(!demo && transport==null) {
                        state=DashState(status="Connecting…")
                        val elm=BluetoothElm(log){running};transport=elm
                        elm.connect(getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address),attempt++%2==1)
                        for(cmd in listOf("ATZ","ATE0","ATL0","ATS1","ATH1","ATAT1","ATST32","ATSP6")) {
                            val reply=elm.query(cmd,"dashboard_setup")
                            if(cmd!="ATZ" && !reply.contains("OK")) throw java.io.IOException("Setup rejected: $cmd")
                        }
                        currentHeader=""
                    }
                    val start=SystemClock.elapsedRealtime()
                    val task=tasks.maxBy { start-it.due }
                    if(task.due>start) { Thread.sleep(minOf(task.due-start,50));continue }
                    if(!demo && currentHeader!=task.header) {
                        val reply=transport!!.query("ATSH${task.header}","dashboard_setup")
                        if(!reply.contains("OK")) throw java.io.IOException("Header rejected")
                        currentHeader=task.header
                    }
                    val bytes=if(demo) {
                        Thread.sleep(35)
                        demoBytes(task.key,start).also { log.event("dashboard_demo_sample","channel" to task.key,"bytes" to org.json.JSONArray(it.map { v->v.toInt() and 255 })) }
                    } else Protocol.data(transport!!.query(task.command,"dashboard_${task.key}",2500),task.command)
                    val now=SystemClock.elapsedRealtime()
                    val required=when(task.key){"tcm"->34;"brake"->34;else->1}
                    if(bytes==null || bytes.size<required) {
                        samples.remove(task.key);task.times.clear();task.due=now+2000
                        log.event("dashboard_no_sample","channel" to task.key)
                        state=DashState(samples.toMap(),"${task.key}: no valid sample",demo)
                    } else {
                        task.times.addLast(now);while(task.times.size>1 && now-task.times.first()>3000)task.times.removeFirst()
                        val hz=if(task.times.size>1)(task.times.size-1)*1000.0/(now-task.times.first()).coerceAtLeast(1) else 0.0
                        samples[task.key]=Sample(bytes,now,hz);task.due=maxOf(start+task.interval,now)
                        state=DashState(samples.toMap(),"Live",demo)
                    }
                } catch(e: Exception) {
                    if(!running)break
                    log.event("dashboard_error","error" to e.toString())
                    transport?.close();transport=null;currentHeader="";samples.clear();tasks.forEach { it.times.clear() }
                    state=DashState(status="Connection error · retrying",demo=demo)
                    Thread.sleep(2000)
                }
            }
        } catch(e: Exception) {
            if(running)state=DashState(status="Stopped: ${e.message}",demo=demo)
        } finally {
            transport?.close();transport=null
            try { log?.close("dashboard stopped") }catch(_:Exception){}
            state=DashState(status="Stopped · log saved",demo=demo);running=false;stopSelf()
        }
    }
    private data class Task(val key:String,val header:String,val command:String,val interval:Long,var due:Long=0,val times:ArrayDeque<Long> = ArrayDeque())
    private fun demoBytes(key:String,now:Long):ByteArray {
        val phase=now/1000.0
        val gas=((sin(phase*.8)+1)*.5)
        val brake=((sin(phase*.8+3)+.65).coerceAtLeast(0.0)/1.65)
        return when(key) {
            "tcm"->ByteArray(34).apply {
                this[7]=(32+gas*213).toInt().toByte();this[8]=(gas*254).toInt().toByte()
                this[21]=8;this[22]=7;this[23]=(1+(now/4000%7).toInt()).toByte();this[24]=this[23]
                this[26]=if(brake>.1)1 else 0;this[30]=(70+sin(phase*.07)*8).toInt().toByte();this[32]=(75+sin(phase*.09)*9).toInt().toByte()
            }
            "brake"->ByteArray(39).apply {this[32]=(brake*168).toInt().toByte();this[33]=(brake*162).toInt().toByte()}
            else->byteArrayOf((45+35*sin(phase*.2)).toInt().toByte())
        }
    }
    override fun onDestroy() {
        running = false; transport?.close(); worker?.interrupt()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
    companion object {
        @Volatile var running=false
        @Volatile var lastFile:File?=null
        @Volatile var state=DashState()
    }
}
