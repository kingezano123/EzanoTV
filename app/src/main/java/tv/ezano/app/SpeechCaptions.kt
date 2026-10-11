package tv.ezano.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal data class SpeechCue(val startMs:Long,val endMs:Long,val text:String,val partial:Boolean=false)

/** Live captions from the program audio, recognized on this device (free, offline, no microphone).
 * PCM enters a bounded queue after the audio sink accepts it; recognition never blocks playback.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpeechCaptions(context:Context) {
    companion object {
        private val engineSlot=kotlinx.coroutines.sync.Mutex()
        private val modelLock=Any()
        private var shared:Model?=null
        private var sharedPath=""
        /** Loading the model takes seconds, so it is kept across channel changes while the app is open. */
        private fun model(path:String):Model=synchronized(modelLock) {
            shared?.takeIf {sharedPath==path}?:Model(path).also {shared?.close();shared=it;sharedPath=path}
        }
        /** Frees the model's memory once no recognizer is using it (e.g. when the app leaves the screen). */
        fun releaseModel() {CoroutineScope(Dispatchers.IO).launch {engineSlot.withLock {synchronized(modelLock) {shared?.close();shared=null;sharedPath=""}}}}
        // ~10 s of 48 kHz stereo decoder output; short stalls no longer reset recognition.
        private const val QUEUE_BYTES=2_000_000
        // Feed Vosk ~120 ms at a time instead of one call per 20 ms decoder buffer.
        private const val BATCH_SAMPLES=1920
    }
    private data class Block(val bytes:ByteArray,val rate:Int,val channels:Int,val ptsUs:Long,val generation:Int)
    private val queue=java.util.concurrent.LinkedBlockingQueue<Block>()
    private val queuedBytes=AtomicInteger(0)
    private val generation=AtomicInteger(0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val modelPath=SpeechModel.directory(context).path
    private val gate=EnglishSpeechGate()
    val status=MutableStateFlow("Live captions off")
    @Volatile var enabled=false;private set
    @Volatile var playheadMs=0L
    @Volatile private var cues:List<SpeechCue> = emptyList()
    @Volatile private var closed=false
    @Volatile private var audioSupported=true
    private var worker:Job?=null
    fun enable(value:Boolean,subtitles:Boolean=false){
        if(closed || enabled==value)return
        if(value && !audioSupported){status.value="Live captions need decoded audio. Use stream subtitles for this format.";return}
        enabled=value;reset()
        if(!value){status.value=if(subtitles)"Live captions paused · a subtitle track is showing" else "Live captions off";return}
        if(worker!=null)return
        status.value="Starting live captions…"
        worker=scope.launch {
            try {
                engineSlot.withLock {Recognizer(model(modelPath),16000f).use {recognizer->
                    recognizer.setWords(true);recognizer.setPartialWords(true)
                    var epoch=-1;var origin=0L;var expected=0L;var resampler:PcmSpeechResampler?=null
                    val batch=ShortArray(BATCH_SAMPLES*4);var batched=0
                    status.value="Live captions on · listening for English speech"
                    while(isActive && !closed){
                        if(!enabled){delay(250);continue}
                        val block=runInterruptible {queue.poll(120,java.util.concurrent.TimeUnit.MILLISECONDS)}
                        if(block!=null)queuedBytes.addAndGet(-block.bytes.size)
                        if(block!=null){
                            if(!enabled || block.generation!=generation.get())continue
                            if(epoch!=block.generation || kotlin.math.abs(block.ptsUs-expected)>250000){
                                recognizer.reset();epoch=block.generation;origin=block.ptsUs;resampler=PcmSpeechResampler(block.rate,block.channels);cues=emptyList();batched=0
                            }
                            expected=block.ptsUs+block.bytes.size.toLong()*1000000/(2*block.channels*block.rate)
                            val samples=resampler!!.convert(block.bytes)
                            if(batched>0 && batched+samples.size>batch.size){recognize(recognizer,batch,batched,origin,epoch);batched=0}
                            if(samples.size>batch.size)recognize(recognizer,samples,samples.size,origin,epoch)
                            else {samples.copyInto(batch,batched);batched+=samples.size}
                            // Keep feeding while more audio is queued, up to one batch.
                            if(batched<BATCH_SAMPLES && queue.isNotEmpty())continue
                        }
                        if(batched>0){recognize(recognizer,batch,batched,origin,epoch);batched=0}
                    }
                }}
            } catch(e:CancellationException){throw e}
              catch(_:Throwable){enabled=false;cues=emptyList();status.value="Live captions are unavailable on this device. Stream subtitles still work."}
            finally {worker=null}
        }
    }
    private fun recognize(recognizer:Recognizer,samples:ShortArray,count:Int,origin:Long,epoch:Int){
        val complete=recognizer.acceptWaveForm(samples,count)
        if(epoch!=generation.get() || !enabled)return
        val result=JSONObject(if(complete)recognizer.result else recognizer.partialResult)
        if(complete){
            val confidence=SpeechCueParser.confidence(result)
            val english=gate.final(confidence)
            val fresh=if(english && confidence>=EnglishSpeechGate.LINE_CONFIDENCE)SpeechCueParser.parse(result,origin/1000,false) else emptyList()
            // A finished utterance always replaces its partial lines, even when it was rejected.
            val boundary=fresh.firstOrNull()?.startMs?:Long.MAX_VALUE
            cues=(cues.filter {!it.partial && it.endMs<boundary && it.endMs>playheadMs-2500}+fresh).takeLast(40)
            status.value=if(gate.englishLikely)"Live captions on · English speech" else "Live captions on · waiting for English speech"
        } else if(gate.englishLikely){
            val fresh=SpeechCueParser.parse(result,origin/1000,true)
            if(fresh.isNotEmpty()){
                val boundary=fresh.first().startMs
                cues=(cues.filter {!it.partial && it.endMs<boundary && it.endMs>playheadMs-2500}+fresh).takeLast(40)
            }
        }
    }
    fun captionAt(positionMs:Long,delayMs:Long=0):String {
        playheadMs=positionMs
        if(!enabled)return ""
        val time=positionMs-delayMs
        return cues.lastOrNull {time>=it.startMs-250 && time<=it.endMs+1800}?.text.orEmpty()
    }
    fun reset(){generation.incrementAndGet();queue.clear();queuedBytes.set(0);cues=emptyList()}
    fun close(){closed=true;enabled=false;reset();scope.cancel()}
    internal fun accept(bytes:ByteArray,rate:Int,channels:Int,ptsUs:Long){
        if(!enabled || closed || rate !in 8000..192000 || channels !in 1..8)return
        if(bytes.size>262144)return // An oversized decoder packet must never create a large allocation.
        if(queuedBytes.get()+bytes.size>QUEUE_BYTES){reset();status.value="Live captions catching up…"}
        queuedBytes.addAndGet(bytes.size);queue.offer(Block(bytes,rate,channels,ptsUs,generation.get()))
    }
    internal fun sink(delegate:AudioSink):AudioSink=object:ForwardingAudioSink(delegate){
        private var format:Format?=null;private var firstPts:Long?=null;private var mediaOrigin=0L
        override fun supportsFormat(format:Format)=getFormatSupport(format)!=AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format:Format):Int {
            if(format.sampleMimeType=="audio/raw")return super.getFormatSupport(format)
            // Decode surround/passthrough formats on the device so their speech can be captioned.
            val decodable=runCatching {androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(format.sampleMimeType.orEmpty(),false,false).isNotEmpty()}.getOrDefault(false)
            return if(decodable)AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
        }
        override fun configure(inputFormat:Format,specifiedBufferSize:Int,outputChannels:IntArray?){
            format=inputFormat;audioSupported=inputFormat.pcmEncoding==C.ENCODING_PCM_16BIT
            if(!audioSupported){enable(false);status.value="Live captions need decoded 16-bit audio. Use stream subtitles for this format."}
            super.configure(inputFormat,specifiedBufferSize,outputChannels)
        }
        override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
            val f=format;val start=buffer.position();val copy=buffer.duplicate();val result=super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
            val consumed=buffer.position()-start
            if(enabled && f?.pcmEncoding==C.ENCODING_PCM_16BIT && consumed in 1..262144){
                if(firstPts==null){firstPts=presentationTimeUs;mediaOrigin=playheadMs*1000}
                val pts=mediaOrigin+(presentationTimeUs-firstPts!!)+start.toLong()*1000000/(2*f.channelCount*f.sampleRate)
                val bytes=ByteArray(consumed);copy.limit(start+consumed);copy.get(bytes);accept(bytes,f.sampleRate,f.channelCount,pts)
            }
            return result
        }
        override fun flush(){firstPts=null;this@SpeechCaptions.reset();super.flush()}
        override fun reset(){firstPts=null;this@SpeechCaptions.reset();super.reset()}
        override fun handleDiscontinuity(){firstPts=null;this@SpeechCaptions.reset();super.handleDiscontinuity()}
    }
}

