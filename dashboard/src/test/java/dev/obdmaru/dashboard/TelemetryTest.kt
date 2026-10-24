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
  assertEquals(120.0,Telemetry.brake(b).percent!!,0.001);assertEquals(140,Telemetry.brake(b).raw33)
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
}
