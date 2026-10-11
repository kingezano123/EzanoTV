package tv.ezano.app

import androidx.test.core.app.ActivityScenario
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitlePlaybackTest {
    private fun waitFor(check: ()->Boolean) {val until=System.currentTimeMillis()+30000;while(!check() && System.currentTimeMillis()<until)Thread.sleep(100);assertTrue("Timed out",check())}
    @Test fun providerEnglishSubtitlesAppearAutomaticallyWithoutBlockingVideo() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val store=(context.applicationContext as EzanoApp).store
        waitFor {store.ready.value};val provider=Provider(id="captions",name="Captions test",url="http://10.0.2.2:8765",user="test",password="test")
        store.update {Library(providers=listOf(provider))}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.play(Channel("caption-film",provider.id,"21","Caption test","http://10.0.2.2:8765/movie/test/test/21.mp4",Kind.MOVIE))}
            var ready=false;waitFor {scenario.onActivity {ready=it.player?.playbackState==Player.STATE_READY};ready}
            var hasCue=false;waitFor {scenario.onActivity {hasCue=it.player?.currentCues?.cues?.any {cue->cue.text?.contains("AUTOMATIC English captions")==true}==true};hasCue}
            val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            scenario.onActivity {it.hidePlayerControls()};Thread.sleep(400);device.takeScreenshot(java.io.File(context.filesDir,"subtitles-030.png"))
            scenario.onActivity {assertTrue(it.player!!.currentPosition>0);assertEquals("en",it.player!!.trackSelectionParameters.preferredTextLanguages.first());it.stopPlayback()}
        }
    }
    @Test fun importedSubtitlesRenderAndKeepPlaybackPosition() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val movie=Channel("subtitle-test","test","1","Subtitle test","http://10.0.2.2:8765/movie/test/test/1.mp4",Kind.MOVIE)
            scenario.onActivity {it.play(movie)}
            var ready=false
            waitFor {scenario.onActivity {ready=it.player?.playbackState==Player.STATE_READY};ready}
            scenario.onActivity {it.player!!.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())}
            scenario.onActivity {it.player!!.seekTo(15000)}
            Thread.sleep(500)
            var attached=false
            scenario.onActivity {a->a.scope.launch {a.attachSubtitle("1\n00:00:00,000 --> 00:01:00,000\nEZANO subtitle check\n","en");attached=true}}
            waitFor {attached}
            var hasCue=false
            waitFor {scenario.onActivity {a->hasCue=a.player?.currentCues?.cues?.any {it.text?.contains("EZANO subtitle check")==true}==true};hasCue}
            scenario.onActivity {assertTrue(it.player!!.currentPosition>=15000);it.stopPlayback()}
        }
    }
}
