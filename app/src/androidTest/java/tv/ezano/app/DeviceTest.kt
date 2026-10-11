package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.media3.common.Player
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val store get()=(context.applicationContext as EzanoApp).store
    private fun waitFor(test: ()->Boolean) {val deadline=System.currentTimeMillis()+30000;while(!test() && System.currentTimeMillis()<deadline)Thread.sleep(100);assertTrue("Condition timed out",test())}
    private fun waitForGuideIdle() {val deadline=System.currentTimeMillis()+120000;while(store.guideBusy.value && System.currentTimeMillis()<deadline)Thread.sleep(100);assertFalse("Guide job did not finish",store.guideBusy.value)}
    @Test fun importPlaybackRecordingAndRemoteNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            waitFor {store.ready.value && !store.busy.value && !store.guideBusy.value};store.update {Library()};val provider=Provider(id="fixture",name="Test cinema",url="http://10.0.2.2:8765",user="test",password="test")
            runBlocking {store.refresh(provider)};assertTrue(store.error.value,store.state.value.channels.any {it.provider==provider.id});assertEquals(4,store.state.value.channels.count {it.kind==Kind.LIVE});assertEquals(6,store.state.value.channels.count {it.kind==Kind.MOVIE});waitForGuideIdle();assertTrue(store.state.value.programs.isNotEmpty())
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());scenario.onActivity {it.page="Live TV"};device.waitForIdle();assertTrue(device.wait(Until.hasObject(By.text("Search channels")),10000));device.pressDPadRight();device.pressDPadDown();device.pressDPadCenter();device.pressBack()
            scenario.onActivity {it.page="Live TV"};Thread.sleep(1500);device.takeScreenshot(File(context.getExternalFilesDir(null),"live-tested.png"))
            val movie=store.state.value.channels.first {it.kind==Kind.MOVIE};scenario.onActivity {it.play(movie)};var ready=false;waitFor {scenario.onActivity {ready=it.player?.playbackState==Player.STATE_READY};ready};scenario.onActivity {assertTrue(it.player!!.duration>0);it.player!!.seekTo(15000)};Thread.sleep(800);scenario.onActivity {assertTrue(it.player!!.currentPosition>=15000);it.stopPlayback()};assertTrue((store.state.value.positions[movie.id]?:0)>0)
            val live=store.state.value.channels.first {it.kind==Kind.LIVE};scenario.onActivity {it.play(live)};waitFor {scenario.onActivity {ready=it.player?.playbackState==Player.STATE_READY};ready};scenario.onActivity {it.stopPlayback()}
            val now=System.currentTimeMillis();val rec=Recording(id="device-test",channel=live,title="Device smoke recording",start=now,end=now+10000);store.update {it.copy(recordings=it.recordings.filterNot {r->r.id==rec.id}+rec)}
            scenario.onActivity {androidx.core.content.ContextCompat.startForegroundService(it,android.content.Intent(it,RecordingService::class.java).putExtra("id",rec.id))};waitFor {store.state.value.recordings.any {it.id==rec.id && it.status !in listOf("scheduled","recording")}};val captured=store.state.value.recordings.first {it.id==rec.id};assertEquals(captured.error,"completed",captured.status);assertTrue(File(captured.path).length()>188)
            store.favorite(live);assertTrue(live.id in store.state.value.favorites);store.favorite(live);assertFalse(live.id in store.state.value.favorites)
            val p=store.state.value.providers.first();val series=store.state.value.channels.first {it.kind==Kind.SERIES};val episodes=Sources.episodes(p,series);assertEquals(1,episodes.size);assertEquals(Kind.EPISODE,episodes[0].kind)
            runBlocking {store.setPin("2468")};store.update {it.copy(locked=it.locked+live.id)};assertTrue(store.needsPin(live));assertFalse(runBlocking {store.checkPin("0000")});assertTrue(runBlocking {store.checkPin("2468")});store.update {it.copy(prefs=it.prefs.copy(pinHash="",pinSalt=""),locked=emptySet())}
            scenario.onActivity {it.page="Movies"};device.waitForIdle();Thread.sleep(1000);device.takeScreenshot(File(context.getExternalFilesDir(null),"movies-tested.png"))
            scenario.onActivity {it.page="Home"};device.waitForIdle();Thread.sleep(1000)
            waitForGuideIdle()
        }
    }
}