/** Captions are English-only, but any program can be playing. Recent recognizer confidence decides
 * whether the audio is English: instant word-by-word lines show for English speech, and lines from
 * other languages or music are dropped instead of appearing as nonsense English words.
 */
internal class EnglishSpeechGate {
    companion object {
        const val LINE_CONFIDENCE=.55
        private const val WINDOW=4
        private const val ENGLISH=.62
    }
    private val recent=ArrayDeque<Double>()
    @Volatile var englishLikely=true;private set
    /** Records a finished utterance; returns whether its words may be shown. */
    fun final(confidence:Double):Boolean {
        if(confidence.isNaN())return englishLikely
        recent.addLast(confidence);while(recent.size>WINDOW)recent.removeFirst()
        englishLikely=recent.average()>=ENGLISH || confidence>=.8
        return englishLikely || confidence>=.8
    }
}

/** Linear low-cost resampling retains phase across decoder buffers. */
internal class PcmSpeechResampler(private val rate:Int,private val channels:Int){
    private var position=0.0;private var previous=0.0
    fun convert(bytes:ByteArray):ShortArray {
        val source=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val frames=bytes.size/(channels*2)
        val mono=DoubleArray(frames);for(i in mono.indices){var sum=0L;repeat(channels){sum+=source.short.toLong()};mono[i]=sum.toDouble()/channels}
        val out=ShortArray((frames*16000.0/rate).toInt()+2);var count=0;val step=rate/16000.0
        while(position<frames){val i=position.toInt();val fraction=position-i;val a=if(i==0)previous else mono[i-1];val b=mono[i];out[count++]=(a+(b-a)*fraction).toInt().coerceIn(-32768,32767).toShort();position+=step}
        position-=frames;if(frames>0)previous=mono.last();return out.copyOf(count)
    }
}

