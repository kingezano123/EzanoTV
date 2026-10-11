package tv.ezano.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Playback {
    fun create(context: Context,c: Channel,afr: Boolean,muted: Boolean=false,subtitleLanguage: String="en",autoSubtitles: Boolean=true,speech:SpeechCaptions?=null): ExoPlayer {
        val http=DefaultHttpDataSource.Factory().setUserAgent("EzanoTV/0.4").setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(c.headers)
        val source=DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http))
        val renderers=object:DefaultRenderersFactory(context){
            override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):androidx.media3.exoplayer.audio.AudioSink? {
                val sink=super.buildAudioSink(context,if(speech==null)enableFloatOutput else false,enableAudioTrackPlaybackParams)?:return null
                return speech?.sink(sink)?:sink
            }
        }.setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context,renderers)
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
            .setMediaSourceFactory(source).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(if(c.kind==Kind.LIVE) 6000 else 15000,if(c.kind==Kind.LIVE) 20000 else 45000,if(c.kind==Kind.LIVE) 500 else 1000,2000).setTargetBufferBytes(32*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),!muted)
                setHandleAudioBecomingNoisy(true)
                setVideoChangeFrameRateStrategy(if(afr) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                volume=if(muted) 0f else 1f
                trackSelectionParameters=trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").setPreferredTextLanguages(subtitleLanguage.ifBlank {"en"},if(subtitleLanguage=="en")"eng" else subtitleLanguage).setSelectUndeterminedTextLanguage(false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!autoSubtitles).build()
                val captions=c.subtitleTracks.map {sub->MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url)).setLanguage(sub.language).setLabel(sub.label).setMimeType(sub.mime).setSelectionFlags(if(autoSubtitles && SubtitleLanguages.matches(sub.language,subtitleLanguage))C.SELECTION_FLAG_DEFAULT else 0).build()}
                setMediaItem(MediaItem.Builder().setUri(Uri.parse(c.url)).setSubtitleConfigurations(captions).setMediaMetadata(MediaMetadata.Builder().setTitle(c.name).build()).build()); prepare(); playWhenReady=true
            }
    }
}
