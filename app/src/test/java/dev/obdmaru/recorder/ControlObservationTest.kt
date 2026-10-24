package dev.obdmaru.recorder
import org.junit.Assert.*
import org.junit.Test
class ControlObservationTest {
 @Test fun candidatesKeepRawIndependentValuesAndDoNotInventCruiseOrParkingBrake(){
  val bytes=ByteArray(34);bytes[7]=245.toByte();bytes[8]=254.toByte();bytes[21]=8;bytes[22]=10;bytes[23]=0xA7.toByte();bytes[26]=3
  val f=ControlObservation.fields("7E1","2201A4",bytes)!!
  assertEquals(245,f["b07"]);assertEquals(254,f["b08"]);assertEquals(7,f["gear_low_nibble_candidate"])
  assertEquals(1,f["brake_bit0_candidate"]);assertFalse(f.keys.any{it.contains("cruise")||it.contains("parking")})
  val brake=ByteArray(39);brake[32]=9;brake[33]=7
  val b=ControlObservation.fields("7D1","22C101",brake)!!
  assertEquals(9,b["b32"]);assertEquals(7,b["b33"])
 }
 @Test fun incompleteAndWrongRequestsCannotCreateControlStates(){
  assertTrue(ControlObservation.fields("7E1","2201A4",ByteArray(1))!!.isEmpty())
  assertNull(ControlObservation.fields("7E1","2201A0",ByteArray(34)))
 }
}
