package dev.obdmaru.dashboard
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.util.Locale

class DashView(context: Context):View(context) {
 private val p=Paint(Paint.ANTI_ALIAS_FLAG)
 private val white=Color.rgb(235,241,248);private val muted=Color.rgb(145,164,184)
 private val green=Color.rgb(58,224,153);private val red=Color.rgb(255,87,101);private val cyan=Color.rgb(67,205,227)
 private val panel=Color.rgb(24,34,47)
 private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int=white,bold:Boolean=false) {p.color=color;p.textSize=size;p.typeface=if(bold)Typeface.create("sans-serif",Typeface.BOLD)else Typeface.create("sans-serif",Typeface.NORMAL);c.drawText(s,x,y,p)}
 private fun box(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int) {p.color=color;c.drawRoundRect(x,y,x+w,y+h,10f,10f,p)}
 private fun number(x:Double?)=x?.let {String.format(Locale.US,"%.0f",it)}?:"—"
 private fun arrow(c:Canvas,x:Float,y:Float,left:Boolean,on:Boolean?) {
  val path=Path();val points=listOf(0f to 10f,12f to 0f,12f to 6f,28f to 6f,28f to 14f,12f to 14f,12f to 20f)
  points.forEachIndexed{i,(a,b)->val px=x+if(left)a else 28-a;if(i==0)path.moveTo(px,y+b)else path.lineTo(px,y+b)};path.close()
  p.color=when(on){true->green;false->Color.rgb(61,80,98);else->muted};c.drawPath(path,p)
  if(on==null)text(c,"?",x+10,y+14,12f,panel,true)
 }
 override fun onDraw(c:Canvas) {
  val scale=minOf(width/360f,height/610f);c.save();c.translate((width-360*scale)/2,0f);c.scale(scale,scale)
  val state=DashboardService.state;val now=SystemClock.elapsedRealtime()
  fun sample(key:String,age:Long)=state.samples[key]?.takeIf { DashboardService.running && Telemetry.fresh(it.at,now,age) }
  val ts=sample("tcm",1500);val bs=sample("brake",1500);val ss=sample("speed",1500)
  val rs=sample("rpm",1500);val ins=sample("indicators",1500)
  val cs=sample("clutch",2500);val ct=cs?.let {Telemetry.transmission(it.bytes)}
  val t=ts?.let {Telemetry.transmission(it.bytes)};val b=bs?.let {Telemetry.brake(it.bytes)}
  val signals=ins?.let{Telemetry.indicators(it.bytes)}
  text(c,if(state.demo)"SYNTHETIC DEMO" else "LIVE TELEMETRY · EXPERIMENTAL",12f,22f,12f,cyan,true)
  text(c,"GEAR",12f,50f,12f,muted);text(c,"SPEED",190f,50f,12f,muted)
  text(c,Telemetry.gear(t),10f,143f,96f,white,true)
  text(c,ss?.bytes?.let {Telemetry.u(it,0)}?.toString()?:"—",184f,132f,64f,white,true)
  text(c,"km/h · standard OBD",190f,155f,12f,muted)
  val mode=when(t?.mode){1->"P";3->"R";5->"N";7->"D";10->"M";null->"—";else->"?"}
  text(c,"Mode $mode · b24 ${t?.nextGear?:"—"} (uncertain)",12f,174f,12f,muted)
  box(c,12f,183f,336f,63f,panel)
  text(c,"ENGINE RPM",24f,200f,10f,cyan,true)
  text(c,number(rs?.let{Telemetry.rpm(it.bytes)}),24f,232f,29f,white,true)
  text(c,"OBD 010C",125f,232f,10f,muted)
  text(c,"TURN · candidate",218f,200f,10f,cyan,true)
  arrow(c,222f,206f,true,signals?.left);arrow(c,304f,206f,false,signals?.right)
  text(c,signals?.let{String.format(Locale.US,"L / R · raw b04 %02X",it.raw4)}?:"L / R · unavailable",218f,241f,9f,muted)
  fun pedal(x:Float,title:String,value:Double?,color:Int,detail:String) {
   box(c,x,258f,162f,127f,panel);text(c,title,x+12,282f,13f,color,true)
   text(c,"${number(value)}${if(value!=null)"%" else ""}",x+12,325f,37f,white,true)
   box(c,x+12,341f,138f,13f,Color.rgb(43,56,72))
   if(value!=null && value>0)box(c,x+12,341f,138f*(value.coerceIn(0.0,100.0)/100).toFloat(),13f,color)
   text(c,detail,x+12,373f,10f,muted)
  }
  pedal(12f,"THROTTLE · candidate",t?.gas,green,"b08 ${t?.rawGas8?:"—"} · b07 ${t?.rawGas7?:"—"}")
  pedal(186f,"BRAKE · relative",b?.percent,red,"b32 ${b?.raw32?:"—"} · b33 ${b?.raw33?:"—"}")
  text(c,"Brake: √ response · raw 150 = 100%; values can exceed 100%",12f,405f,10f,muted)
  text(c,"b07 pedal: ${number(t?.gasAlt)}% · brake switch: ${when(t?.brakeSwitch){true->"ON";false->"OFF";else->"—"}}",12f,422f,11f,muted)
  fun clutch(x:Float,title:String,value:Double?) {
   box(c,x,438f,162f,90f,panel);text(c,title,x+12,460f,12f,cyan,true)
   text(c,"${number(value)}${if(value!=null)"°C" else ""}",x+12,499f,34f,white,true)
   text(c,"Provisional OBDb mapping",x+12,517f,10f,muted)
  }
  clutch(12f,"CLUTCH 1",ct?.clutch1);clutch(186f,"CLUTCH 2",ct?.clutch2)
  fun stat(name:String,s:Sample?)=if(s==null)"$name —"else String.format(Locale.US,"%s %.1fHz %dms",name,s.hz,now-s.at)
  text(c,stat("U19",ts)+" · "+stat("U03",bs),12f,549f,10f,muted)
  text(c,stat("Speed",ss)+" · "+stat("RPM",rs),12f,566f,10f,muted)
  text(c,stat("Clutch",cs)+" · "+stat("Turn",ins),12f,583f,10f,muted)
  text(c,state.status.take(55),12f,604f,11f,cyan)
  c.restore()
 }
}
