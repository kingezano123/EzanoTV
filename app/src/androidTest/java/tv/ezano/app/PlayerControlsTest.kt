package tv.ezano.app

import android.view.View
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test

class PlayerControlsTest {
    private fun waitFor(check:()->Boolean){val until=System.currentTimeMillis()+20000;while(!check() && System.currentTimeMillis()<until)Thread.sleep(100);assertTrue("Timed out",check())}
    @Test fun oneCompactBarSupportsRemoteSeekingAndAutoHide(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val store=(instrumentation.targetContext.applicationContext as EzanoApp).store
        waitFor {store.ready.value};store.update {Library(prefs=Prefs(panelSeconds=20))}
        val device=UiDevice.getInstance(instrumentation);Configurator.getInstance().waitForIdleTimeout=100
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.startPlayback(Channel("controls","test","1","Controls test","http://10.0.2.2:8765/movie/test/test/1.mp4",Kind.MOVIE))}
            var ready=false;waitFor {scenario.onActivity {ready=it.player?.playbackState==Player.STATE_READY};ready}
            assertTrue(device.wait(Until.hasObject(By.text("Audio & subtitles")),10000))
            assertFalse(device.hasObject(By.text("Hide tools")));assertFalse(device.hasObject(By.text("Record")));assertFalse(device.hasObject(By.text("Quality")))
            scenario.onActivity {assertFalse(it.window.decorView.findViewById<View>(androidx.media3.ui.R.id.exo_controller).isShown);it.player!!.pause();it.player!!.seekTo(15000)}
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);Thread.sleep(300)
            scenario.onActivity {assertTrue(it.player!!.currentPosition in 24900..25200)}
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND);Thread.sleep(300)
            scenario.onActivity {assertTrue(it.player!!.currentPosition in 14900..15200)}
            device.takeScreenshot(java.io.File(instrumentation.targetContext.filesDir,"player-030.png"))
            scenario.onActivity {it.hidePlayerControls()}
            device.pressDPadUp();assertTrue(device.wait(Until.hasObject(By.text("Audio & subtitles")),5000))
            scenario.onActivity {assertFalse("Reveal must not toggle playback",it.player!!.playWhenReady)}
            store.update {it.copy(prefs=it.prefs.copy(panelSeconds=2))}
            var hidden=false;waitFor {scenario.onActivity {hidden=!it.playerControlsVisible};hidden}
            assertTrue(device.wait(Until.gone(By.text("Audio & subtitles")),5000))
            device.pressBack();scenario.onActivity {assertFalse(it.fullPlayer);assertNull(it.player);assertNull(it.playing);assertTrue((it.store.state.value.positions["controls"]?:0)>0)}
            assertFalse(device.hasObject(By.text("Expand")));assertFalse(device.hasObject(By.text("PiP")))
        }
    }
}