internal object SpeechCueParser {
    /** Mean word confidence of a final result; NaN when it has no words. */
    fun confidence(json:JSONObject):Double {
        val words=json.optJSONArray("result")?:return Double.NaN
        var total=0.0;var count=0
        for(i in 0 until words.length()){val word=words.optJSONObject(i)?:continue;if(word.optString("word").isBlank())continue;total+=word.optDouble("conf",1.0);count++}
        return if(count==0)Double.NaN else total/count
    }
    fun parse(json:JSONObject,originMs:Long,partial:Boolean):List<SpeechCue>{
        val words=json.optJSONArray(if(partial)"partial_result" else "result")?:return emptyList()
        val result=ArrayList<SpeechCue>();var text="";var start=0L;var end=0L
        fun emit(){if(text.isNotBlank())result+=SpeechCue(originMs+start,originMs+end,text.trim().replaceFirstChar {it.uppercase()},partial);text=""}
        for(i in 0 until words.length()){
            val word=words.getJSONObject(i);val value=word.optString("word").take(40);if(value.isBlank())continue
            val from=(word.optDouble("start")*1000).toLong();val to=(word.optDouble("end")*1000).toLong()
            if(!partial && word.optDouble("conf",1.0)<.35)continue
            if(text.isNotBlank() && (text.length+value.length>64 || to-start>4500))emit()
            if(text.isBlank())start=from;text+="$value ";end=to.coerceAtLeast(from+200)
        }
        emit();return result
    }
}
