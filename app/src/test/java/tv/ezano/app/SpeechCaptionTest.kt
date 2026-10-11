package tv.ezano.app
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechCaptionTest {
    @Test fun captionsUseWordTimesAndExcludeLowConfidenceFinalWords(){
        val cues=SpeechCueParser.parse(JSONObject("""{"result":[{"word":"hello","start":1,"end":1.5,"conf":0.95},{"word":"uncertain","start":1.6,"end":2,"conf":0.1},{"word":"world","start":2.1,"end":2.6,"conf":0.9}]}"""),10000,false)
        assertEquals(11000,cues.single().startMs);assertEquals(12600,cues.single().endMs);assertEquals("Hello world",cues.single().text)
    }
    @Test fun partialUpdatesRemainShortEnoughForTwoLines(){
        val words=org.json.JSONArray();repeat(25){words.put(JSONObject().put("word","caption").put("start",it*.5).put("end",it*.5+.4))}
        val cues=SpeechCueParser.parse(JSONObject().put("partial_result",words),0,true)
        assertTrue(cues.size>1);assertTrue(cues.all {it.text.length<=64 && it.endMs-it.startMs<=4500 && it.partial})
    }
    @Test fun stereoDownmixAndResamplingKeepPhaseAcrossBuffers(){
        fun block(frames:Int)=ByteBuffer.allocate(frames*4).order(ByteOrder.LITTLE_ENDIAN).apply {repeat(frames){putShort(1000);putShort(3000)}}.array()
        val resampler=PcmSpeechResampler(48000,2);val chunks=List(100){resampler.convert(block(481))}.flatMap {it.toList()}
        assertEquals(16034,chunks.size);assertTrue(chunks.drop(1).all {it==2000.toShort()})
    }
    @Test fun englishLanguageCodesArePrecise(){
        listOf("EN","eng","English","en-GB","EN_US").forEach {assertTrue(SubtitleLanguages.matches(it))}
        listOf("end","es","de","und","").forEach {assertFalse(SubtitleLanguages.matches(it))}
    }
}
