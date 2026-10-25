package dev.obdmaru.dashboard
import org.junit.Assert.*
import org.junit.Test
class ReplyHintTest {
 private val one="7E8 03 41 0D 32\r>"
 @Test fun threeCompleteReadsRequiredAndRejectedHintsStayDisabled(){
  val hint=ReplyHint()
  repeat(2){hint.observe(one,"010D");assertEquals("010D",hint.command("010D"))}
  hint.observe(one,"010D");assertEquals("010D1",hint.command("010D"))
  hint.reject();repeat(5){hint.observe(one,"010D")};assertEquals("010D",hint.command("010D"))
  hint.reset();repeat(3){hint.observe(one,"010D")};assertEquals("010D1",hint.command("010D"))
 }
 @Test fun multipleEcusAndIncompleteRepliesNeverLearnACount(){
  val hint=ReplyHint()
  repeat(5){hint.observe(one.replace("\r>","\r7E9 03 41 0D 33\r>"),"010D")}
  assertEquals("010D",hint.command("010D"))
  repeat(5){hint.observe("7E9 10 25 62 01 A4 00 00 00\r>","2201A4")}
  assertEquals("2201A4",hint.command("2201A4"))
 }
 @Test fun periodicallyChecksAnUnrestrictedReply(){
  val hint=ReplyHint();repeat(30){hint.observe(one,"010D")}
  assertEquals("010D",hint.command("010D"))
  hint.observe(one,"010D");assertEquals("010D1",hint.command("010D"))
 }
 @Test fun learnsCompactCombinedReplyOnlyAfterCompletePidValidation() {
  val hint=ReplyHint();val raw="7E806410C1AF80D32\r>";val pids=mapOf(0x0C to 2,0x0D to 1)
  repeat(3){hint.observe(raw,"010C0D",dev.obdmaru.recorder.Protocol.mode01Values(raw,pids)!=null)}
  assertEquals("010C0D1",hint.command("010C0D"))
 }

}
