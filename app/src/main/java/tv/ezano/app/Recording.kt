package tv.ezano.app

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Scheduler {
    private fun pending(c: Context,r: Recording)=PendingIntent.getBroadcast(c,r.id.hashCode(),Intent(c,ScheduleReceiver::class.java).setAction("ezano.${r.id}").putExtra("id",r.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun exact(c: Context)=Build.VERSION.SDK_INT<31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun schedule(c: Context,r: Recording) { val alarms=c.getSystemService(AlarmManager::class.java); val at=if(r.reminder) (r.start-60000).coerceAtLeast(System.currentTimeMillis()+1000) else r.start.coerceAtLeast(System.currentTimeMillis()+1000); if(exact(c)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,r)) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,r)) }
    fun cancel(c: Context,r: Recording) { c.getSystemService(AlarmManager::class.java).cancel(pending(c,r)); RecordingService.stop(c,r.id) }
    fun channels(c: Context) { val n=c.getSystemService(NotificationManager::class.java); n.createNotificationChannel(NotificationChannel("recordings","Recordings",NotificationManager.IMPORTANCE_LOW)); n.createNotificationChannel(NotificationChannel("reminders","Program reminders",NotificationManager.IMPORTANCE_HIGH)) }
    fun add(c: Context,store: Store,r: Recording) { require(r.end>r.start && r.end>System.currentTimeMillis()); store.update { it.copy(recordings=it.recordings+r) }; schedule(c,r) }
}
class ScheduleReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) { val pending=goAsync(); CoroutineScope(Dispatchers.IO).launch { try {
        val store=(context.applicationContext as EzanoApp).store; while(!store.ready.value) delay(30)
        val r=store.state.value.recordings.firstOrNull { it.id==intent.getStringExtra("id") && it.status=="scheduled" }?:return@launch
        if(r.end<=System.currentTimeMillis()) { store.update { s->s.copy(recordings=s.recordings.map { if(it.id==r.id) it.copy(status="missed",error="Device was unavailable at the scheduled time") else it }) }; nextOccurrence(r)?.let { Scheduler.add(context,store,it) }; return@launch }
        if(r.reminder) {
            Scheduler.channels(context); val click=PendingIntent.getActivity(context,r.id.hashCode(),Intent(context,MainActivity::class.java).putExtra("channel",r.channel.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try { context.getSystemService(NotificationManager::class.java).notify(r.id.hashCode(),NotificationCompat.Builder(context,"reminders").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(r.title).setContentText("${r.channel.name} · ${timeLabel(r.start)}").setContentIntent(click).setAutoCancel(true).build()) } catch(_: SecurityException) {}
            if(r.autoPlay) MainActivity.active?.get()?.requestChannel(r.channel.id)
            store.update { s->s.copy(recordings=s.recordings.map { if(it.id==r.id) it.copy(status="reminded") else it }) }; nextOccurrence(r)?.let { Scheduler.add(context,store,it) }
        } else try { ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).putExtra("id",r.id)) } catch(_: Exception) { store.update { s->s.copy(recordings=s.recordings.map { if(it.id==r.id) it.copy(status="failed",error="The device blocked background recording. Allow exact alarms and keep Ezano running.") else it }) } }
    } finally { pending.finish() } } }
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) { if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return; val result=goAsync(); CoroutineScope(Dispatchers.IO).launch { try { val store=(context.applicationContext as EzanoApp).store; while(!store.ready.value) delay(30); val saved=store.state.value.recordings; saved.filter { it.status=="recording" }.forEach { r->store.update { s->s.copy(recordings=s.recordings.map { if(it.id==r.id) it.copy(status="interrupted",error="Device restarted during recording") else it }) }; nextOccurrence(r)?.let { Scheduler.add(context,store,it) } }; saved.filter { it.status=="scheduled" }.forEach { r-> if(r.end>System.currentTimeMillis()) Scheduler.schedule(context,r) else { store.update { s->s.copy(recordings=s.recordings.map { if(it.id==r.id) it.copy(status="missed") else it }) }; nextOccurrence(r)?.let { Scheduler.add(context,store,it) } } } } finally { result.finish() } } }
}
class RecordingService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=ConcurrentHashMap<String,Job>()
    private lateinit var store: Store
    private var wake: PowerManager.WakeLock?=null
    companion object {
        private var instance: RecordingService?=null
        fun stop(c: Context,id: String) { instance?.jobs?.get(id)?.cancel() }
        fun stopProvider(c: Context,id: String) { instance?.let { s->s.store.state.value.recordings.filter { it.channel.provider==id }.forEach { s.jobs[it.id]?.cancel() } } }
        fun start(c: Context,store: Store,channel: Channel,minutes: Int=60) { val now=System.currentTimeMillis(); val r=Recording(channel=channel,title=store.now(channel)?.title?:channel.name,start=now,end=now+minutes*60000L); store.update { it.copy(recordings=it.recordings+r) }; ContextCompat.startForegroundService(c,Intent(c,RecordingService::class.java).putExtra("id",r.id)) }
    }
    override fun onCreate() { super.onCreate(); instance=this; store=(application as EzanoApp).store; Scheduler.channels(this); wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Ezano:recording").apply { acquire(6*60*60*1000L) } }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(300,NotificationCompat.Builder(this,"recordings").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Ezano TV is recording").setContentText("Open Recordings to view or stop your recordings").setContentIntent(open).setOngoing(true).build())
        val id=intent?.getStringExtra("id")?:run { stopSelf(); return START_NOT_STICKY }
        if(jobs.containsKey(id)) return START_NOT_STICKY
        val job=scope.launch(start=CoroutineStart.LAZY) { try { while(!store.ready.value) delay(30); val r=store.state.value.recordings.firstOrNull { it.id==id }?:return@launch; capture(r) } finally { jobs.remove(id); if(jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } } }; jobs[id]=job; job.start(); return START_NOT_STICKY
    }
    private suspend fun capture(r: Recording) {
        val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:File(filesDir,"recordings"); dir.mkdirs(); val file=File(dir,"${r.id}.ts")
        fun status(s: String,e: String="")=store.update { old->old.copy(recordings=old.recordings.map { if(it.id==r.id) it.copy(status=s,path=file.absolutePath,error=e) else it }) }
        var status="completed"; var error=""
        try {
            require(dir.usableSpace>100*1024*1024) { "Not enough storage" }
            val c=Sources.resolve(store.state.value.providers.firstOrNull { it.id==r.channel.provider },r.channel)
            status("recording"); val request=Request.Builder().url(c.url); c.headers.forEach { (k,v)->request.header(k,v) }
            Sources.http.newCall(request.build()).execute().use { response ->
                require(response.isSuccessful) { "Source rejected recording" }
                if(response.peekBody(32).string().trimStart().startsWith("#EXTM3U")) { response.close(); recordHls(c,file,r.end) }
                else { val body=response.body?:error("Empty stream"); require(response.peekBody(1).bytes().firstOrNull()==0x47.toByte()) { "Recording requires MPEG-TS or unencrypted TS HLS" }; body.byteStream().use { input-> file.outputStream().use { output->val buffer=ByteArray(64*1024); while(System.currentTimeMillis()<r.end) { currentCoroutineContext().ensureActive(); require(dir.usableSpace>50*1024*1024) { "Recording stopped: storage almost full" }; val n=input.read(buffer); if(n<0) { if(System.currentTimeMillis()<r.end-3000) error("Stream ended before the recording finished"); break }; output.write(buffer,0,n) } } } }
            }
            require(file.length()>0) { "The source returned no video" }
        } catch(e: CancellationException) { status="stopped" } catch(e: Exception) { status="failed"; error=when { e.message?.contains("storage",true)==true -> "Storage is full"; e.message?.contains("MPEG-TS")==true -> e.message!!; e.message?.contains("encrypted",true)==true -> "This HLS format is not supported for recording"; else -> "The source disconnected or its recording format is unsupported. Any captured video is retained." } }
        status(status,error)
        // A cancelled schedule should not silently recreate itself.
        if(status!="stopped") nextOccurrence(r)?.let { Scheduler.add(this,store,it) }
    }
    private suspend fun recordHls(c: Channel,file: File,end: Long) {
        var playlist=c.url; var manifest=Sources.read(playlist,c.headers)
        if(manifest.contains("#EXT-X-STREAM-INF")) { val lines=manifest.lines(); val index=lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.maxByOrNull { Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull()?:0 }?:error("Empty HLS"); playlist=playlist.toHttpUrl().resolve(lines.drop(index+1).first { it.isNotBlank() && !it.startsWith('#') }.trim())!!.toString() }
        var last=-1L; var stalledAt=System.currentTimeMillis()
        file.outputStream().use { out-> while(System.currentTimeMillis()<end) {
            currentCoroutineContext().ensureActive(); require(file.parentFile!!.usableSpace>50*1024*1024) { "Storage full" }; manifest=Sources.read(playlist,c.headers)
            require(!manifest.contains("#EXT-X-MAP") && !Regex("#EXT-X-KEY:(?!METHOD=NONE)").containsMatchIn(manifest) && !manifest.contains("#EXT-X-BYTERANGE")) { "encrypted or fragmented HLS" }
            var seq=Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(manifest)?.groupValues?.get(1)?.toLong()?:0L
            val segments=manifest.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()
            // Begin at the live edge rather than recording the whole server's sliding window.
            if(last<0) last=seq+(segments.size-2).coerceAtLeast(0)-1
            for(segment in segments) { val index=seq++; if(index<=last) continue; currentCoroutineContext().ensureActive(); if(System.currentTimeMillis()>=end) break
                val u=playlist.toHttpUrl().resolve(segment.trim())!!.toString(); val b=Request.Builder().url(u); c.headers.forEach { (k,v)->b.header(k,v) }; Sources.http.newCall(b.build()).execute().use { r->require(r.isSuccessful); val body=r.body?:error("Empty segment"); require(r.peekBody(1).bytes().firstOrNull()==0x47.toByte()) { "MPEG-TS segments required" }; body.byteStream().copyTo(out) }; last=index; stalledAt=System.currentTimeMillis()
            }
            out.flush(); if(manifest.contains("#EXT-X-ENDLIST")) { require(System.currentTimeMillis()>=end-3000) { "Stream ended early" }; break }; require(System.currentTimeMillis()-stalledAt<90_000) { "Stream stalled" }; delay(1500)
        } }
    }
    override fun onBind(intent: Intent?)=null
    override fun onDestroy() { scope.cancel(); wake?.let { if(it.isHeld) it.release() }; instance=null; super.onDestroy() }
}
