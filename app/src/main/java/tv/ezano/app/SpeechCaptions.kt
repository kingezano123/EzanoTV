package tv.ezano.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal data class SpeechCue(val startMs:Long,val endMs:Long,val text:String,val partial:Boolean=false)

/** PCM enters a bounded queue after the audio sink accepts it. Recognition never blocks playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpeechCaptions(context:Context) {
    companion object {private val engineSlot=kotlinx.coroutines.sync.Mutex()}
    private data class Block(val bytes:ByteArray,val rate:Int,val channels:Int,val ptsUs:Long,val generation:Int)
    private val queue=ArrayBlockingQueue<Block>(12)
    private val generation=AtomicInteger(0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val modelPath=SpeechModel.directory(context).path
    val status=MutableStateFlow("Speech captions off")
    @Volatile var enabled=false;private set
    @Volatile var playheadMs=0L
    @Volatile private var cues:List<SpeechCue> = emptyList()
    @Volatile private var closed=false
    @Volatile private var audioSupported=true
    private var worker:Job?=null
    fun enable(value:Boolean){
        if(closed || enabled==value)return
        if(value && !audioSupported){status.value="Speech captions need decoded 16-bit audio. Use stream subtitles for this format.";return}
        enabled=value;reset()
        if(!value){status.value="Speech captions off";return}
        if(worker!=null)return
        status.value="Starting English speech captions…"
        worker=scope.launch {
            try {
                engineSlot.withLock {Model(modelPath).use {model->Recognizer(model,16000f).use {recognizer->
                    recognizer.setWords(true);recognizer.setPartialWords(true)
                    var epoch=-1;var origin=0L;var expected=0L;var resampler:PcmSpeechResampler?=null
                    while(isActive && !closed){
                        if(!enabled){delay(250);continue}
                        val block=queue.poll();if(block==null){delay(20);continue}
                        if(!enabled || block.generation!=generation.get())continue
                        if(epoch!=block.generation || kotlin.math.abs(block.ptsUs-expected)>250000){
                            recognizer.reset();epoch=block.generation;origin=block.ptsUs;resampler=PcmSpeechResampler(block.rate,block.channels);cues=emptyList()
                        }
                        expected=block.ptsUs+block.bytes.size.toLong()*1000000/(2*block.channels*block.rate)
                        val samples=resampler!!.convert(block.bytes)
                        val complete=recognizer.acceptWaveForm(samples,samples.size)
                        val result=JSONObject(if(complete)recognizer.result else recognizer.partialResult)
                        if(epoch!=generation.get() || !enabled)continue
                        val fresh=SpeechCueParser.parse(result,origin/1000,!complete)
                        if(fresh.isNotEmpty()){
                            val boundary=fresh.first().startMs
                            val retained=cues.filter {!it.partial && it.endMs<boundary && it.endMs>playheadMs-2500}
                            cues=(retained+fresh).takeLast(40)
                        }
                        status.value="English speech captions · on device"
                    }
                }}}
            } catch(e:CancellationException){throw e}
              catch(_:Throwable){enabled=false;cues=emptyList();status.value="Speech captions unavailable on this device. Stream subtitles still work."}
            finally {worker=null}
        }
    }
    fun captionAt(positionMs:Long,delayMs:Long=0):String {
        playheadMs=positionMs
        if(!enabled)return ""
        val time=positionMs-delayMs
        return cues.lastOrNull {time>=it.startMs-120 && time<=it.endMs+1800}?.text.orEmpty()
    }
    fun reset(){generation.incrementAndGet();queue.clear();cues=emptyList()}
    fun close(){closed=true;enabled=false;reset();scope.cancel()}
    internal fun accept(bytes:ByteArray,rate:Int,channels:Int,ptsUs:Long){
        if(!enabled || closed || rate !in 8000..192000 || channels !in 1..8)return
        if(bytes.size>65536)return // An oversized decoder packet must never create a large allocation.
        val epoch=generation.get();if(!queue.offer(Block(bytes,rate,channels,ptsUs,epoch))){reset();queue.offer(Block(bytes,rate,channels,ptsUs,generation.get()));status.value="Speech captions catching up…"}
    }
    internal fun sink(delegate:AudioSink):AudioSink=object:ForwardingAudioSink(delegate){
        private var format:Format?=null;private var firstPts:Long?=null;private var mediaOrigin=0L
        override fun supportsFormat(format:Format)=getFormatSupport(format)!=AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format:Format):Int {
            if(format.sampleMimeType=="audio/raw")return super.getFormatSupport(format)
            val decodable=runCatching {androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(format.sampleMimeType.orEmpty(),false,false).isNotEmpty()}.getOrDefault(false)
            return if(decodable)AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
        }
        override fun configure(inputFormat:Format,specifiedBufferSize:Int,outputChannels:IntArray?){
            format=inputFormat;audioSupported=inputFormat.pcmEncoding==C.ENCODING_PCM_16BIT
            if(!audioSupported){enable(false);status.value="Speech captions need decoded 16-bit audio. Use stream subtitles for this format."}
            super.configure(inputFormat,specifiedBufferSize,outputChannels)
        }
        override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
            val f=format;val start=buffer.position();val copy=buffer.duplicate();val result=super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
            val consumed=buffer.position()-start
            if(enabled && f?.pcmEncoding==C.ENCODING_PCM_16BIT && consumed in 1..65536){
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
