package tv.ezano.app

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: ComponentActivity() {
    val store get()=(application as EzanoApp).store
    var playing by mutableStateOf<Channel?>(null)
    var player by mutableStateOf<ExoPlayer?>(null)
    var fullPlayer by mutableStateOf(false)
    var playerControlsVisible by mutableStateOf(true)
    var playerControlsInteraction by mutableIntStateOf(0)
    var playerDialogOpen=false
    private var dismissingControlsBack=false
    var page by mutableStateOf("Home")
    var message by mutableStateOf("")
    var pinRequest by mutableStateOf<(() -> Unit)?>(null)
    var pendingImport by mutableStateOf(false)
    var focusImportedChannel by mutableStateOf(false)
    var focusCatalogSearch by mutableStateOf(false)
    private var session: MediaSession?=null
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var playbackJob: Job?=null
    private var subtitleJob: Job?=null
    private var subtitleLookupComplete=false
    var subtitleSelectionManual=false
    var speech by mutableStateOf<SpeechCaptions?>(null)
    private val speechSettings get()=getSharedPreferences("speech-captions",MODE_PRIVATE)
    fun speechEnabled()=speechSettings.getBoolean("enabled",false)
    fun setSpeechEnabled(value:Boolean){speechSettings.edit().putBoolean("enabled",value).apply();playing?.let {startPlayback(it)}}
    private var revealKey=0
    fun manualSubtitles(){subtitleSelectionManual=true;subtitleJob?.cancel();speech?.enable(false)}
    fun automaticSubtitles(){
        subtitleSelectionManual=false
        player?.let {p->p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT,false).setPreferredTextLanguages("en","eng").build();playing?.let {subtitleJob?.cancel();discoverSubtitles(it,p,force=true)}}
    }
    fun browsePlayback(){stopPlayback()}
    private var sleepJob: Job?=null
    private var backupPassword=""
    private var numberBuffer=""
    private var numberJob: Job?=null
    private var subtitleTarget: String?=null
    private val openSubtitle=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        val target=subtitleTarget;subtitleTarget=null
        if(uri!=null && target!=null) scope.launch {try {
            val text=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)?.use {SubtitleFiles.read(it)}?:error("File unavailable")}
            if(playing?.id==target) attachSubtitle(text,store.state.value.prefs.subtitleLanguage)
        } catch(_:Exception){message="Could not read subtitles. Choose an SRT or WebVTT file under 3 MB."}}
    }
    companion object { var active: WeakReference<MainActivity>?=null }
    private val createBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri-> if(uri!=null) scope.launch { val pass=backupPassword; backupPassword=""; try { withContext(Dispatchers.IO) { val encrypted=Crypto.backup(Json.encode(store.state.value),pass); contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }?:error("No output") }; message="Encrypted backup saved" } catch(_: Exception) { message="Backup could not be saved" } } else backupPassword="" }
    private val openBackup=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri-> if(uri!=null) scope.launch { val pass=backupPassword; backupPassword=""; try { val raw=withContext(Dispatchers.IO) { val bytes=contentResolver.openInputStream(uri)?.use { input->val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(32768); while(true) { val n=input.read(buf); if(n<0) break; require(out.size()+n<=128*1024*1024); out.write(buf,0,n) }; out.toByteArray() }?:error("No input"); Crypto.restore(bytes,pass).also { Json.decode(it) } }; stopPlayback(); store.state.value.recordings.forEach { Scheduler.cancel(this@MainActivity,it) }; withContext(Dispatchers.IO) { store.restore(raw); store.update { s-> s.copy(recordings=s.recordings.map { r->if(r.status=="recording") r.copy(status="interrupted",path="",error="Recording files are not part of a settings backup") else r.copy(path="") }) } }; store.state.value.recordings.filter { it.status=="scheduled" && it.end>System.currentTimeMillis() }.forEach { Scheduler.schedule(this@MainActivity,it) }; message="Backup restored. Recording files stay on their original device." } catch(_: Exception) { message="Restore failed. Check the passphrase and backup file." } } else backupPassword="" }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); active=WeakReference(this); WindowCompat.setDecorFitsSystemWindows(window,false); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); setContent { EzanoRoot(this) }; scope.launch { while(!store.ready.value) delay(30); val id=intent.getStringExtra("channel"); if(id!=null) requestChannel(id) else if(store.state.value.prefs.resumeLast) requestChannel(store.state.value.lastChannel); if(store.shouldRefresh()) store.refreshGuideAutomatically(); while(isActive) {delay(60000);if(active?.get()===this@MainActivity && store.shouldRefresh()) store.refreshGuideAutomatically()} } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra("channel")?.let { requestChannel(it) } }
    fun requestChannel(id: String) { scope.launch {withContext(Dispatchers.Default) {store.state.value.channels.selectCatalog {it.id==id}.firstOrNull()}?.let {play(it)}} }
    fun guarded(c: Channel,block: ()->Unit) { if(store.needsPin(c)) pinRequest=block else block() }
    fun admin(block: ()->Unit) { if(store.state.value.prefs.pinHash.isNotBlank()) pinRequest=block else block() }
    fun play(c: Channel) { guarded(c) { startPlayback(c) } }
    fun startPlayback(c: Channel) {
        subtitleJob?.cancel();playbackJob?.cancel(); playbackJob=scope.launch { try {
            val resolved=withContext(Dispatchers.IO) { Sources.resolve(store.state.value.providers.firstOrNull { it.id==c.provider },c) }
            savePosition(); session?.release(); player?.release();speech?.close();player=null; playing=c
            speech=if(speechEnabled() && SpeechModel.installed(this@MainActivity))SpeechCaptions(this@MainActivity) else null
            player=Playback.create(this@MainActivity,resolved,store.state.value.prefs.afr,subtitleLanguage=store.state.value.prefs.subtitleLanguage,autoSubtitles=store.state.value.prefs.autoSubtitles,speech=speech)
            if(c.kind!=Kind.LIVE) player?.seekTo(store.state.value.positions[c.id]?:0)
            subtitleSelectionManual=false;subtitleLookupComplete=false;discoverSubtitles(resolved,player!!);session=MediaSession.Builder(this@MainActivity,player!!).build(); showPlayerControls(); fullPlayer=true
            player!!.addListener(object:Player.Listener {
                override fun onTracksChanged(tracks:androidx.media3.common.Tracks){val selected=tracks.groups.any {g->g.type==androidx.media3.common.C.TRACK_TYPE_TEXT && g.isSelected};if(selected)speech?.enable(false) else if(subtitleLookupComplete && !subtitleSelectionManual)speech?.enable(true)}
                override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){speech?.playheadMs=newPosition.positionMs;speech?.reset()}
            })
            if(c.kind==Kind.LIVE) store.update { it.copy(lastChannel=c.id) }
            else store.update {it.copy(recent=(listOf(c)+it.recent.filterNot {old->old.id==c.id}).take(30))}
        } catch(e: CancellationException) {throw e} catch(_: Exception) { message="Could not open this stream. Check the provider and connection limit." } }
    }
    private fun discoverSubtitles(c: Channel,p: ExoPlayer,force:Boolean=false) {
        val prefs=store.state.value.prefs
        if(!prefs.autoSubtitles && !force){subtitleLookupComplete=true;speech?.enable(true);return}
        subtitleJob=scope.launch {
            try {
                // Wait for the manifest/container before considering external captions.
                withTimeoutOrNull(15000){while(p.playbackState!=Player.STATE_READY)delay(150)}
                delay(1500)
                fun hasPreferred()=p.currentTracks.groups.any {g->g.type==androidx.media3.common.C.TRACK_TYPE_TEXT && (0 until g.length).any {i->g.isTrackSupported(i) && SubtitleLanguages.matches(g.getTrackFormat(i).language,prefs.subtitleLanguage)}}
                if(playing?.id!=c.id || player!==p || subtitleSelectionManual || hasPreferred())return@launch
                val details=withContext(Dispatchers.IO){val provider=store.state.value.providers.firstOrNull {it.id==c.provider};if(c.kind==Kind.MOVIE && provider!=null) runCatching {Sources.details(provider,c)}.getOrDefault(c) else c}
                val text=withContext(Dispatchers.IO){
                    var found:String?=null
                    for(source in details.subtitleTracks.filter {SubtitleLanguages.matches(it.language,prefs.subtitleLanguage)}){try{found=SubtitleFiles.download(source.url);break}catch(e:CancellationException){throw e}catch(_:Exception){}}
                    if(found==null && prefs.subtitleApiKey.isNotBlank() && c.kind!=Kind.LIVE){val api=OpenSubtitles();val ep=SubtitleIdentity.episode(details);val match=api.search(SubtitleIdentity.query(details),prefs.subtitleLanguage,prefs.subtitleApiKey,ep?.first,ep?.second).firstOrNull {SubtitleIdentity.matches(details,it)};if(match!=null)found=SubtitleFiles.download(api.downloadUrl(match,prefs.subtitleApiKey))}
                    found
                }
                if(text!=null && player===p && playing?.id==c.id && !subtitleSelectionManual && !hasPreferred())attachSubtitle(text,prefs.subtitleLanguage,notify=false)
            } catch(e: CancellationException){throw e} catch(_:Exception){/* Caption failure never interrupts video. Manual lookup remains available. */}
            finally {if(player===p){subtitleLookupComplete=true;if(!subtitleSelectionManual && !p.currentTracks.groups.any {g->g.type==androidx.media3.common.C.TRACK_TYPE_TEXT && g.isSelected})speech?.enable(true)}}
        }
    }
    fun savePosition() { val c=playing?:return; val p=player?:return; if(c.kind!=Kind.LIVE && p.currentPosition>0) store.update { it.copy(positions=it.positions+(c.id to (if(p.duration>0 && p.duration-p.currentPosition<30000) 0 else p.currentPosition))) } }
    fun showPlayerControls() {playerControlsVisible=true;playerControlsInteraction++}
    fun hidePlayerControls() {playerControlsVisible=false}
    fun chooseSubtitleFile() {manualSubtitles();subtitleTarget=playing?.id;try{openSubtitle.launch(arrayOf("*/*"))}catch(_:ActivityNotFoundException){subtitleTarget=null;message="Install a document picker, or use a direct subtitle link."}}
    suspend fun attachSubtitle(text: String,language: String,notify: Boolean=true) {
        val target=playing?.id?:return;val p=player?:return
        val mime=SubtitleFiles.mime(text)
        val file=withContext(Dispatchers.IO){java.io.File(cacheDir,"subtitle-${java.util.UUID.randomUUID()}.${if(mime=="text/vtt") "vtt" else "srt"}").apply {writeText(text)}}
        if(playing?.id!=target || player!==p)return
        val item=p.currentMediaItem?:return;val position=p.currentPosition;val autoPlay=p.playWhenReady
        val subtitle=androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file)).setMimeType(mime).setLanguage(language.ifBlank {"en"}).setLabel("Imported · ${language.ifBlank {"en"}.uppercase()}").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage(language.ifBlank {"en"}).build()
        p.setMediaItem(item.buildUpon().setSubtitleConfigurations(item.localConfiguration?.subtitleConfigurations.orEmpty()+subtitle).build(),position);p.prepare();p.playWhenReady=autoPlay
        if(notify)message="Subtitles loaded"
    }
    fun stopPlayback() { sleepJob?.cancel();numberJob?.cancel();subtitleJob?.cancel();savePosition(); playbackJob?.cancel(); session?.release(); session=null; player?.release();speech?.close();speech=null; player=null; playing=null; fullPlayer=false;playerDialogOpen=false;dismissingControlsBack=false }
    fun backup(pass: String,restore: Boolean) { backupPassword=pass; try { if(restore) openBackup.launch(arrayOf("*/*")) else createBackup.launch("ezano-backup.ezano") } catch(_: ActivityNotFoundException) { backupPassword=""; message="This Fire TV needs a document picker to export or restore backups." } }
    fun sleep(minutes: Int) { sleepJob?.cancel(); if(minutes>0) sleepJob=scope.launch { delay(minutes*60000L); stopPlayback(); message="Sleep timer finished" }; message=if(minutes>0) "Playback will stop in $minutes minutes" else "Sleep timer turned off" }
    fun permissions() { if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),42) }
    override fun onResume() { super.onResume(); active=WeakReference(this); if(store.ready.value && store.shouldRefresh()) scope.launch {store.refreshGuideAutomatically()} }
    override fun onStop() { super.onStop(); active=null; savePosition();if(subtitleTarget!=null)player?.pause() else stopPlayback() }
    override fun onDestroy() { stopPlayback(); scope.cancel(); if(active?.get()===this) active=null; super.onDestroy() }
    // Android's public Activity key callback must intercept before PlayerView/Compose.
    // androidx.core marks its intermediate override library-only, unlike the platform API.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(revealKey!=0 && event.keyCode==revealKey){if(event.action==KeyEvent.ACTION_UP)revealKey=0;return true}
        // Handle the reveal before focused Android/Compose views consume D-pad events.
        if(event.keyCode==KeyEvent.KEYCODE_BACK && dismissingControlsBack) {if(event.action==KeyEvent.ACTION_UP)dismissingControlsBack=false;return true}
        if(fullPlayer && !playerDialogOpen && pinRequest==null) {
            if(event.keyCode==KeyEvent.KEYCODE_BACK && event.action==KeyEvent.ACTION_DOWN) {stopPlayback();dismissingControlsBack=true;return true}
            val reveal=event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MENU)
            // Android may consume the first down event when leaving touch mode;
            // accepting its up event too makes one remote press sufficient.
            if(reveal && !playerControlsVisible) {if(event.action==KeyEvent.ACTION_DOWN)revealKey=event.keyCode;showPlayerControls();return true}
            if(!playerControlsVisible && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){if(event.action==KeyEvent.ACTION_DOWN){if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT)player?.seekBack() else player?.seekForward();showPlayerControls();revealKey=event.keyCode};return true}
            if(playerControlsVisible && event.action==KeyEvent.ACTION_DOWN) playerControlsInteraction++
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN) {
            if(event.keyCode==KeyEvent.KEYCODE_MEDIA_REWIND){player?.seekBack();showPlayerControls();return true}
            if(event.keyCode==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){player?.seekForward();showPlayerControls();return true}
            if(event.keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { player?.let { if(it.isPlaying) it.pause() else it.play() }; return true }
            if(event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && fullPlayer) { numberBuffer+=(event.keyCode-KeyEvent.KEYCODE_0).toString(); message="Channel $numberBuffer"; numberJob?.cancel(); numberJob=scope.launch { delay(1000); val n=numberBuffer.toIntOrNull(); numberBuffer=""; val list=withContext(Dispatchers.Default) {store.visible(Kind.LIVE)}; if(n!=null && n in 1..list.size) play(list[n-1]) else message="Channel number not found" }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
