package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Invoke seed and verify in separate force-stopped processes, never in one suite. */
class ColdRestartTest {
    @org.junit.Before fun separateProcessesOnly(){org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cold_restart")=="true")}
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val store get()=(context.applicationContext as EzanoApp).store
    private fun ready() {val until=System.currentTimeMillis()+120000;while(!store.ready.value && System.currentTimeMillis()<until)Thread.sleep(100);assertTrue(store.error.value,store.ready.value)}
    @Test fun seed() {
        ready()
        val channels=CompactCatalog.Builder()
        val random=java.util.Random(451)
        repeat(76000) {i->
            val entropy=ByteArray(100).also {random.nextBytes(it)}.joinToString(""){"%02x".format(it)}
            channels.add(Channel("cold:$i","cold","$i","EN Movie $i","http://10.0.2.2:8765/movie/test/test/1.mp4",Kind.MOVIE,"EN Movies",logo="https://example.com/$entropy.jpg",description=entropy,language="en",certification="PG",genre="Family"))
        }
        val guide=CompactGuide.Builder();val now=System.currentTimeMillis()
        repeat(64000){i->guide.add(Program("cold-epg:$i","cold:${i%100}","Program $i",now+i*60000L,now+(i+1)*60000L,"Saved guide description $i"))}
        store.update {Library(providers=listOf(Provider(id="cold",name="Cold restart fixture",url="http://10.0.2.2:8765",user="test",password="test")),channels=channels.build(),programs=guide.build(),favorites=setOf("cold:75999"),positions=mapOf("cold:75999" to 123456L),prefs=Prefs(guideHours=168,autoSubtitles=false))}
        assertTrue(runBlocking {store.flush()})
        File(context.filesDir,"cold-seed.txt").writeText("${android.os.Process.myPid()}")
        println("COLD_SEED heap=${Runtime.getRuntime().maxMemory()} vault=${File(context.filesDir,"library.vault").length()}")
    }
    @Test fun verify() {
        val start=android.os.SystemClock.elapsedRealtime();ready()
        val loadedMs=android.os.SystemClock.elapsedRealtime()-start
        assertNotEquals(File(context.filesDir,"cold-seed.txt").readText(),"${android.os.Process.myPid()}")
        assertEquals(76000,store.state.value.channels.size);assertEquals(64000,store.state.value.programs.size)
        assertEquals(setOf("cold:75999"),store.state.value.favorites);assertEquals(123456L,store.state.value.positions["cold:75999"])
        assertEquals("EN Movie 75999",store.state.value.channels.last().name)
        assertTrue(runBlocking {store.flush()})
        assertEquals("EZV3",File(context.filesDir,"library.vault").inputStream().use {input->val header=ByteArray(4);java.io.DataInputStream(input).readFully(header);String(header)})
        File(context.filesDir,"library.vault").copyTo(File(context.filesDir,"cold-good.vault"),overwrite=true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("Live TV")),20000))
        }
        println("COLD_REOPEN loadedMs=$loadedMs totalMs=${android.os.SystemClock.elapsedRealtime()-start} pid=${android.os.Process.myPid()} heap=${Runtime.getRuntime().maxMemory()}")
    }
    @Test fun corruptSavedVault(){
        ready();assertTrue(runBlocking {store.flush()})
        val vault=File(context.filesDir,"library.vault");vault.copyTo(File(context.filesDir,"cold-good.vault"),overwrite=true)
        java.io.RandomAccessFile(vault,"rw").use {it.seek(vault.length()-1);val last=it.read();it.seek(vault.length()-1);it.write(last xor 1)}
    }
    @Test fun recoveryPreservesDamagedDataAndCanRetry(){
        val deadline=System.currentTimeMillis()+120000;while(store.opening.value && System.currentTimeMillis()<deadline)Thread.sleep(100)
        assertFalse(store.opening.value);assertFalse(store.ready.value);assertTrue(store.error.value.isNotBlank())
        val vault=File(context.filesDir,"library.vault");val before=vault.readBytes()
        store.update {Library()};assertFalse(runBlocking {store.flush()});assertArrayEquals(before,vault.readBytes())
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("Retry")),10000));device.takeScreenshot(File(context.filesDir,"recovery-031.png"))
            File(context.filesDir,"cold-good.vault").copyTo(vault,overwrite=true)
            scenario.onActivity {it.store.retryOpening()};ready()
            assertEquals(76000,store.state.value.channels.size);assertEquals(64000,store.state.value.programs.size)
            assertEquals(setOf("cold:75999"),store.state.value.favorites);assertEquals(123456L,store.state.value.positions["cold:75999"])
            assertTrue(device.wait(Until.hasObject(By.text("Live TV")),20000))
        }
    }
}
