package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RebrandDeviceTest {
    private fun waitFor(check:()->Boolean){val until=System.currentTimeMillis()+20000;while(!check() && System.currentTimeMillis()<until)Thread.sleep(100);assertTrue("Timed out",check())}
    @Test fun watchIsInitialShowActionAndFavoritesRemainCompact(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val store=(context.applicationContext as EzanoApp).store
        waitFor {store.ready.value};val p=Provider(id="rebrand",name="Open cinema",url="http://10.0.2.2:8765",user="test",password="test")
        val art="http://10.0.2.2:8765/art/"
        val series=Channel("rebrand:series:1",p.id,"1","Open Film Stories","",Kind.SERIES,"EN Shows",logo=art+"steel.jpg",backdrop=art+"steel.jpg",language="en",certification="PG",genre="Family")
        val movies=listOf("Sintel","Tears of Steel","Big Buck Bunny").mapIndexed {i,name->Channel("movie:$i",p.id,"${i+1}",name,"http://10.0.2.2:8765/movie/test/test/${i+1}.mp4",Kind.MOVIE,"EN Movies",logo=art+listOf("sintel.jpg","steel.jpg","bunny.jpg")[i],backdrop=art+listOf("sintel.jpg","steel.jpg","bunny.jpg")[i],language="en",certification="PG",year="2010",rating="8.2",genre="Family")}
        val live=(1..7).map {i->Channel("live:$i",p.id,"$i","UK Channel $i","http://10.0.2.2:8765/live/test/test/1.ts",Kind.LIVE,"UK Entertainment",logo=art+"sintel.jpg")}
        store.update {Library(providers=listOf(p),channels=movies+series+live,favorites=(live+movies).map {it.id}.toSet(),prefs=Prefs(autoSubtitles=false,panelSeconds=20))}
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Configurator.getInstance().waitForIdleTimeout=100
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.page="Series"}
            val card=device.wait(Until.findObject(By.text("Open Film Stories")),10000);assertNotNull(card);card.click()
            assertTrue(device.wait(Until.hasObject(By.text("Watch")),10000));Thread.sleep(600)
            device.takeScreenshot(File(context.filesDir,"detail-030.png"));device.pressDPadCenter()
            var playing=false;waitFor {scenario.onActivity {playing=it.playing?.kind==Kind.EPISODE && it.player?.playbackState==Player.STATE_READY};playing}
            scenario.onActivity {assertEquals(series.id,it.playing!!.seriesId);it.player!!.seekTo(5000);it.player!!.pause()};Thread.sleep(400)
            scenario.onActivity {it.stopPlayback();it.page="Home"}
            assertTrue(device.wait(Until.hasObject(By.text("Continue Watching")),10000));Thread.sleep(800)
            assertTrue(WatchHistory.resume(store.state.value).any {it.seriesId==series.id})
            assertFalse(device.hasObject(By.text("Connect playlist")));assertFalse(device.hasObject(By.text("YOUR WORLD. ONE SCREEN.")))
            device.takeScreenshot(File(context.filesDir,"home-030.png"))
            scenario.onActivity {it.page="My list"};assertTrue(device.wait(Until.hasObject(By.text("Favorite channels")),10000));Thread.sleep(400)
            val cards=device.findObjects(By.textStartsWith("UK Channel"));assertTrue("Seven compact favorites should fit on the first row",cards.size>=7)
            assertTrue(cards.all {it.visibleBounds.height()<70});device.takeScreenshot(File(context.filesDir,"favorites-030.png"))
            cards.first().click();waitFor {scenario.onActivity {playing=it.playing?.kind==Kind.LIVE && it.player?.playbackState==Player.STATE_READY};playing}
            scenario.onActivity {it.stopPlayback()}
        }
    }
}
