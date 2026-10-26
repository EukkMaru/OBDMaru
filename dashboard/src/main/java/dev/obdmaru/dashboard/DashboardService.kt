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
        if (intent?.action == "stop" && blinkerReady) { blinkerStage=BlinkerTest.STAGES.size; return START_NOT_STICKY }
        if (intent?.action == "stop") { running = false; transport?.close(); worker?.interrupt(); return START_NOT_STICKY }
        if (running || worker?.isAlive == true) return START_NOT_STICKY
        val demo = intent?.getBooleanExtra("demo", false) ?: false
        val benchmark = intent?.getBooleanExtra("benchmark", false) ?: false
        val blinker = intent?.getBooleanExtra("blinker", false) ?: false
        val address = intent?.getStringExtra("address") ?: ""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("watch", "Dashboard", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DashboardService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this, "watch").setContentTitle("OBDMaru Dash")
            .setContentText("Experimental telemetry · tap to open").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        blinkerStage=0;blinkerReady=false;blinkerMode=blinker
        running = true; benchmarkProgress=if(benchmark)"Connecting for parked benchmark…"else ""; state = DashState(status = "Connecting…", demo = demo)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDMaru:dashboard").apply { acquire(2 * 60 * 60 * 1000L) }
        worker = Thread { capture(address, demo, benchmark, blinker) }.apply { name = "dashboard-poll"; start() }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun capture(address: String, demo: Boolean, benchmark:Boolean, blinker:Boolean) {
        var log: SessionLog? = null
        var lease: ConnectionLease? = null
        var poller: DashboardPolling? = null
        var benchmarkLink:DashboardLink? = null
        var finalStatus = "Stopped · log saved"
        var attempt = 0
        try {
            if (!demo) lease = ObdConnectionGuard.acquire(this)
            log = SessionLog(this,address,if(blinker)"Parked blinker test"else if(benchmark)"Parked bottleneck test"else "Dashboard",demo,"dashboard-0.1.14",detailedTiming=benchmark);lastFile=log.file
            val session = log
            log.event("obd_connection_guard", "exclusive" to !demo, "demo" to demo)
            log.event("dashboard_start","capture_mode" to (if(blinker)"blinker_monitor"else if(benchmark)"benchmark"else "dashboard"),"normal_polling_active" to (!blinker && !benchmark),"tcm_target_hz" to 10,"brake_target_hz" to 10,"speed_target_hz" to 3,"clutch_display_hz" to 1,
                "rpm_target_hz" to 5, "indicators_target_hz" to 2, "indicators_candidate" to true,
                "coolant_target_hz" to 1, "coolant_adaptive_intervals_ms" to "1000,2000,5000",
                "coolant_stale_ms" to DashboardPolling.COOLANT_MAX_AGE_MS,
                "coolant_background_budget" to true, "coolant_max_defer_ms" to 2000,
                "request_gap_ms" to 0, "compact_output_probe" to true, "engine_batch_probe" to "010C0D",
                "indicator_max_defer_ms" to 1500,"manual_display_gear" to "U19 b24 selected/requested candidate",
                "display_layout" to "circular_level_gear_strip", "rpm_fill_reference" to 6000,
                "rpm_fill_mapping" to "linear_vertical_level",
                "brake_display_curve" to "square_root", "brake_raw_reference" to 150, "brake_display_exponent" to 0.5,
                "mapping" to "U19 b08 effective demand candidate /254; U03 b32 brake display sqrt(raw/150)*100; U19 low nibble b23 D gear, b24 M selected/requested gear candidate; U19 b29:31 and b31:33 BE *2-40 provisional temperatures; standard 010D speed; standard 010C RPM /4; standard 0105 coolant A-40 Celsius; indicator candidate 770/22BC08 b04 masks 02 left,04 right")
            val openLink:()->DashboardLink = {
                    if(demo) demoLink(session) else {
                        val elm=BluetoothElm(session,requestGapMs=0,detailedTiming=benchmark){running};transport=elm
                        try {
                            elm.connect(getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address),attempt++%2==1)
                            object: DashboardLink {
                                override fun query(command:String,phase:String,timeoutMs:Long)=elm.query(command,phase,timeoutMs)
                                override fun recover()=elm.recoverPrompt()
                                override fun close(){elm.close();if(transport===elm)transport=null}
                            }
                        }catch(e:Exception){elm.close();transport=null;throw e}
                    }
                }
            if(blinker) {
                if(demo)throw java.io.IOException("Blinker test requires a real adapter")
                val elm=BluetoothElm(session,requestGapMs=0){running};transport=elm
                elm.connect(getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address),false)
                finalStatus=BlinkerTest(object:BlinkerLink {
                    override fun query(command:String)=elm.query(command,"blinker_setup_or_reset",6000)
                    override fun monitor(active:()->Boolean,tick:()->Unit,frame:(ByteArray)->Unit)=elm.monitorCan541(active,tick,frame)
                },{SystemClock.elapsedRealtime()},{blinkerStage},
                    {type,fields->session.event(type,*fields.map{it.key to it.value}.toTypedArray())},
                    {benchmarkProgress=it},{blinkerReady=it}).run()
                benchmarkProgress=finalStatus
                return
            }
            if(benchmark) {
                val limit=SystemClock.elapsedRealtime()+12*60*1000L
                fun active()=running && SystemClock.elapsedRealtime()<limit
                benchmarkLink=openLink()
                BenchmarkPlan(benchmarkLink,{SystemClock.elapsedRealtimeNanos()},{Thread.sleep(it)},::active,
                    {type,fields->session.event(type,*fields.map{it.key to it.value}.toTypedArray())},
                    {benchmarkProgress=it;state=DashState(status=it)}).run()
                benchmarkProgress="Final test: normal dashboard scheduler · 45 seconds"
                session.event("benchmark_phase_start","id" to "scheduler","label" to "Normal dashboard scheduler, 45 seconds")
                // The regular scheduler runs unchanged, on this same instrumented connection.
                var reuse:DashboardLink?=benchmarkLink
                poller=DashboardPolling(open={reuse?.also{reuse=null}?:openLink()},now={SystemClock.elapsedRealtime()},sleep={Thread.sleep(it)},publish={state=it},
                    event={type,fields->session.event(type,*fields.map{it.key to it.value}.toTypedArray())})
                val end=SystemClock.elapsedRealtime()+45000
                while(active() && SystemClock.elapsedRealtime()<end) {
                    poller.step()
                    if(state.samples["speed"]?.bytes?.firstOrNull()?.let{(it.toInt() and 255)>0}==true)
                        throw java.io.IOException("Vehicle is moving; parked benchmark stopped")
                }
                if(!active())throw InterruptedException("Benchmark stopped or reached its 12-minute limit")
                session.event("benchmark_phase_end","id" to "scheduler","completed" to true)
                session.event("benchmark_complete")
                finalStatus="Benchmark complete · log saved"
                benchmarkProgress=finalStatus
                return
            }
            poller = DashboardPolling(
                open = openLink,
                now = {SystemClock.elapsedRealtime()},sleep = {Thread.sleep(it)},publish = {state=it},
                event = {type,fields->session.event(type,*fields.map{it.key to it.value}.toTypedArray())},demo = demo)
            while(running)poller.step()
        }catch(e:Exception){
            if(e is ObdGuardException)Handler(Looper.getMainLooper()).post{android.widget.Toast.makeText(this,e.message,android.widget.Toast.LENGTH_LONG).show()}
            if(running){finalStatus="Stopped: ${e.message}";try{log?.event("dashboard_fatal","error" to e.toString())}catch(_:Exception){}}
        }finally{
            try{poller?.close()}catch(_:Exception){}
            try{benchmarkLink?.close()}catch(_:Exception){}
            transport?.close();transport=null
            lease?.close();lease=null
            try{log?.close(if(benchmark || blinker)finalStatus else "dashboard stopped")}catch(e:Exception){finalStatus="Log finalization failed: ${e.message}"}
            if(blinker)finalStatus+="\nReturn indicators and hazards to OFF. Share the log."
            state=DashState(status=finalStatus,demo=demo);running=false;stopSelf()
            if(benchmark || blinker)benchmarkProgress=finalStatus
            blinkerReady=false
        }
    }
    private fun demoLink(log:SessionLog)=object:DashboardLink {
        override fun query(command:String,phase:String,timeoutMs:Long):String {
            if(command.startsWith("AT"))return if(command=="ATZ")"ELM327 DEMO\r>"else "OK\r>"
            Thread.sleep(35)
            if(command.startsWith("010C0D")) {
                val at=SystemClock.elapsedRealtime();val rpm=demoBytes("rpm",at);val speed=demoBytes("speed",at)
                for((key,bytes) in listOf("rpm" to rpm,"speed" to speed))log.event("dashboard_demo_sample","channel" to key,"bytes" to org.json.JSONArray(bytes.map{it.toInt() and 255}))
                return "7E8 06 41 0C %02X %02X 0D %02X\r>".format(rpm[0].toInt() and 255,rpm[1].toInt() and 255,speed[0].toInt() and 255)
            }
            val logical=command.take(if(command.startsWith("01"))4 else 6)
            val key=when(logical){"2201A4"->"tcm";"22C101"->"brake";"010C"->"rpm";"0105"->"coolant";"22BC08"->"indicators";else->"speed"}
            val bytes=demoBytes(key,SystemClock.elapsedRealtime())
            log.event("dashboard_demo_sample","channel" to key,"bytes" to org.json.JSONArray(bytes.map{it.toInt() and 255}))
            val prefix=("%02X".format(logical.take(2).toInt(16)+64)+logical.drop(2)).chunked(2).map{it.toInt(16)}
            val data=prefix+bytes.map{it.toInt() and 255}
            val id=when(key){"tcm"->"7E9";"brake"->"7D9";"indicators"->"778";else->"7E8"}
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
                val gear=1+(now/4000%7).toInt()
                this[21]=8;this[22]=if(now/12000%2==0L)7 else 10;this[23]=gear.toByte()
                this[24]=(if(gear==7)6 else gear+1).toByte()
                this[26]=if(brake>.1)1 else 0;this[30]=(70+sin(phase*.07)*8).toInt().toByte();this[32]=(75+sin(phase*.09)*9).toInt().toByte()
            }
            "brake"->ByteArray(39).apply {this[32]=(brake*168).toInt().toByte();this[33]=(brake*162).toInt().toByte()}
            "rpm"->{val raw=((850+gas*2700)*4).toInt();byteArrayOf((raw shr 8).toByte(),raw.toByte())}
            "coolant"->byteArrayOf((87+sin(phase*.04)*4+40).toInt().toByte())
            "indicators"->ByteArray(5).apply{this[4]=if(now/500%2==0L)when(now/4000%3){0L->2;1L->4;else->6}else 0}
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
        @Volatile var benchmarkProgress=""
        @Volatile var blinkerStage=0
        @Volatile var blinkerReady=false
        @Volatile var blinkerMode=false
        @Volatile var lastFile:File?=null
        @Volatile var state=DashState()
    }
}
