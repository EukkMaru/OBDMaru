package dev.obdmaru.recorder
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConnectionLeaseTest {
 private class Kernel {
  val held=mutableSetOf<String>()
  @Synchronized fun bind(name:String):Closeable {
   if(!held.add(name))throw LeaseNameBusy()
   return Closeable{synchronized(this){held.remove(name)}}
  }
 }
 @Test fun allThreeAppsExcludeEachOtherAndNameTheActiveOwner(){
  for(owner in ConnectionLease.owners){
   val k=Kernel();val lease=ConnectionLease.acquire(owner,k::bind)
   for(other in ConnectionLease.owners.filter{it!=owner}){
    try{ConnectionLease.acquire(other,k::bind);fail("Second owner acquired")}catch(e:ObdGuardException){assertTrue(e.message!!.startsWith("$owner is using OBD"))}
   }
   lease.close();assertTrue(k.held.isEmpty())
   ConnectionLease.acquire("Dash",k::bind).use{}
   assertTrue(k.held.isEmpty())
  }
 }
 @Test fun simultaneousStartsHaveOnlyOneWinner(){
  val k=Kernel();val start=CountDownLatch(1);val finish=CountDownLatch(1)
  val pool=Executors.newFixedThreadPool(3)
  try{
   val jobs=ConnectionLease.owners.map{owner->pool.submit<Boolean>{start.await();try{ConnectionLease.acquire(owner,k::bind).use{finish.await(3,TimeUnit.SECONDS)};true}catch(_:ObdGuardException){false}}}
   start.countDown()
   val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(jobs.count{it.isDone}<2 && System.nanoTime()<end)Thread.yield()
   assertEquals(2,jobs.count{it.isDone})
   finish.countDown();assertEquals(1,jobs.count{it.get(3,TimeUnit.SECONDS)})
   assertTrue(k.held.isEmpty())
  }finally{finish.countDown();pool.shutdownNow()}
 }
 @Test fun failedBindNeverFallsThroughAndCleansItsMarker(){
  val k=Kernel()
  try{ConnectionLease.acquire("Dash"){name->if(name==ConnectionLease.NAME)throw IOException("Permission denied")else k.bind(name)};fail("Guard failed open")}
  catch(e:ObdGuardException){assertTrue(e.message!!.contains("blocked"))}
  assertTrue(k.held.isEmpty())
 }
 @Test fun oldLeaseCloseCannotReleaseNewOwnerAndCloseIsIdempotent(){
  val k=Kernel();val first=ConnectionLease.acquire("Magic",k::bind);first.close()
  val second=ConnectionLease.acquire("Watcher",k::bind);first.close()
  try{ConnectionLease.acquire("Dash",k::bind);fail("Released another owner")}
  catch(e:ObdGuardException){assertTrue(e.message!!.startsWith("Watcher"))}
  second.close();assertTrue(k.held.isEmpty())
 }
}
