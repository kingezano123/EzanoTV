package tv.ezano.app

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.channels.Channel as SaveChannel
import kotlinx.coroutines.sync.withLock

private val KEY_MAGIC="EZK1".toByteArray(Charsets.US_ASCII)

class EzanoApp: Application() {
    lateinit var store: Store
    override fun onCreate() { super.onCreate(); store=Store(this) }
}
class Store(private val context: Context) {
    val events = EventSearchIndex(context)
    val state = MutableStateFlow(Library())
    /** Settings, playlists, favorites and progress are open; the interface can be shown. */
    val ready = MutableStateFlow(false)
    /** Channels, movies and shows are open. Until then screens show a loading state, never an empty library. */
    val catalogReady = MutableStateFlow(false)
    val guideReady = MutableStateFlow(false)
    /** The previous launch's Home shelves, shown instantly while the catalog opens. */
    val home = MutableStateFlow(HomeSnapshot())
    val error = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val guideBusy = MutableStateFlow(false)
    val guideStatus = MutableStateFlow("")
    private val guideLock=kotlinx.coroutines.sync.Mutex()
    private val guideJobs=java.util.concurrent.atomic.AtomicInteger(0)
    private val guideSchedule=context.getSharedPreferences("guide-schedule",Context.MODE_PRIVATE)
    private val refreshLock=kotlinx.coroutines.sync.Mutex()
    private val diskLock=kotlinx.coroutines.sync.Mutex()
    private val saves=SaveChannel<Unit>(SaveChannel.CONFLATED)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    // 0.3.1 sealed the whole library with the Keystore key, one hardware call per 64 KB frame.
    // On Fire TV that made every launch and every save take minutes. Sections are now sealed in
    // software with a random data key, and only that 32-byte key is wrapped by the Keystore.
    private val legacyFile=AtomicFile(File(context.filesDir,"library.vault"))
    private val vault=File(context.filesDir,"vault").apply {mkdirs()}
    private val keyFile=AtomicFile(File(vault,"key.bin"))
    private val profileFile=AtomicFile(File(vault,"profile.ezv"))
    private val catalogFile=AtomicFile(File(vault,"catalog.ezv"))
    private val guideFile=AtomicFile(File(vault,"guide.ezv"))
    private val homeFile=AtomicFile(File(vault,"home.ezv"))
    val opening = MutableStateFlow(true)
    /** Shown under the startup spinner, e.g. the one-time upgrade progress. Never asks to close the app. */
    val openingStatus = MutableStateFlow("")
    private val loadRunning=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var loadFailed=false
    private var savedRevision=-1L
    private var savedChannels: List<Channel>?=null
    private var savedPrograms: List<Program>?=null
    private var savedHome: HomeSnapshot?=null
    private val keyLock=Any()
    @Volatile private var dataKey: SecretKey?=null
    init {
        scope.launch {
            var indexed: List<Program>? = null
            combine(state.map {it.programs}.distinctUntilChanged {old,new->old===new},guideReady) {programs,loaded->programs to loaded}.collectLatest {(programs,loaded) ->
                // Event search is optional: index after startup has settled, and skip unchanged guides.
                if(loaded && programs !== indexed) {
                    delay(20000)
                    try { events.prepare(programs); indexed=programs }
                    catch(e: CancellationException) { throw e }
                    catch(_:Exception) { /* The cache is rebuildable; explicit search can retry. */ }
                }
            }
        }
        scope.launch {
            retryOpening()
            for(request in saves) {
                delay(300)
                while(busy.value) delay(100)
                while(saves.tryReceive().isSuccess) {}
                if(!flush() && !loadFailed) {delay(5000);saves.trySend(Unit)}
            }
        }
    }
    private fun AtomicFile.present()=baseFile.exists() || File(baseFile.path+".bak").exists()
    suspend fun awaitCatalog() {catalogReady.first {it}}
    suspend fun awaitLibrary() {catalogReady.first {it};guideReady.first {it}}
    fun retryOpening() {
        if(ready.value || !loadRunning.compareAndSet(false,true))return
        opening.value=true;openingStatus.value="";error.value=""
        scope.launch {
            var split=false
            try {
                diskLock.withLock {
                    when {
                        profileFile.present() -> {openProfile();split=true}
                        legacyFile.present() -> migrateLegacy()
                        else -> opened(Library(),catalog=true,guide=true)
                    }
                }
            } catch(e:CancellationException){throw e}
              catch(_:OutOfMemoryError){loadFailed=true;error.value="Not enough memory to open your saved library. Retry, or restart the TV. Your saved data has been preserved."}
              catch(_:Exception){loadFailed=true;error.value="Your saved library could not be opened. Retry, or restore an encrypted backup. Your playlists, favorites and progress have been preserved."}
            finally{opening.value=false;openingStatus.value="";loadRunning.set(false)}
            if(split && ready.value) try {openSections()} catch(e:CancellationException){throw e} catch(_:Throwable){
                synchronized(this@Store) {catalogReady.value=true;guideReady.value=true}
                error.value="Your saved channels, movies and shows could not be opened. Open Playlists and refresh to download them again."
            }
        }
    }
    private fun opened(saved: Library,catalog: Boolean,guide: Boolean) {
        synchronized(this) {
            state.value=saved.copy(revision=state.value.revision+1,recordings=saved.recordings.map {if(it.status=="recording")it.copy(status="interrupted",error="The previous recording process was interrupted")else it})
            savedRevision=state.value.revision;savedChannels=state.value.channels;savedPrograms=state.value.programs
            loadFailed=false
            if(catalog) catalogReady.value=true
            if(guide) guideReady.value=true
            ready.value=true
        }
    }
    /** Small: settings, playlists, favorites, progress, recordings. Opens in well under a second. */
    private fun openProfile() {
        val key=dataKey(false)
        val profile=readSection(profileFile,key) {input->GZIPInputStream(input,65536).reader().use {LibraryStreams.read(it)}}
        home.value=runCatching {if(homeFile.present()) readSection(homeFile,key) {VaultFormat.readHome(java.io.DataInputStream(it))}.also {savedHome=it} else null}.getOrNull()?:HomeSnapshot()
        opened(profile.copy(channels=emptyList(),programs=emptyList()),catalog=false,guide=false)
    }
    /** The catalog and guide open after the interface is visible. Failures keep the files for a later refresh. */
    private fun openSections() {
        val key=dataKey(false)
        val catalog=try {if(catalogFile.present()) readSection(catalogFile,key) {VaultFormat.readCatalog(java.io.DataInputStream(it))} else CompactCatalog.Builder().build()}
            catch(e:CancellationException){throw e}
            catch(_:Throwable){error.value="Your saved channels, movies and shows could not be opened. Open Playlists and refresh to download them again.";null}
        synchronized(this) {
            val loaded=catalog?:CompactCatalog.Builder().build()
            state.value=state.value.copy(channels=loaded);savedChannels=loaded
            catalogReady.value=true
        }
        val guide=try {if(guideFile.present()) readSection(guideFile,key) {VaultFormat.readGuide(java.io.DataInputStream(it))} else CompactGuide.Builder().build()}
            catch(e:CancellationException){throw e}
            catch(_:Throwable){null}
        synchronized(this) {
            val loaded=guide?:CompactGuide.Builder().build()
            state.value=state.value.copy(programs=loaded);savedPrograms=loaded
            guideReady.value=true
        }
    }
    /** One-time upgrade from the single 0.3.x vault. It is slow on some TVs, so progress stays visible. */
    private fun migrateLegacy() {
        openingStatus.value="Speeding up your library · one-time upgrade"
        val saved=readLegacy()
        opened(saved,catalog=true,guide=true)
        synchronized(this) {savedRevision=-1;savedChannels=null;savedPrograms=null}
        openingStatus.value="Saving the faster library…"
        if(persist()) {legacyFile.delete();File(context.filesDir,"library.vault.bak").delete()}
    }
    private fun key(create:Boolean=true):SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (ks.getKey("ezano-library",null) as? SecretKey)?.let {return it}
        check(create){"Saved library key is unavailable"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder("ezano-library",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    private fun dataKey(create: Boolean): SecretKey {
        dataKey?.let {return it}
        synchronized(keyLock) {
            dataKey?.let {return it}
            val result=if(keyFile.present()) {
                val sealed=keyFile.readFully()
                require(sealed.size>16 && sealed.copyOfRange(0,4).contentEquals(KEY_MAGIC)) {"Invalid library key"}
                val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,sealed.copyOfRange(4,16)))
                SecretKeySpec(c.doFinal(sealed.copyOfRange(16,sealed.size)),"AES")
            } else {
                check(create) {"Saved library key is unavailable"}
                val raw=ByteArray(32).also {SecureRandom().nextBytes(it)}
                val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
                val sealed=KEY_MAGIC+c.iv+c.doFinal(raw)
                var out: java.io.FileOutputStream?=null
                try {out=keyFile.startWrite();out.write(sealed);keyFile.finishWrite(out)} catch(e:Exception) {keyFile.failWrite(out);throw e}
                SecretKeySpec(raw,"AES")
            }
            dataKey=result
            return result
        }
    }
    private fun <T> readSection(file: AtomicFile,key: SecretKey,body: (java.io.InputStream)->T): T =
        file.openRead().buffered(65536).use {raw->ChunkedVault.input(raw,key,ChunkedVault.DATA_KEY_FRAMES).use {frames->body(frames.buffered(65536))}}
    private fun writeSection(file: AtomicFile,key: SecretKey,body: (java.io.OutputStream)->Unit) {
        var out: java.io.FileOutputStream?=null
        try {
            out=file.startWrite()
            val keepOpen=object:java.io.FilterOutputStream(out) {
                override fun write(bytes: ByteArray,offset: Int,length: Int){this.out.write(bytes,offset,length)}
                override fun close(){flush()}
            }
            ChunkedVault.output(keepOpen,key,ChunkedVault.DATA_KEY_FRAMES).buffered(65536).use(body)
            file.finishWrite(out)
        } catch(e:Exception) {file.failWrite(out);throw e}
    }
    private fun decrypt(b: ByteArray): String { val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,b.copyOfRange(0,12))); return String(c.doFinal(b.copyOfRange(12,b.size))) }
    private fun readLegacy(): Library {
        val total=legacyFile.baseFile.length().coerceAtLeast(1)
        var read=0L;var shown=-1
        fun counted(raw:java.io.InputStream)=object:java.io.FilterInputStream(raw){
            override fun read():Int=super.read().also {if(it>=0) advance(1)}
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int=this.`in`.read(bytes,offset,length).also {if(it>0) advance(it)}
            fun advance(n:Int){read+=n;val percent=(read*100/total).toInt().coerceIn(0,99);if(percent!=shown){shown=percent;openingStatus.value="Speeding up your library · one-time upgrade · $percent%"}}
        }.buffered(65536)
        return legacyFile.openRead().use {raw->
            counted(raw).use {input->
                input.mark(4);val header=ByteArray(4);java.io.DataInputStream(input).readFully(header);input.reset()
                when(String(header,Charsets.US_ASCII)){
                    "EZV3"->readFramed(input)
                    "EZV2"->{
                        // Authenticate old GCM separately from inflating catalog/guide data.
                        // The staging file is encrypted with bounded frames; no plaintext is left on disk.
                        val staged=File.createTempFile("vault-migration-",".encrypted",context.cacheDir)
                        try{stageLegacy(input,staged);staged.inputStream().buffered(65536).use {readFramed(it)}}finally{staged.delete()}
                    }
                    else->LibraryStreams.read(decrypt(input.readBytes()).reader())
                }
            }
        }
    }
    private fun readFramed(input:java.io.InputStream):Library = ChunkedVault.input(input,key(false)).use {frames->GZIPInputStream(frames).reader().use {LibraryStreams.read(it)}}
    private fun stageLegacy(input:java.io.InputStream,target:File){
        val data=java.io.DataInputStream(input);data.skipBytes(4);val iv=ByteArray(12);data.readFully(iv)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv))
        target.outputStream().buffered(65536).use {out->ChunkedVault.output(out,key()).use {frames->
            val bytes=ByteArray(65536)
            while(true){val n=data.read(bytes);if(n<0)break;cipher.update(bytes,0,n)?.let {frames.write(it)}}
            frames.write(cipher.doFinal())
        }}
    }
    @Synchronized fun update(change: (Library)->Library) {
        if(!ready.value || loadFailed) return
        val previous=state.value
        state.value=change(previous).copy(revision=previous.revision+1)
        saves.trySend(Unit)
    }
    fun saveHome(snapshot: HomeSnapshot) {if(snapshot!=home.value){home.value=snapshot;saves.trySend(Unit)}}
    // Disk work never holds the lock used by UI updates. Only changed sections are rewritten:
    // a favorite or watch position saves a few kilobytes, not the whole catalog and guide.
    private fun persist(latest: Library?=null): Boolean {
        // Sections still opening are never written: read the snapshot and flags together.
        val (snapshot,catalogOpen,guideOpen)=synchronized(this) {Triple(latest?:state.value,catalogReady.value,guideReady.value)}
        if(loadFailed || !ready.value) return false
        try {
            val key=dataKey(true)
            if(catalogOpen && snapshot.channels !== savedChannels) {
                val catalog=VaultFormat.asCatalog(snapshot.channels)
                writeSection(catalogFile,key) {out->java.io.DataOutputStream(out).let {VaultFormat.writeCatalog(it,catalog);it.flush()}}
                savedChannels=snapshot.channels
            }
            if(guideOpen && snapshot.programs !== savedPrograms) {
                val guide=VaultFormat.asGuide(snapshot.programs)
                writeSection(guideFile,key) {out->java.io.DataOutputStream(out).let {VaultFormat.writeGuide(it,guide);it.flush()}}
                savedPrograms=snapshot.programs
            }
            if(savedRevision!=snapshot.revision) {
                writeSection(profileFile,key) {out->GZIPOutputStream(out,65536).let {zip->val writer=zip.writer();LibraryStreams.write(snapshot.copy(channels=emptyList(),programs=emptyList()),writer);writer.flush();zip.finish()}}
                savedRevision=snapshot.revision
            }
            val shelves=home.value
            if(shelves!==savedHome && !shelves.isEmpty) {
                writeSection(homeFile,key) {out->java.io.DataOutputStream(out).let {VaultFormat.writeHome(it,shelves);it.flush()}}
                savedHome=shelves
            }
            return true
        } catch(_:Exception) {error.value="Your library could not be saved. Free device storage, then refresh to retry.";return false}
    }
    suspend fun flush(): Boolean = withContext(Dispatchers.IO) {diskLock.withLock {persist()}}
    fun restore(raw: String) { check(!opening.value){"Wait for the current saved-library read to finish"}; val restored=LibraryStreams.read(raw.reader());synchronized(this){state.value=restored.copy(revision=state.value.revision+1);savedRevision=-1;savedChannels=null;savedPrograms=null;loadFailed=false;error.value="";catalogReady.value=true;guideReady.value=true;ready.value=true};saves.trySend(Unit) }
    private fun guideDue(p: Provider): Boolean = GuideRefreshPolicy.due(p.enabled,guideSchedule.getBoolean("blocked:${p.id}",false),
        guideSchedule.getLong("attempt:${p.id}",state.value.lastGuideSync),0L,state.value.prefs.guideHours,System.currentTimeMillis())
    // Automatic work NEVER imports a catalog. A failed guide waits for a manual retry,
    // including after process death; it cannot repeatedly rebuild the visible library.
    fun shouldRefresh(): Boolean = ready.value && catalogReady.value && guideReady.value && !loadFailed && !busy.value && !guideBusy.value &&
        state.value.channels.isNotEmpty() && state.value.providers.any(::guideDue)
    suspend fun refreshGuideAutomatically() { if(shouldRefresh()) submitGuide(null,true).await() }
    suspend fun refreshGuide(provider: Provider? = null): Boolean = submitGuide(provider?.let(::listOf),false).await()
    private fun submitGuide(providers: List<Provider>?,automatic: Boolean): Deferred<Boolean> {
        guideJobs.incrementAndGet();guideBusy.value=true
        return scope.async {
            try {guideLock.withLock {updateGuide(providers,automatic)}}
            finally {if(guideJobs.decrementAndGet()==0) guideBusy.value=false}
        }
    }
    // Imports belong to the application, so closing a dialog/activity cannot cancel a save.
    suspend fun refresh(provider: Provider? = null): Boolean = scope.async {refreshLibrary(provider)}.await()
    private suspend fun refreshLibrary(provider: Provider?) = withContext(Dispatchers.IO) {
        if(loadFailed) {
            error.value="The saved library needs recovery. Restore your encrypted backup before refreshing."
            return@withContext false
        }
        if(!ready.value) {error.value="Your saved library is still opening. Try again shortly.";return@withContext false}
        progress.value="Opening your saved library…";awaitLibrary()
        if(!refreshLock.tryLock()) return@withContext false
        busy.value=true;error.value="";var connected=false
        val guideProviders=mutableListOf<Provider>()
        try {
            val selected=provider?.let { listOf(it) } ?: state.value.providers.filter { it.enabled }
            for(p in selected) { try {
                val content=Sources.importCatalog(p) {progress.value=it}
                val previous=state.value.channels
                val warnings=content.warnings.toMutableList()
                val returnedKinds=content.channels.catalogLabels().map {it.kind}.toSet()
                val previousKinds=previous.catalogLabels().filter {it.provider==p.id}.map {it.kind}.toSet()
                val emptyKinds=previousKinds-returnedKinds
                val retainedKinds=content.failedKinds+emptyKinds
                require(content.channels.isNotEmpty() || previousKinds.isNotEmpty()) {"No catalogs available"}
                if(emptyKinds.isNotEmpty()) warnings+="The provider returned an empty section. Its saved titles have been kept; refresh later to retry."
                val mergedCatalog=if(retainedKinds.isEmpty() && state.value.providers.none {it.id!=p.id}) content.channels else CompactCatalog.Builder().apply {
                    previous.forEach {if(it.provider!=p.id || it.kind in retainedKinds) add(it)}
                    addAll(content.channels)
                }.build()
                update {old->old.copy(providers=old.providers.filterNot {it.id==p.id}+p,channels=mergedCatalog)}
                progress.value="Saving channels, movies and shows…"
                if(!flush()) return@withContext false
                connected=true
                guideProviders+=p
                if(warnings.isNotEmpty()) error.value="${p.name}: ${warnings.joinToString(" ")}"
            } catch(e: Exception) {if(e is CancellationException) throw e;error.value="${p.name}: ${Sources.friendlyError(e)}" }
            }
            if(connected) {progress.value="100% · Library saved. Ready to watch.";submitGuide(guideProviders,false)}
            connected
        } finally {if(!connected) progress.value="";busy.value=false;refreshLock.unlock()}
    }

    private suspend fun updateGuide(requested: List<Provider>?,automatic: Boolean): Boolean {
        if(!ready.value || loadFailed) return false
        awaitLibrary()
        val providers=requested?:state.value.providers.filter {it.enabled}
        var completed=false
        for(p in providers) {
            if(state.value.providers.none {it==p && it.enabled} || (automatic && !guideDue(p))) continue
            val catalog=state.value.channels
            val live=catalog.selectCatalog {it.provider==p.id && it.kind==Kind.LIVE}
            if(live.isEmpty()) continue
            // Mark in-flight attempts as blocked before networking. Even an interrupted
            // process cannot retry a broken guide on every launch.
            guideSchedule.edit().putLong("attempt:${p.id}",System.currentTimeMillis()).putBoolean("blocked:${p.id}",true).commit()
            guideStatus.value="Updating TV guide in the background · You can watch now"
            try {
                val result=Sources.guideResult(p,live,state.value.prefs.retentionDays)
                refreshLock.withLock {
                    if(state.value.providers.none {it==p && it.enabled}) return@withLock
                    if(result.programs.isEmpty()) {
                        guideStatus.value="TV guide is unavailable · Saved channels are ready · Retry guide in Settings"
                        return@withLock
                    }
                    val snapshot=state.value
                    val now=System.currentTimeMillis()
                    val valid=snapshot.channels.catalogLabels().filter {it.kind==Kind.LIVE}.map {it.id}.toHashSet()
                    val merged=mergeSavedGuide(result.programs,snapshot.programs,valid,now-snapshot.prefs.retentionDays*86400000L)
                    // Preserve the catalog object, filters, selected channel and scroll position.
                    update {it.copy(programs=merged,lastGuideSync=if(result.complete && result.programs.isNotEmpty()) now else it.lastGuideSync)}
                    val saved=flush()
                    val success=saved && result.complete && result.programs.isNotEmpty()
                    guideSchedule.edit().putBoolean("blocked:${p.id}",!success).commit()
                    guideStatus.value=when {
                        !saved -> "Guide could not be saved · Your saved channels are ready"
                        success -> "TV guide saved · ${result.programs.size} programs"
                        result.programs.isNotEmpty() -> "Partial guide saved · All channels are ready · Retry guide in Settings"
                        else -> "TV guide is unavailable · Saved channels are ready · Retry guide in Settings"
                    }
                    completed=completed || (saved && result.programs.isNotEmpty())
                }
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                guideStatus.value="TV guide is unavailable · Saved channels are ready · Retry guide in Settings"
            }
        }
        return completed
    }
    fun removeProvider(id: String) { scope.launch { awaitLibrary(); refreshLock.withLock {
        val snapshot=state.value
        val canceled=snapshot.recordings.filter {it.channel.provider==id && it.status in listOf("scheduled","recording")}
        canceled.forEach {Scheduler.cancel(context,it)}
        RecordingService.stopProvider(context,id)
        val ids=snapshot.channels.catalogLabels().filter {it.provider==id}.map {it.id}.toHashSet()
        val channels=snapshot.channels.selectCatalog {it.provider!=id}
        val programs=CompactGuide.Builder().apply {snapshot.programs.forEach {if(it.channelId !in ids) add(it)}}.build()
        update {s->s.copy(providers=s.providers.filterNot {it.id==id},channels=channels,programs=programs,
            recordings=s.recordings.map {if(it in canceled) it.copy(status="cancelled") else it},
            favorites=s.favorites-ids,hidden=s.hidden-ids,locked=s.locked-ids,recent=s.recent.filterNot {it.provider==id},positions=s.positions.filterKeys {it !in ids && snapshot.recent.none {c->c.provider==id && c.id==it}})}
        guideSchedule.edit().remove("attempt:$id").remove("blocked:$id").apply()
    } } }
    fun favorite(c: Channel) = update { it.copy(favorites=if(c.id in it.favorites) it.favorites-c.id else it.favorites+c.id) }
    private data class VisibilityKey(val channels:Any,val providers:List<Provider>,val hidden:Set<String>,val hiddenGroups:Set<String>,val order:List<String>)
    private var visibilitySnapshot: VisibilityKey?=null
    private val visibilityCache=HashMap<Kind?,List<Channel>>()
    private val visibilityLock=Any()
    fun visible(kind: Kind?=null): List<Channel> {
        val s=state.value
        val key=VisibilityKey(uiKey(s.channels),s.providers,s.hidden,s.hiddenGroups,s.order)
        synchronized(visibilityLock) {
            if(visibilitySnapshot==key) visibilityCache[kind]?.let {return it}
        }
        val enabled=s.providers.filter {it.enabled}.map {it.id}.toSet()
        val result=s.channels.selectCatalog {it.provider in enabled && (kind==null || it.kind==kind) && it.id !in s.hidden && it.group !in s.hiddenGroups}.orderChannels(s.order.withIndex().associate {it.value to it.index}).englishFirst()
        synchronized(visibilityLock) {
            if(visibilitySnapshot!=key) {visibilityCache.clear();visibilitySnapshot=key}
            visibilityCache[kind]=result
        }
        return result
    }
    // Imported/restored guides contain a channel index; composing a row never indexes the full guide.
    fun programs(c: Channel): List<Program> {val list=state.value.programs;return (list as? CompactGuide)?.forChannel(c.id) ?: list.filter {it.channelId==c.id}.sortedBy {it.start}}
    fun now(c: Channel): Program? {val t=System.currentTimeMillis();return programs(c).firstOrNull {it.start<=t && it.end>t}}
    fun needsPin(c: Channel): Boolean { val s=state.value; return s.prefs.pinHash.isNotEmpty() && (c.id in s.locked || c.group.contains("adult",true) || c.group.contains("xxx",true) || (if(c.kind==Kind.LIVE) "Live TV" else if(c.kind==Kind.MOVIE) "Movies" else "Series") in s.prefs.lockedSections) }
    suspend fun checkPin(pin: String): Boolean = withContext(Dispatchers.IO) { val p=state.value.prefs; if(p.pinBlockedUntil>System.currentTimeMillis()) false else { val valid=Crypto.verify(pin,p.pinSalt,p.pinHash); update { it.copy(prefs=it.prefs.copy(pinFailures=if(valid) 0 else p.pinFailures+1,pinBlockedUntil=if(valid) 0 else if(p.pinFailures>=4) System.currentTimeMillis()+30000 else 0)) }; valid } }
    suspend fun setPin(pin: String) = withContext(Dispatchers.IO) { require(pin.matches(Regex("[0-9]{4,8}"))); val salt=Crypto.salt(); val hash=Crypto.b64(Crypto.derive(pin,salt)); update { it.copy(prefs=it.prefs.copy(pinHash=hash,pinSalt=Crypto.b64(salt),pinFailures=0,pinBlockedUntil=0)) } }
}
