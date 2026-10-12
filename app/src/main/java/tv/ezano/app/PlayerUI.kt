package tv.ezano.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.*
import kotlinx.coroutines.*

/** Subtitles are white with a shadow, or white on a black box for busy or bright scenes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun captionStyle(boxed: Boolean)=CaptionStyleCompat(android.graphics.Color.WHITE,if(boxed)0xD9000000.toInt() else android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT,if(boxed)CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,android.graphics.Color.BLACK,android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL))
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun VideoSurface(p: ExoPlayer,modifier: Modifier=Modifier,controls: Boolean=true,seconds: Int=7,resize: Int=AspectRatioFrameLayout.RESIZE_MODE_FIT,controlsVisible: Boolean?=null,boxedCaptions: Boolean=false) {
    AndroidView(factory={ctx->PlayerView(ctx).apply {
        player=p;useController=false;resizeMode=resize;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        isFocusable=false;isFocusableInTouchMode=false;setKeepContentOnPlayerReset(true)
        subtitleView?.apply {setApplyEmbeddedStyles(false);setApplyEmbeddedFontSizes(false);setFractionalTextSize(.036f);setStyle(captionStyle(boxedCaptions))}
    }},update={v->v.player=p;v.resizeMode=resize;v.useController=false;v.subtitleView?.apply {setStyle(captionStyle(boxedCaptions));val density=v.resources.displayMetrics.density;setPadding((28*density).toInt(),0,(28*density).toInt(),((if(controlsVisible==true)104 else 40)*density).toInt());setBottomPaddingFraction(.075f)}},onRelease={it.player=null},modifier=modifier)
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PreviewVideo(a: MainActivity,c: Channel) {
    var resolved by remember(c.id) {mutableStateOf<Channel?>(null)}
    LaunchedEffect(c.id) {delay(650);runCatching {withContext(Dispatchers.IO){Sources.resolve(a.store.state.value.providers.firstOrNull {it.id==c.provider},c)}}.onSuccess {resolved=it}}
    resolved?.let {channel->val p=remember(channel.id) {Playback.create(a,channel,false,true)};DisposableEffect(p){onDispose{p.release()}};VideoSurface(p,Modifier.fillMaxSize(),false)}?:Art(c.logo,Modifier.fillMaxSize())
}
private fun playbackTime(ms: Long): String {val sec=(ms.coerceAtLeast(0)/1000);return if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%d:%02d".format(sec/60,sec%60)}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlayerScreen(a: MainActivity) {
    val p=a.player?:return;val c=a.playing?:return;val s by a.store.libraryState()
    var tracks by remember {mutableStateOf<Int?>(null)};var subtitleSources by remember {mutableStateOf(false)}
    var more by remember {mutableStateOf(false)};var captions by remember {mutableStateOf(false)};var sleep by remember {mutableStateOf(false)};var add by remember {mutableStateOf(false)}
    var speechSetup by remember {mutableStateOf(false)};var speechText by remember(p){mutableStateOf("")}
    var multi by remember(c.id) {mutableStateOf<List<Channel>>(emptyList())};var activeAudio by remember(c.id) {mutableStateOf(c.id)}
    var resize by remember {mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)};var status by remember(c.id) {mutableStateOf("")}
    var position by remember(p) {mutableLongStateOf(p.currentPosition)};var duration by remember(p) {mutableLongStateOf(0)};var isPlaying by remember(p) {mutableStateOf(p.isPlaying)}
    var scrubbing by remember {mutableStateOf<Float?>(null)};val playFocus=remember(p) {FocusRequester()};val toolsVisible=a.playerControlsVisible
    val speechBottom by animateDpAsState(if(toolsVisible)132.dp else 64.dp,label="caption clearance")
    val selectedIds=remember(multi) {multi.map {it.id}.toSet()}
    val choices by uiListState(uiKey(s.channels),s.providers,s.hidden,s.hiddenGroups,s.order,uiKey(multi),add) {if(add)a.store.visible(Kind.LIVE).searchCatalog {it.id!=c.id && it.id !in selectedIds} else emptyList()}
    val dialogOpen=tracks!=null || subtitleSources || more || captions || sleep || add || speechSetup || status.isNotBlank()
    SideEffect {a.playerDialogOpen=dialogOpen};DisposableEffect(a){onDispose{a.playerDialogOpen=false}}
    LaunchedEffect(toolsVisible,dialogOpen){if(toolsVisible && !dialogOpen){delay(60);runCatching {playFocus.requestFocus()}}}
    LaunchedEffect(toolsVisible,a.playerControlsInteraction,dialogOpen,s.prefs.panelSeconds){if(toolsVisible && !dialogOpen){delay(s.prefs.panelSeconds.coerceIn(2,30)*1000L);a.hidePlayerControls()}}
    var retries by remember(p) {mutableIntStateOf(0)};val recovery=rememberCoroutineScope()
    DisposableEffect(p){val listener=object:Player.Listener {
        override fun onPlayerError(error:PlaybackException){
            // Live streams reconnect by themselves a few times before asking; falling behind the live window jumps to live.
            if(error.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW){p.seekToDefaultPosition();p.prepare();return}
            if(c.kind==Kind.LIVE && retries<3){retries++;recovery.launch {delay(1500L*retries);p.prepare();p.play()};return}
            status="This stream was interrupted. Check your connection or try again."
        }
        override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_READY){status="";retries=0}}
        override fun onIsPlayingChanged(value:Boolean){isPlaying=value}
    };p.addListener(listener);onDispose{p.removeListener(listener);p.volume=1f}}
    LaunchedEffect(p){while(true){position=p.currentPosition;duration=p.duration.coerceAtLeast(0);delay(350)}}
    LaunchedEffect(p,a.speech){var lastPosition=-1L;while(true){val now=p.currentPosition;val engine=a.speech;engine?.playheadMs=now;if(p.isPlaying || kotlin.math.abs(now-lastPosition)>250 || engine?.enabled!=true)speechText=engine?.captionAt(now).orEmpty();lastPosition=now;delay(100)}}
    LaunchedEffect(activeAudio){p.volume=if(activeAudio==c.id)1f else 0f}
    Box(Modifier.fillMaxSize().background(Color.Black).clickable {a.showPlayerControls()}) {
        if(multi.isEmpty())VideoSurface(p,Modifier.fillMaxSize(),false,resize=resize,controlsVisible=toolsVisible && !dialogOpen,boxedCaptions=s.prefs.subtitleBackground)
        else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            (listOf(c)+multi).chunked(2).forEach {row->Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)){row.forEach {tile->Box(Modifier.weight(1f).fillMaxHeight().border(1.dp,if(toolsVisible && activeAudio==tile.id)Color.White else Color.Transparent).clickable {a.showPlayerControls();activeAudio=tile.id}){if(tile.id==c.id)VideoSurface(p,Modifier.fillMaxSize(),false)else MultiVideo(a,tile,activeAudio==tile.id);if(toolsVisible)Text(tile.name,color=Color.White,fontSize=11.sp,modifier=Modifier.align(Alignment.TopStart).background(Color.Black.copy(.65f)).padding(7.dp))}};if(row.size==1)Spacer(Modifier.weight(1f))}}
        }
        if(toolsVisible && !dialogOpen) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(.7f),Color.Transparent))).padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                EzButton("Back",Icons.Outlined.ArrowBack,onClick={a.browsePlayback()})
                Text(if(c.kind==Kind.EPISODE) "${c.group} · ${c.name}" else HomeRecommendations.title(c),color=Color.White,fontSize=15.sp,maxLines=1)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(.95f)))).padding(start=28.dp,end=28.dp,top=28.dp,bottom=20.dp)) {
                if(duration>0 && p.isCurrentMediaItemSeekable) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(playbackTime(scrubbing?.toLong()?:position),color=Color.White,fontSize=11.sp)
                        val timelineColors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=LocalAccent.current,inactiveTrackColor=Color.White.copy(.35f))
                        Slider(value=scrubbing?:position.toFloat().coerceIn(0f,duration.toFloat()),onValueChange={scrubbing=it;a.playerControlsInteraction++},valueRange=0f..duration.toFloat().coerceAtLeast(1f),onValueChangeFinished={scrubbing?.let {p.seekTo(it.toLong());position=it.toLong()};scrubbing=null;a.showPlayerControls()},modifier=Modifier.weight(1f),colors=timelineColors,thumb={Box(Modifier.size(8.dp).background(Color.White,CircleShape))},track={state->SliderDefaults.Track(state,modifier=Modifier.height(3.dp),colors=timelineColors)})
                        Text("−${playbackTime((duration-position).coerceAtLeast(0))}",color=Color.White,fontSize=11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    EzButton(if(isPlaying)"Pause" else "Play",if(isPlaying)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,primary=true,modifier=Modifier.focusRequester(playFocus),onClick={if(p.isPlaying)p.pause()else p.play();a.showPlayerControls()})
                    if(p.isCurrentMediaItemSeekable){EzButton("10s",Icons.Outlined.Replay10,onClick={p.seekBack();a.showPlayerControls()});EzButton("10s",Icons.Outlined.Forward10,onClick={p.seekForward();a.showPlayerControls()})}
                    if(c.kind==Kind.LIVE)Text("LIVE",color=Color.White,fontSize=11.sp)
                    Spacer(Modifier.weight(1f));EzButton("Audio & subtitles",Icons.Outlined.Subtitles,onClick={captions=true});EzButton("",Icons.Outlined.MoreHoriz,onClick={more=true})
                }
            }
        }
        if(speechText.isNotBlank()){
            val boxed=s.prefs.subtitleBackground
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.76f).padding(bottom=speechBottom),contentAlignment=Alignment.BottomCenter){
                Text(speechText,color=Color.White,fontSize=20.sp,lineHeight=27.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2,
                    style=androidx.compose.ui.text.TextStyle(shadow=if(boxed)null else Shadow(Color.Black,androidx.compose.ui.geometry.Offset(1f,2f),5f)),
                    modifier=if(boxed)Modifier.background(Color.Black.copy(.85f),RoundedCornerShape(4.dp)).padding(horizontal=12.dp,vertical=5.dp) else Modifier)
            }
        }
        if(status.isNotBlank())Column(Modifier.align(Alignment.Center).width(420.dp).background(Panel,RoundedCornerShape(4.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(status,color=Color.White,fontSize=14.sp);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){EzButton("Retry",primary=true,onClick={status="";p.prepare();p.play()});EzButton("Back",onClick={a.stopPlayback()})}}
    }
    if(captions)Modal("Audio & subtitles",{captions=false}){
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){EzButton("Audio",Icons.Outlined.VolumeUp,onClick={captions=false;tracks=C.TRACK_TYPE_AUDIO});EzButton("Subtitles",Icons.Outlined.Subtitles,onClick={captions=false;tracks=C.TRACK_TYPE_TEXT});EzButton(if(a.speechEnabled() && SpeechModel.installed(a))"Live captions: On" else "Live captions: Off",Icons.Outlined.ClosedCaption,onClick={captions=false;speechSetup=true})}
        EzButton(if(s.prefs.subtitleBackground)"Caption background: Black" else "Caption background: Clear",onClick={a.store.update {it.copy(prefs=it.prefs.copy(subtitleBackground=!it.prefs.subtitleBackground))}})
        Text("English subtitles are detected from your stream and provider automatically. Live captions write English subtitles from the audio when a program has none.",color=Muted,fontSize=12.sp,lineHeight=17.sp)
        a.speech?.let {engine->val speechStatus by engine.status.collectAsState();Text(speechStatus,color=Muted,fontSize=11.sp)}
    }
    tracks?.let {type->TrackDialog(p,type,onAdd={tracks=null;subtitleSources=true},onSelect={if(type==C.TRACK_TYPE_TEXT)a.manualSubtitles()},onAutomatic={a.automaticSubtitles()},onDismiss={tracks=null})}
    if(speechSetup)SpeechSetup(a){speechSetup=false}
    if(subtitleSources)SubtitleSources(a){subtitleSources=false}
    if(more)Modal("Playback options",{more=false}) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){EzButton("Quality",Icons.Outlined.HighQuality,onClick={more=false;tracks=C.TRACK_TYPE_VIDEO});EzButton("Sleep timer",Icons.Outlined.Bedtime,onClick={more=false;sleep=true})}
        EzButton(when(resize){AspectRatioFrameLayout.RESIZE_MODE_FIT->"Picture: Fit";AspectRatioFrameLayout.RESIZE_MODE_FILL->"Picture: Fill";else->"Picture: Zoom"},Icons.Outlined.AspectRatio,onClick={resize=when(resize){AspectRatioFrameLayout.RESIZE_MODE_FIT->AspectRatioFrameLayout.RESIZE_MODE_FILL;AspectRatioFrameLayout.RESIZE_MODE_FILL->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FIT};more=false})
        if(c.kind==Kind.LIVE)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){EzButton("Record",Icons.Outlined.FiberManualRecord,onClick={a.permissions();RecordingService.start(a,a.store,c);more=false;a.message="Recording for 60 minutes"});EzButton("Multiview",Icons.Outlined.GridView,onClick={more=false;if(multi.size<3)add=true else a.message="Four-screen maximum"});if(multi.isNotEmpty())EzButton("Single screen",onClick={multi=emptyList();activeAudio=c.id;more=false})}
        EzButton("External player",Icons.Outlined.OpenInNew,onClick={more=false;a.scope.launch {try{val resolved=withContext(Dispatchers.IO){Sources.resolve(s.providers.firstOrNull {it.id==c.provider},c)};val uri=if(resolved.url.startsWith("file:"))androidx.core.content.FileProvider.getUriForFile(a,a.packageName+".files",java.io.File(Uri.parse(resolved.url).path!!))else Uri.parse(resolved.url);val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.putExtra("headers",resolved.headers.flatMap {listOf(it.key,it.value)}.toTypedArray());a.startActivity(Intent.createChooser(intent,"Play with"));p.pause()}catch(_:Exception){a.message="No compatible external player is installed"}}})
        EzButton("Stop playback",Icons.Outlined.Close,onClick={more=false;a.stopPlayback()})
    }
    if(sleep)Modal("Sleep timer",{sleep=false}){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(0,15,30,60,90).forEach {minutes->EzButton(if(minutes==0)"Off" else "$minutes min",onClick={a.sleep(minutes);sleep=false})}}}
    if(add)Modal("Add a live screen",{add=false}){Text("Each screen uses a provider connection. Select a tile to hear its audio.",color=Muted,fontSize=12.sp);LazyColumn(Modifier.heightIn(max=290.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(choices.size,key={choices.catalogLabel(it).id}){index->val channel=choices[index];EzButton(channel.name,modifier=Modifier.fillMaxWidth(),onClick={a.guarded(channel){multi=multi+channel;add=false}})}}}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun MultiVideo(a: MainActivity,c: Channel,audible: Boolean) {
    var resolved by remember(c.id) {mutableStateOf<Channel?>(null)};var error by remember {mutableStateOf(false)}
    LaunchedEffect(c.id) {runCatching {withContext(Dispatchers.IO){Sources.resolve(a.store.state.value.providers.firstOrNull {it.id==c.provider},c)}}.onSuccess {resolved=it}.onFailure {error=true}}
    resolved?.let {channel->val player=remember(c.id) {Playback.create(a,channel,false,true)};DisposableEffect(player){val listener=object:Player.Listener{override fun onPlayerError(e:PlaybackException){error=true}};player.addListener(listener);onDispose{player.release()}};LaunchedEffect(audible){player.volume=if(audible) 1f else 0f};VideoSurface(player,Modifier.fillMaxSize(),false)}
    if(error) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Stream unavailable. Check your connection limit.",color=Color.White,fontSize=12.sp)}
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun TrackDialog(p: ExoPlayer,type: Int,onAdd: ()->Unit={},onSelect: ()->Unit={},onAutomatic: ()->Unit={},onDismiss: ()->Unit) {
    var currentTracks by remember(p) {mutableStateOf(p.currentTracks)};DisposableEffect(p) {val listener=object:Player.Listener {override fun onTracksChanged(tracks: Tracks){currentTracks=tracks}};p.addListener(listener);onDispose {p.removeListener(listener)}}
    val groups=currentTracks.groups.filter {it.type==type};Modal(when(type){C.TRACK_TYPE_AUDIO->"Audio tracks";C.TRACK_TYPE_TEXT->"Subtitles & captions";else->"Streaming quality"},onDismiss) {
        Text(if(type==C.TRACK_TYPE_VIDEO) "Auto adapts to the network and device. Resolution and HDR depend on the source and TV." else "Choose a detected stream or provider track.",color=Muted,fontSize=12.sp)
        EzButton(if(type==C.TRACK_TYPE_TEXT) "Subtitles off" else "Automatic",primary=true,onClick={onSelect();p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,type==C.TRACK_TYPE_TEXT).build();onDismiss()})
        if(type==C.TRACK_TYPE_TEXT) {EzButton("Automatic English",onClick={onAutomatic();onDismiss()});EzButton("Find or add subtitles",onClick=onAdd)}
        if(groups.isEmpty())Text("No selectable tracks are available yet.",color=Muted,fontSize=12.sp)
        groups.forEach {group->for(i in 0 until group.length) {if(group.isTrackSupported(i)) {val f=group.getTrackFormat(i);val label=if(type==C.TRACK_TYPE_VIDEO) "${f.width} × ${f.height}"+(if(f.bitrate>0) " · ${f.bitrate/1000} kbps" else "") else listOf(f.label,f.language,if(f.channelCount>0) "${f.channelCount} channels" else null).filterNotNull().joinToString(" · ").ifBlank {"Track ${i+1}"};EzButton(label+(if(group.isTrackSelected(i)) "  ✓" else ""),onClick={onSelect();p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i)).build();onDismiss()})}}}
    }
}
