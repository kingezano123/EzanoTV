package tv.ezano.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Exercises the modal-open import path that a direct Store.refresh test misses. */
class ImportFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val store get()=(instrumentation.targetContext.applicationContext as EzanoApp).store
    private fun awaitCondition(timeoutMs: Long=30_000,timeoutMessage: String="Condition timed out after ${timeoutMs} ms",condition: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+timeoutMs
        while(!condition() && SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
        assertTrue(timeoutMessage,condition())
    }
    private fun awaitGuideIdle() {val deadline=SystemClock.uptimeMillis()+120_000;while(store.guideBusy.value && SystemClock.uptimeMillis()<deadline)Thread.sleep(50);assertFalse("Guide job did not finish",store.guideBusy.value)}

    @Test fun largeImportFromEditorOpensUsableChannelsWithoutBlockingRemote() {
        awaitCondition(120_000) {store.ready.value && !store.busy.value && !store.guideBusy.value}
        // Fixture configuration only: the catalog must be imported by clicking the UI.
        val provider=Provider(id="ui-import",name="Import flow fixture",url="http://10.0.2.2:8766",user="test",password="test")
        store.update {Library(providers=listOf(provider),lastGuideSync=System.currentTimeMillis())}
        val configurator=Configurator.getInstance()
        val previousIdleTimeout=configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout=100
        try { ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val device=UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.desc("Playlists")),30_000))
            device.findObject(By.desc("Playlists")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Your playlists")),10_000))
            device.findObject(By.text("Edit")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Edit playlist")),10_000))
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Connect & import")
            assertTrue(device.wait(Until.hasObject(By.text("Connect & import")),10_000))
            // Let initial composition and the software keyboard settle before measuring.
            device.waitForIdle();Thread.sleep(500)
            val maximumGap=AtomicLong(0)
            val main=Handler(Looper.getMainLooper())
            var lastTick=SystemClock.uptimeMillis()
            val heartbeat=object:Runnable {
                override fun run() {
                    val now=SystemClock.uptimeMillis()
                    maximumGap.updateAndGet {maxOf(it,now-lastTick)}
                    lastTick=now
                    main.postDelayed(this,50)
                }
            }
            main.post(heartbeat)
            try {
                device.findObject(By.text("Connect & import")).click()
                awaitCondition(180_000) {!store.busy.value && store.state.value.channels.size==76_000}
                assertTrue(store.error.value,device.wait(Until.hasObject(By.text("Search channels")),30_000))
                assertFalse("The playlist manager must close after successful import",device.hasObject(By.text("Your playlists")))
                assertFalse("The nested editor must close after successful import",device.hasObject(By.text("Edit playlist")))
                scenario.onActivity {assertEquals("Live TV",it.page)}
                assertTrue(device.wait(Until.hasObject(By.text("▶ Watch live")),30_000))
                println("IMPORT_FLOW_MAX_MAIN_GAP_MS=${maximumGap.get()}")
                assertTrue("Main thread stalled for ${maximumGap.get()} ms during the visible import flow",maximumGap.get()<1_500)
                var channelFocused=false
                awaitCondition(30_000) {
                    scenario.onActivity {channelFocused=it.page=="Live TV" && !it.focusImportedChannel}
                    channelFocused
                }
                device.pressDPadCenter()
                var playing=false
                awaitCondition(30_000,"The imported channel did not reach Player.STATE_READY") {
                    scenario.onActivity {playing=it.fullPlayer && it.player?.playbackState==Player.STATE_READY}
                    playing
                }
                scenario.onActivity {it.stopPlayback()}
            } finally {main.removeCallbacks(heartbeat)}
            awaitGuideIdle()
        } } finally {configurator.waitForIdleTimeout=previousIdleTimeout}
    }

    @Test fun unavailableGuideStillClosesEditorAndOpensPlayableChannels() {
        awaitCondition(120_000) {store.ready.value && !store.busy.value && !store.guideBusy.value}
        val provider=Provider(
            id="ui-missing-guide",
            name="Missing guide fixture",
            url="http://10.0.2.2:8765",
            user="test",
            password="test",
            epg="http://10.0.2.2:8765/missing.xml"
        )
        store.update {Library(providers=listOf(provider),lastGuideSync=System.currentTimeMillis())}
        val device=UiDevice.getInstance(instrumentation)
        val configurator=Configurator.getInstance()
        val previousIdleTimeout=configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout=100
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                assertTrue(device.wait(Until.hasObject(By.desc("Playlists")),30_000))
                device.findObject(By.desc("Playlists")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Your playlists")),10_000))
                device.findObject(By.text("Edit")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Edit playlist")),10_000))
                UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Connect & import")
                device.findObject(By.text("Connect & import")).click()

                awaitCondition(90_000) {
                    !store.busy.value && store.state.value.channels.size==11
                }
                awaitGuideIdle()
                assertEquals(4,store.state.value.channels.count {it.kind==Kind.LIVE})
                assertEquals(6,store.state.value.channels.count {it.kind==Kind.MOVIE})
                assertEquals(1,store.state.value.channels.count {it.kind==Kind.SERIES})
                assertTrue("Expected guide failure status: ${store.guideStatus.value}",store.guideStatus.value.contains("guide is unavailable",true))
                assertTrue(device.wait(Until.hasObject(By.textContains("guide is unavailable")),10_000))
                assertFalse("The playlist manager must close after catalog import",device.hasObject(By.text("Your playlists")))
                assertFalse("The nested editor must close after catalog import",device.hasObject(By.text("Edit playlist")))
                scenario.onActivity {assertEquals("Live TV",it.page)}
                assertTrue(device.wait(Until.hasObject(By.text("Search channels")),15_000))
                assertTrue(device.wait(Until.hasObject(By.text("▶ Watch live")),15_000))
                var channelFocused=false
                awaitCondition(30_000) {
                    scenario.onActivity {channelFocused=it.page=="Live TV" && !it.focusImportedChannel}
                    channelFocused
                }
                device.pressDPadCenter()
                var playing=false
                awaitCondition(30_000) {
                    scenario.onActivity {playing=it.fullPlayer && it.player?.playbackState==Player.STATE_READY}
                    playing
                }
                scenario.onActivity {it.stopPlayback()}
                awaitGuideIdle()
            }
        } finally {
            configurator.waitForIdleTimeout=previousIdleTimeout
        }
    }

}
