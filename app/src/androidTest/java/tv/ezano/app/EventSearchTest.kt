package tv.ezano.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EventSearchTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun indexedSearchFindsLiveDescriptionsHonorsVisibilityAndReplacesSnapshots() = runBlocking {
        val index=EventSearchIndex(context,"events-functional-test.db")
        val now=System.currentTimeMillis()
        val current=Program("p:live:now","p:live","Sunday football",now-60_000,now+60_000,"Buffalo Bills vs Kansas City Chiefs")
        val future=current.copy(id="p:live:later",title="Chiefs press conference",start=now+60_000,end=now+120_000)
        val hidden=current.copy(id="q:live:now",channelId="q:live")
        val expired=current.copy(id="p:live:old",start=now-120_000,end=now)
        val guide=CompactGuide.Builder().apply {addAll(listOf(current,future,hidden,expired))}.build()
        assertEquals(listOf(current),index.search(guide,"Bills Chiefs",EventWindow.NOW,now,setOf("p:live")).programs)
        assertEquals(2,index.search(guide,"Chief",EventWindow.TODAY,now,setOf("p:live")).total)
        val page=index.search(guide,"chief",EventWindow.TODAY,now,setOf("p:live"),1)
        assertEquals(2,page.total);assertEquals(1,page.programs.size)
        assertTrue(index.search(guide,"' OR * ()",EventWindow.NOW,now,setOf("p:live")).programs.isEmpty())
        assertTrue(index.search(emptyList(),"chief",EventWindow.NOW,now,setOf("p:live")).programs.isEmpty())
        assertEquals(listOf(current),index.search(guide,"chief",EventWindow.NOW,now,setOf("p:live")).programs)
    }

    @Test fun hundredThousandProgramSearchAndRemoteEventFlow(): Unit = runBlocking {
        val now=System.currentTimeMillis()
        val guide=CompactGuide.Builder().apply {
            repeat(100_000) {i->add(Program("event:$i","channel:${i%200}",if(i==99_999) "Championship final" else "Program $i",now-60_000,now+3_600_000,if(i==99_999) "Buffalo Bills vs Kansas City Chiefs" else "A sample guide listing"))}
        }.build()
        val index=EventSearchIndex(context,"events-performance-test.db")
        val allowed=(0 until 200).map {"channel:$it"}.toSet()
        val build=System.nanoTime();index.prepare(guide)
        println("100,000-program event index build: ${(System.nanoTime()-build)/1_000_000}ms")
        val started=System.nanoTime()
        val results=index.search(guide,"Bills Chiefs",EventWindow.NOW,now,allowed)
        val elapsed=(System.nanoTime()-started)/1_000_000
        println("100,000-program indexed event query: ${elapsed}ms")
        assertEquals(1,results.total);assertEquals("event:99999",results.programs.single().id)
        assertTrue("Warm indexed search should finish within 1 second on the TV emulator",elapsed<1000)
        val store=(context.applicationContext as EzanoApp).store
        while(!store.ready.value) delay(50)
        val p=Provider(id="events",name="EPG fixture",type="m3u",url="https://example.test/list")
        val channels=(0 until 200).map {i->Channel("channel:$i",p.id,"$i","Sports ${i+1}","https://example.test/live/$i.ts",group="US Sports")}
        store.update {Library(providers=listOf(p),channels=channels,programs=guide,lastGuideSync=now)}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.page="Live TV"}
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val button=device.wait(Until.findObject(By.text("Find event")),15_000)
            assertNotNull(button);button.click()
            assertTrue(device.wait(Until.hasObject(By.text("Every channel. One search.")),10_000))
            val search=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10_000);search.click();Thread.sleep(400);search.text="Bills Chiefs";device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Championship final")),30_000))
            assertTrue(device.hasObject(By.text("Sports 200")))
            device.takeScreenshot(File(context.filesDir,"ezano-events.png"))
            device.pressDPadDown();device.pressDPadRight();device.pressBack()
        }
    }
}
