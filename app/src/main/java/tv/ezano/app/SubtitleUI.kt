package tv.ezano.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

/** Free, on-device live captions: one button downloads the English model (once) and switches captions on. */
@Composable fun SpeechSetup(a:MainActivity,onDismiss:()->Unit){
    var installed by remember {mutableStateOf(SpeechModel.installed(a))};var enabled by remember {mutableStateOf(a.speechEnabled())}
    var busy by remember {mutableStateOf(false)};var status by remember {mutableStateOf("")}
    fun turnOn(){
        if(busy)return
        if(installed){enabled=true;a.setSpeechEnabled(true);onDismiss();return}
        busy=true
        a.scope.launch {
            try{withContext(Dispatchers.IO){SpeechModel.install(a){value->a.scope.launch {status=value}}};installed=true;enabled=true;a.setSpeechEnabled(true);a.message="Live captions are on";onDismiss()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){status=e.message?.takeIf {it.length<120}?:"Setup failed. Check your connection and free storage, then retry."}
            finally{busy=false}
        }
    }
    Modal("Live captions",onDismiss){
        Text("Ezano listens to the program's audio and writes English captions as people speak — on any channel, movie or show, even when there are no subtitles. It is free and runs entirely on this TV: no microphone, no account, nothing uploaded.",color=Color.White,fontSize=13.sp,lineHeight=19.sp)
        Text("Captions appear within about a second of speech. Music, crowd noise and strong accents lower accuracy, and speech in other languages is not shown. When the stream has its own subtitles, those are used instead.",color=Muted,fontSize=12.sp,lineHeight=17.sp)
        if(!installed)Text("First use downloads a 40 MB English speech model (needs 200 MB free).",color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            if(enabled && installed)EzButton("Turn live captions off",primary=true,onClick={enabled=false;a.setSpeechEnabled(false);onDismiss()})
            else EzButton(if(busy)"Setting up…" else if(installed)"Turn live captions on" else "Download & turn on",primary=true,onClick={turnOn()})
        }
        if(status.isNotBlank())Text(status,color=Color.White,fontSize=12.sp)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=LocalAccent.current)
    }
}

@Composable fun SubtitleSources(a: MainActivity,onDismiss: ()->Unit) {
    val s by a.store.libraryState();val scope=rememberCoroutineScope();val channel=a.playing?:return
    var mode by remember {mutableStateOf("Find subtitles")};var query by remember {mutableStateOf(SubtitleIdentity.query(channel))};var language by remember {mutableStateOf(s.prefs.subtitleLanguage)};var key by remember {mutableStateOf(s.prefs.subtitleApiKey)};var url by remember {mutableStateOf("")};var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)};var searched by remember {mutableStateOf(false)};var matches by remember {mutableStateOf<List<SubtitleMatch>>(emptyList())}
    fun load(address: suspend ()->String) {
        if(busy)return
        busy=true;error=""
        scope.launch {try {val text=withContext(Dispatchers.IO){SubtitleFiles.download(address())};if(a.playing?.id==channel.id){a.manualSubtitles();a.attachSubtitle(text,language);onDismiss()}else error="The playing title changed. Open subtitles again."}catch(e:Exception){error=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty() else "Could not download subtitles. Check your connection and try again."}finally{busy=false}}
    }
    Modal("Find or add subtitles",onDismiss) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Find subtitles","Subtitle link","Local file").forEach {tab->EzButton(tab,selected=mode==tab,onClick={mode=tab;error=""})}}
        if(mode=="Find subtitles") {
            Text("Search OpenSubtitles for a matching movie or episode. Free API use has daily download limits. Add an API key from your OpenSubtitles account; it is saved encrypted on this device.",color=Muted,fontSize=12.sp)
            Field("Movie / episode / release name",query,{query=it})
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.weight(1f)){Field("Language (en, es, fr…)",language,{language=it})};Box(Modifier.weight(2f)){Field("OpenSubtitles API key",key,{key=it},true)}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                EzButton(if(busy) "Searching…" else "Search",primary=true,onClick={if(!busy){busy=true;error="";scope.launch {try {val result=withContext(Dispatchers.IO){OpenSubtitles().search(query,language,key)};matches=result;searched=true;a.store.update {it.copy(prefs=it.prefs.copy(subtitleApiKey=key.trim(),subtitleLanguage=language.trim().ifBlank {"en"}))}}catch(e:Exception){error=if(e is IllegalArgumentException||e is IllegalStateException)e.message.orEmpty() else "Search unavailable. Check your connection."}finally{busy=false}}}})
                EzButton("Get a free API key",onClick={try{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.opensubtitles.com/en/consumers")))}catch(_:Exception){error="On your phone or computer, visit opensubtitles.com → Profile → API Consumers."}})
            }
            if(searched && matches.isEmpty())Text("No matches. Try the original title and check the language code.",color=Muted,fontSize=12.sp)
            if(matches.isNotEmpty()) {
                Text("Choose a release matching your video. Timing can vary between releases.",color=Muted,fontSize=11.sp)
                LazyColumn(Modifier.heightIn(max=180.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){items(matches.distinctBy {it.fileId},key={it.fileId}) {match->EzButton("${match.language.uppercase()} · ${match.label.take(70)}${if(match.captions) " · CC" else ""}",modifier=Modifier.fillMaxWidth(),onClick={load {OpenSubtitles().downloadUrl(match,key)}})}}
            }
        } else if(mode=="Subtitle link") {
            Text("Paste a direct SRT or WebVTT download link. A webpage or ZIP link will not work.",color=Muted,fontSize=12.sp)
            Field("Subtitle URL",url,{url=it});Field("Language code",language,{language=it})
            EzButton(if(busy) "Loading…" else "Load subtitles",primary=true,onClick={load {url}})
        } else {
            Text("Choose a downloaded SRT or WebVTT file. Some Fire TV devices need a document-picker app; a direct subtitle link also works.",color=Muted,fontSize=12.sp)
            EzButton("Choose subtitle file",primary=true,onClick={a.chooseSubtitleFile();onDismiss()})
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=LocalAccent.current)
        if(error.isNotBlank())Text(error,color=Color(0xFFFF8995),fontSize=12.sp)
    }
}
