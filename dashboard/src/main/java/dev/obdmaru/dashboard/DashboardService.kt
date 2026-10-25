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
        var lease: ConnectionLease? = null
        var poller: DashboardPolling? = null
        var finalStatus = "Stopped · log saved"
        var attempt = 0
        try {
            if (!demo) lease = ObdConnectionGuard.acquire(this)
            log = SessionLog(this,address,"Dashboard",demo,"dashboard-0.1.4");lastFile=log.file
            val session = log
            log.event("obd_connection_guard", "exclusive" to !demo, "demo" to demo)
            log.event("dashboard_start","tcm_target_hz" to 10,"brake_target_hz" to 10,"speed_target_hz" to 3,"clutch_display_hz" to 1,
                "brake_display_curve" to "square_root", "brake_raw_reference" to 150, "brake_display_exponent" to 0.5,
                "mapping" to "U19 b08 pedal /254; U03 b32 brake display sqrt(raw/150)*100; U19 low nibble b23 gear; U19 b29:31 and b31:33 BE *2-40 provisional temperatures; standard 010D speed")
            poller = DashboardPolling(
                open = {
                    if(demo) demoLink(session) else {
                        val elm=BluetoothElm(session){running};transport=elm
                        try {
                            elm.connect(getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address),attempt++%2==1)
                            object: DashboardLink {
                                override fun query(command:String,phase:String,timeoutMs:Long)=elm.query(command,phase,timeoutMs)
                                override fun recover()=elm.recoverPrompt()
                                override fun close(){elm.close();if(transport===elm)transport=null}
                            }
                        }catch(e:Exception){elm.close();transport=null;throw e}
                    }
                },
                now = {SystemClock.elapsedRealtime()},sleep = {Thread.sleep(it)},publish = {state=it},
                event = {type,fields->session.event(type,*fields.map{it.key to it.value}.toTypedArray())},demo = demo)
            while(running)poller.step()
        }catch(e:Exception){
            if(e is ObdGuardException)Handler(Looper.getMainLooper()).post{android.widget.Toast.makeText(this,e.message,android.widget.Toast.LENGTH_LONG).show()}
            if(running){finalStatus="Stopped: ${e.message}";try{log?.event("dashboard_fatal","error" to e.toString())}catch(_:Exception){}}
        }finally{
            try{poller?.close()}catch(_:Exception){}
            transport?.close();transport=null
            lease?.close();lease=null
            try{log?.close("dashboard stopped")}catch(_:Exception){}
            state=DashState(status=finalStatus,demo=demo);running=false;stopSelf()
        }
    }
    private fun demoLink(log:SessionLog)=object:DashboardLink {
        override fun query(command:String,phase:String,timeoutMs:Long):String {
            if(command.startsWith("AT"))return if(command=="ATZ")"ELM327 DEMO\r>"else "OK\r>"
            Thread.sleep(35)
            val logical=command.take(if(command.startsWith("01"))4 else 6)
            val key=when(logical){"2201A4"->"tcm";"22C101"->"brake";else->"speed"}
            val bytes=demoBytes(key,SystemClock.elapsedRealtime())
            log.event("dashboard_demo_sample","channel" to key,"bytes" to org.json.JSONArray(bytes.map{it.toInt() and 255}))
            val prefix=("%02X".format(logical.take(2).toInt(16)+64)+logical.drop(2)).chunked(2).map{it.toInt(16)}
            val data=prefix+bytes.map{it.toInt() and 255}
            val id=when(key){"tcm"->"7E9";"brake"->"7D9";else->"7E8"}
            val frames=if(data.size<=7)listOf(listOf(data.size)+data)else {
                listOf(listOf(0x10 or (data.size shr 8),data.size and 255)+data.take(6))+data.drop(6).chunked(7).mapIndexed{i,b->listOf(0x20 or ((i+1)and 15))+b}
            }
            return frames.joinToString("\r"){id+" "+it.joinToString(" "){v->"%02X".format(v)}}+"\r>"
        }
        override fun recover()=true
        override fun close(){}
    }
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
