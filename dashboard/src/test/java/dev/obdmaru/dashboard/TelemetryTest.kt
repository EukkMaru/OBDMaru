package dev.obdmaru.dashboard
import org.junit.Assert.*
import org.junit.Test
class TelemetryTest {
 @Test fun pedalCalibrationAndBrakeOverflow() {
  val t=ByteArray(34);t[7]=32;t[8]=0
  assertEquals(0.0,Telemetry.transmission(t).gas!!,0.001); assertEquals(0.0,Telemetry.transmission(t).gasAlt!!,0.001)
  t[7]=245.toByte();t[8]=254.toByte()
  assertEquals(100.0,Telemetry.transmission(t).gas!!,0.001);assertEquals(100.0,Telemetry.transmission(t).gasAlt!!,0.001)
  val b=ByteArray(39);b[32]=168.toByte();b[33]=140.toByte()
  assertEquals(105.830052,Telemetry.brake(b).percent!!,0.001);assertEquals(140,Telemetry.brake(b).raw33)
 }
 @Test fun temperatureUsesBothBytesAndGearUsesLowNibble() {
  val t=ByteArray(34);t[21]=8;t[23]=0xA7.toByte();t[24]=0xB6.toByte();t[30]=70;t[32]=75
  val result=Telemetry.transmission(t)
  assertEquals(100.0,result.clutch1!!,0.001);assertEquals(110.0,result.clutch2!!,0.001)
  assertEquals("7",Telemetry.gear(result));assertEquals(6,result.nextGear)
  t[29]=1;t[30]=0;assertNull(Telemetry.transmission(t).clutch1)
 }
 @Test fun incompleteAndStaleSamplesNeverBecomeZero() {
  assertNull(Telemetry.brake(ByteArray(2)).percent);assertNull(Telemetry.transmission(ByteArray(3)).gas)
  assertFalse(Telemetry.fresh(100,1700,1500));assertTrue(Telemetry.fresh(100,1600,1500));assertFalse(Telemetry.fresh(0,100,1500))
 }

 @Test fun brakeCurveHasSteepLowResponseCompressesHighInputsAndRetainsRaw() {
  fun display(raw:Int):Double {
   val b=ByteArray(39);b[32]=raw.toByte();b[33]=7
   val decoded=Telemetry.brake(b)
   assertEquals(raw,decoded.raw32);assertEquals(7,decoded.raw33)
   return decoded.percent!!
  }
  assertEquals(0.0,display(0),0.0001)
  assertEquals(20.0,display(6),0.0001);assertEquals(40.0,display(24),0.0001)
  assertEquals(60.0,display(54),0.0001);assertEquals(80.0,display(96),0.0001)
  assertEquals(100.0,display(150),0.0001);assertTrue(display(255)>100)
  assertTrue(display(40)-display(0)>display(80)-display(40))
  assertTrue((1..255).all{display(it)>display(it-1)})
 }

 @Test fun standardEngineRpmUsesBothUnsignedBytesAndQuarterRpmUnits() {
  assertEquals(1726.0,Telemetry.rpm(byteArrayOf(0x1A,0xF8.toByte()))!!,0.001)
  assertEquals(0.0,Telemetry.rpm(byteArrayOf(0,0))!!,0.001)
  assertEquals(16383.75,Telemetry.rpm(byteArrayOf(-1,-1))!!,0.001)
  assertNull(Telemetry.rpm(byteArrayOf(1)))
 }
 @Test fun indicatorDefinitionUsesMsbOffsetsAndKeepsUnavailableSeparateFromOff() {
  fun lights(raw:Int)=Telemetry.indicators(ByteArray(5).apply{this[4]=raw.toByte()})!!
  assertFalse(lights(0).left);assertFalse(lights(0).right)
  assertTrue(lights(2).left);assertFalse(lights(2).right)
  assertFalse(lights(4).left);assertTrue(lights(4).right)
  assertTrue(lights(6).left);assertTrue(lights(6).right)
  assertFalse(lights(0xC0).left);assertFalse(lights(0xC0).right)
  assertEquals(6,lights(6).raw4);assertNull(Telemetry.indicators(ByteArray(4)))
 }
}
