package tv.ezano.app
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpeechPlaybackTest {
    private fun waitFor(check:()->Boolean){val deadline=System.currentTimeMillis()+60000;while(!check() && System.currentTimeMillis()<deadline)Thread.sleep(100);assertTrue("Timed out",check())}
    @Test fun decodedProgramAudioGeneratesEnglishCaptions(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val store=(context.applicationContext as EzanoApp).store
        org.junit.Assume.assumeTrue("Push the verified speech model fixture first",File(context.externalMediaDirs.first(),"model.zip").isFile)
        waitFor {store.ready.value};store.update {Library(prefs=Prefs())}
        SpeechModel.installArchive(File(context.externalMediaDirs.first(),"model.zip"),File(context.cacheDir,"model-test"),SpeechModel.directory(context))
        context.getSharedPreferences("speech-captions",0).edit().putBoolean("enabled",true).commit()
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.startPlayback(Channel("speech-test","fixture","1","Speech test","http://10.0.2.2:8767/speech.wav",Kind.MOVIE))}
            var caption="";var engineStatus="";var position=0L
            waitFor {scenario.onActivity {a->position=a.player?.currentPosition?:0;caption=a.speech?.captionAt(position).orEmpty();engineStatus=a.speech?.status?.value.orEmpty()};caption.lowercase().let {it.contains("one") || it.contains("zero")}}
            assertTrue(position>0);println("SPEECH_CAPTION positionMs=$position text=$caption status=$engineStatus")
            scenario.onActivity {it.hidePlayerControls()};Thread.sleep(200)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.filesDir,"speech-031.png"))
            scenario.onActivity {it.player!!.pause();it.player!!.seekTo(0);assertEquals("",it.speech!!.captionAt(0));it.stopPlayback();assertNull(it.speech)}
        }}finally{context.getSharedPreferences("speech-captions",0).edit().putBoolean("enabled",false).commit()}
    }
}
