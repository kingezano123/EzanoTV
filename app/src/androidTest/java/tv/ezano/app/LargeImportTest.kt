package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LargeImportTest {
    private fun waitForGuideIdle(store: Store) {val deadline=System.currentTimeMillis()+120000;while(store.guideBusy.value && System.currentTimeMillis()<deadline)Thread.sleep(100);assertFalse("Guide job did not finish",store.guideBusy.value)}
    private fun waitForStoreIdle(store: Store) {val deadline=System.currentTimeMillis()+120000;while((!store.ready.value || store.busy.value || store.guideBusy.value) && System.currentTimeMillis()<deadline)Thread.sleep(100);assertTrue("Store did not become ready",store.ready.value);assertFalse("Catalog import did not finish",store.busy.value);assertFalse("Guide job did not finish",store.guideBusy.value)}
    @Test fun largeChunkedCatalogAndGzipGuideRemainBrowsable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=(context.applicationContext as EzanoApp).store
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            waitForStoreIdle(store)
            store.update {Library()}
            val start=System.currentTimeMillis()
            runBlocking {store.refresh(Provider(id="large",name="Large test",url="http://10.0.2.2:8766",user="test",password="test"))}
            assertTrue(store.error.value,store.state.value.channels.size==76000)
            waitForGuideIdle(store)
            assertEquals(64000,store.state.value.programs.size)
            assertEquals(76000,store.state.value.channels.asSequence().map {it.id}.toSet().size)
            assertEquals(6000,store.visible(Kind.SERIES).size)
            assertEquals("series 6000",store.visible(Kind.SERIES).last().name)
            // A successful import must already be durable without a manual flush.
            val reloaded=Store(context)
            val deadline=System.currentTimeMillis()+120000
            while(!reloaded.ready.value && System.currentTimeMillis()<deadline)Thread.sleep(50)
            assertTrue(reloaded.error.value,reloaded.ready.value)
            assertEquals(76000,reloaded.state.value.channels.size)
            assertEquals(64000,reloaded.state.value.programs.size)
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            scenario.onActivity {it.page="Live TV"}
            assertTrue(device.wait(Until.hasObject(By.text("Search channels")),20000))
            scenario.onActivity {it.page="Movies"}
            assertTrue(device.wait(Until.hasObject(By.text("68000 movies in your collection")),30000))
            device.findObject(By.clazz("android.widget.EditText")).click();Thread.sleep(400);device.findObject(By.clazz("android.widget.EditText")).text="movie 68000"
            assertTrue(device.wait(Until.hasObject(By.text("movie 68000")),30000))
            device.pressBack();assertTrue(device.wait(Until.hasObject(By.textContains("1 results")),30000))
            scenario.onActivity {it.page="Series"}
            assertTrue(device.wait(Until.hasObject(By.text("6000 series in your collection")),30000))
            device.findObject(By.clazz("android.widget.EditText")).click();Thread.sleep(400);device.findObject(By.clazz("android.widget.EditText")).text="series 6000";device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.textContains("1 results")),30000))
            repeat(5){device.pressDPadDown()};device.pressBack()
            waitForGuideIdle(store)
            println("LARGE_IMPORT_MS="+(System.currentTimeMillis()-start))
            println("HEAP_MAX_MB="+Runtime.getRuntime().maxMemory()/1048576)
        }
    }
}
