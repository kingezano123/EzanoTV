package tv.ezano.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun ProvidersDialog(a: MainActivity,onDismiss: ()->Unit) {
    val s by a.store.libraryState(); val busy by a.store.busy.collectAsState(); var editor by remember {mutableStateOf<Provider?>(null)}; var add by remember {mutableStateOf(false)}; var deleting by remember {mutableStateOf<Provider?>(null)}
    val progress by a.store.progress.collectAsState()
    // A compact catalog decodes each accessed row. Never scan it while composing
    // the dialog: a large import publishes a new catalog while this dialog is open.
    val counts by produceState<Map<String,Int>?>(null,uiKey(s.channels)) {
        value=withContext(Dispatchers.Default) {
            val result=HashMap<String,Int>()
            s.channels.catalogLabels().forEachIndexed {index,channel->
                if(index%512==0) ensureActive()
                result[channel.provider]=(result[channel.provider]?:0)+1
            }
            result
        }
    }
    val showChannels: ()->Unit={add=false;editor=null;onDismiss();a.focusImportedChannel=true;a.page="Live TV"}
    Modal("Your playlists",onDismiss) {
        Text("Connect your own service. Playlists stay together; credentials stay encrypted on this device.",color=Muted,fontSize=12.sp)
        s.providers.forEach { p-> Column {Text(p.name,color=Color.White,fontSize=16.sp,fontWeight=FontWeight.SemiBold);Text("${p.type.uppercase()} · ${counts?.let {"${it[p.id]?:0} items"}?:"Reading library…"} · ${if(p.enabled) "Enabled" else "Disabled"}",color=Muted,fontSize=11.sp);Row(Modifier.padding(top=9.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {EzButton("Edit",onClick={if(!busy) editor=p});EzButton(if(p.enabled) "Disable" else "Enable",onClick={if(!busy) a.store.update {it.copy(providers=it.providers.map {v->if(v.id==p.id) v.copy(enabled=!v.enabled) else v})}});EzButton("Remove",onClick={if(!busy) deleting=p})}}
        }
        if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth(),color=LocalAccent.current);Text(progress.ifBlank {"Preparing your library…"},color=Muted,fontSize=12.sp)}
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {EzButton("Add playlist",Icons.Outlined.Add,primary=true,onClick={if(!busy) add=true});EzButton(if(busy) "Updating…" else "Refresh all",Icons.Outlined.Refresh,onClick={if(!busy) a.scope.launch {if(a.store.refresh()) {a.message="100% · Library saved. Ready to watch.";showChannels()}}})}
        if(!busy && s.channels.isNotEmpty()) EzButton("Open saved library",Icons.Outlined.LiveTv,primary=true,onClick=showChannels)
        Text("Use the server address, username and password supplied by your IPTV service. Ezano does not supply paid channels.",color=Muted,fontSize=11.sp)
    }
    if(add || editor!=null) ProviderEditor(a,editor,onDismiss={add=false;editor=null},onConnected=showChannels)
    deleting?.let {p->Modal("Remove ${p.name}?",{deleting=null}) {Text("This removes its imported channels and cancels its scheduled recordings. Existing recording files are kept.",color=Muted,fontSize=12.sp);EzButton("Remove playlist",onClick={a.store.removeProvider(p.id);deleting=null})}}
}
@Composable private fun ProviderEditor(a: MainActivity,original: Provider?,onDismiss: ()->Unit,onConnected: ()->Unit) {
    var type by remember {mutableStateOf(original?.type?:"xtream")};var name by remember {mutableStateOf(original?.name?:"")};var url by remember {mutableStateOf(original?.url?:"")};var user by remember {mutableStateOf(original?.user?:"")};var password by remember {mutableStateOf(original?.password?:"")};var epg by remember {mutableStateOf(original?.epg?:"")};var mac by remember {mutableStateOf(original?.mac?:"")};var error by remember {mutableStateOf("")};val busy by a.store.busy.collectAsState();val progress by a.store.progress.collectAsState()
    val connectFocus=remember {FocusRequester()};val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(error) {if(error.isNotBlank()) runCatching {connectFocus.requestFocus()}}
    Modal(if(original==null) "Connect your playlist" else "Edit playlist",onDismiss) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("xtream" to "Xtream Codes","m3u" to "M3U URL","stalker" to "Stalker portal").forEach {(id,label)->EzButton(label,selected=type==id,onClick={type=id})}}
        Field("Playlist name",name,{name=it});Field(if(type=="m3u") "Playlist URL" else if(type=="stalker") "Portal URL" else "Server URL (include http:// or https://)",url,{url=it})
        if(type=="xtream") {Field("Username",user,{user=it});Field("Password",password,{password=it},true)}
        if(type=="stalker") {Field("Your provider-registered MAC address",mac,{mac=it});Text("Basic Stalker live-channel support. Portal authentication and guide extensions vary by provider.",color=Muted,fontSize=11.sp)}
        Field("XMLTV guide URL (optional override)",epg,{epg=it})
        if(url.startsWith("http://")) Text("This server uses HTTP. Its credentials and streams travel without transport encryption.",color=Color(0xFFFFCA89),fontSize=11.sp)
        if(error.isNotBlank()) Text(error,color=Color(0xFFFF8592),fontSize=12.sp)
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth(),color=LocalAccent.current);Text(progress.ifBlank {"Preparing your library…"},color=Muted,fontSize=12.sp);Text("Your channels will open automatically when the import is ready.",color=Muted,fontSize=11.sp) }
        EzButton(if(busy) "Connecting…" else "Connect & import",primary=true,modifier=Modifier.focusRequester(connectFocus),onClick={
            if(!busy) {keyboard?.hide();error="";try {require(name.isNotBlank()) {"Give your playlist a name"};val normalized=if(type=="xtream") Sources.serverUrl(url) else Sources.url(url);require(type!="xtream" || (user.isNotBlank() && password.isNotBlank())) {"Enter your username and password"};require(type!="stalker" || mac.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {"Enter a valid MAC address"};if(epg.isNotBlank()) Sources.url(epg);val p=Provider(original?.id?:java.util.UUID.randomUUID().toString(),name.trim(),type,normalized,user.trim(),password,epg.trim(),mac.trim(),original?.enabled?:true);a.scope.launch {val connected=a.store.refresh(p);if(connected) {a.message="100% · Library saved. Ready to watch.";onConnected()} else error=a.store.error.value.ifBlank {"Another update is in progress. Wait for it to finish and try again."}}} catch(e: Exception){error=e.message?:"Check the fields"}}
        })
    }
}
@Composable fun PinDialog(a: MainActivity,onDismiss: ()->Unit,onSuccess: ()->Unit) {
    var pin by remember {mutableStateOf("")};var error by remember {mutableStateOf("")};var checking by remember {mutableStateOf(false)}
    Modal("Enter your PIN",onDismiss) {Text("This content or setting is protected.",color=Muted,fontSize=12.sp);Field("PIN",pin,{pin=it.filter(Char::isDigit).take(8)},true);if(error.isNotBlank()) Text(error,color=Color(0xFFFF8592),fontSize=12.sp);EzButton(if(checking) "Checking…" else "Unlock",primary=true,onClick={if(!checking){checking=true;a.scope.launch {val valid=a.store.checkPin(pin);checking=false;if(valid) onSuccess() else {error=if(a.store.state.value.prefs.pinBlockedUntil>System.currentTimeMillis()) "Too many attempts. Wait 30 seconds before trying again." else "That PIN is incorrect.";pin=""}}}})}
}
@Composable fun DetailDialog(a: MainActivity,original: Channel,onDismiss: ()->Unit,onProgram: (Program)->Unit) {
    val s by a.store.libraryState();var c by remember(original.id) {mutableStateOf(original)};var episodes by remember {mutableStateOf<List<Channel>>(emptyList())};var loading by remember {mutableStateOf(false)};var failure by remember {mutableStateOf("")};var groupModal by remember {mutableStateOf(false)};var schedule by remember {mutableStateOf(false)};var options by remember {mutableStateOf(false)}
    var reordering by remember {mutableStateOf(false)}
    val watchFocus=remember(original.id) {FocusRequester()};var pendingWatch by remember {mutableStateOf(false)}
    val resumeEpisode=s.recent.firstOrNull {it.seriesId==c.id && (s.positions[it.id]?:0)>0}
    fun watch() {a.guarded(c){if(c.kind==Kind.SERIES){val ep=resumeEpisode?:episodes.firstOrNull();if(ep!=null){a.startPlayback(ep);onDismiss()}else pendingWatch=true}else{a.startPlayback(c);onDismiss()}}}
    suspend fun loadEpisodes() {val p=s.providers.firstOrNull {it.id==c.provider}?:return;loading=true;failure="";try{episodes=withContext(Dispatchers.IO){Sources.episodes(p,c)};if(episodes.isEmpty())failure="No episodes returned by this provider";if(pendingWatch && episodes.isNotEmpty())watch()}catch(_:Exception){failure="Episodes could not be loaded. Try again."}finally{loading=false;pendingWatch=false}}
    fun moveContent(direction: Int) {
        if(reordering) return
        reordering=true
        a.scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val ids=a.store.visible().catalogLabels().map {it.id}.toMutableList()
                    val index=ids.indexOf(c.id);val target=index+direction
                    if(index>=0 && target in ids.indices) {java.util.Collections.swap(ids,index,target);a.store.update {it.copy(order=ids)}}
                }
                a.message="Channel order updated"
            } finally {reordering=false}
        }
    }
    LaunchedEffect(original.id) {if(c.kind==Kind.SERIES) loadEpisodes(); val p=s.providers.firstOrNull {it.id==c.provider}; if(p!=null && c.kind==Kind.MOVIE) runCatching {withContext(Dispatchers.IO) {Sources.details(p,c)}}.onSuccess {c=it} }
    Modal(if(c.kind==Kind.LIVE) c.name else HomeRecommendations.title(c),onDismiss) {
        val windowFocused=LocalWindowInfo.current.isWindowFocused
        val inputMode=LocalInputModeManager.current
        LaunchedEffect(original.id,windowFocused){if(windowFocused){inputMode.requestInputMode(InputMode.Keyboard);delay(80);runCatching {watchFocus.requestFocus()}}}
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {Art(c.backdrop.ifBlank {c.logo},Modifier.width(160.dp).height(97.dp));Column {Text(listOf(c.year.take(4),c.group,if(c.rating.isNotBlank()) "Rating ${c.rating}" else "").filter {it.isNotBlank()}.joinToString(" · "),color=LocalAccent.current,fontSize=11.sp);Text(c.description.ifBlank {if(c.kind==Kind.LIVE) a.store.now(c)?.description?.ifBlank {"Live channel from your playlist"}?:"Live channel from your playlist" else "Open this title to watch from your provider."},color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))}}
        if(c.provider=="demo") Text("Playable open film demo · Blender Foundation · CC BY 3.0",color=Muted,fontSize=10.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            EzButton(if((s.positions[c.id]?:0)>0 || resumeEpisode!=null) "Resume" else "Watch",Icons.Outlined.PlayArrow,primary=true,modifier=Modifier.focusRequester(watchFocus),onClick={if(failure.isNotBlank() && c.kind==Kind.SERIES){pendingWatch=true;a.scope.launch {loadEpisodes()}}else watch()})
            EzButton(if(c.id in s.favorites) "In my list" else "Add to my list",Icons.Outlined.BookmarkBorder,onClick={a.store.favorite(c)})
            EzButton("Options",Icons.Outlined.MoreHoriz,onClick={options=!options})
        }
        if(loading) Text("Loading episodes…",color=Muted,fontSize=12.sp);if(failure.isNotBlank()) Text(failure,color=Color(0xFFFF8592),fontSize=12.sp)
        if(episodes.isNotEmpty()) {
            val seasons=remember(episodes) {episodes.groupBy {it.name.substringBefore('·').trim()}}
            var selectedSeason by remember(episodes) {mutableStateOf(seasons.keys.first())}
            LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {items(seasons.keys.toList()) {season->EzButton(season.replace("S","Season "),selected=season==selectedSeason,onClick={selectedSeason=season})}}
            LazyColumn(Modifier.height(220.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                items(seasons[selectedSeason].orEmpty(),key={it.id}) {ep->
                    Row(Modifier.fillMaxWidth().clickable {a.guarded(c){a.startPlayback(ep);onDismiss()}}.background(Color.White.copy(.04f),androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Art(ep.logo,Modifier.width(94.dp).height(54.dp))
                        Column(Modifier.weight(1f)) {Text(ep.name,color=Color.White,fontSize=12.sp);Text(ep.description,color=Muted,fontSize=10.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
                    }
                }
            }
        }
        if(options) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {EzButton("Custom groups",onClick={groupModal=true});EzButton(if(c.id in s.locked) "Unlock content" else "Lock content",onClick={a.admin {if(s.prefs.pinHash.isBlank()) a.message="Set a PIN in Settings first" else a.store.update {it.copy(locked=if(c.id in it.locked) it.locked-c.id else it.locked+c.id)}}});EzButton("Hide",onClick={a.admin {a.store.update {it.copy(hidden=it.hidden+c.id)};onDismiss()}})}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {EzButton("Move earlier",onClick={moveContent(-1)});EzButton("Move later",onClick={moveContent(1)});EzButton("Hide group",onClick={a.admin {a.store.update {it.copy(hiddenGroups=it.hiddenGroups+c.group)};onDismiss()}})}
            if(c.kind==Kind.LIVE) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {EzButton("Record 60 min",Icons.Outlined.FiberManualRecord,onClick={a.guarded(c){a.permissions();RecordingService.start(a,a.store,c);a.message="Recording started"}});EzButton("Schedule recording",onClick={a.guarded(c){schedule=true}})}
        }
        if(c.kind==Kind.LIVE) {
            val visible by produceState<List<Program>>(emptyList(),uiKey(s.programs),c.id,c.catchupDays) {
                value=withContext(Dispatchers.Default) {a.store.programs(c).filter {it.end>System.currentTimeMillis() || c.catchupDays>0}.take(60)}
            }
            Text(if(c.catchupDays>0) "Guide & catch-up · ${c.catchupDays}-day archive" else "Coming up on this channel",color=Color.White,fontSize=14.sp)
            if(visible.isEmpty()) Text("No guide data from this source. Refresh your playlists or add an XMLTV guide URL.",color=Muted,fontSize=12.sp)
            visible.forEach {p->EzButton("${timeLabel(p.start)}  ${p.title}",modifier=Modifier.fillMaxWidth(),onClick={onProgram(p)})}
        }
    }
    if(groupModal) GroupDialog(a,c) {groupModal=false}
    if(schedule) ScheduleDialog(a,c,null) {schedule=false}
}
@Composable fun GroupDialog(a: MainActivity,c: Channel,onDismiss: ()->Unit) {val s by a.store.libraryState();var name by remember {mutableStateOf("")};var move by remember {mutableStateOf(false)}
    Modal("Organize ${c.name}",onDismiss) {Text("Copy a channel into several custom groups, or move it between custom groups. Provider categories stay intact.",color=Muted,fontSize=12.sp);SettingRow("Move instead of copy","Remove from other custom groups first",if(move) "Move" else "Copy"){move=!move}
        fun add(g: String) {a.store.update {old->val groups=if(move) old.groups.mapValues {it.value-c.id}.toMutableMap() else old.groups.toMutableMap();groups[g]=((groups[g]?:emptyList())+c.id).distinct();old.copy(groups=groups)};a.message="Added to $g"}
        s.groups.forEach {(g,ids)->Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){EzButton(g,onClick={add(g)});if(c.id in ids) EzButton("Remove from group",onClick={a.store.update {it.copy(groups=it.groups+(g to ids.filterNot {id->id==c.id}))}})}}
        Field("New group name",name,{name=it});EzButton("Create & add",primary=true,onClick={if(name.isNotBlank()){add(name.trim());onDismiss()}})
    }
}
@Composable fun ProgramDialog(a: MainActivity,c: Channel,p: Program,onDismiss: ()->Unit) {
    var schedule by remember {mutableStateOf(false)};val now=System.currentTimeMillis();val s by a.store.libraryState()
    Modal(p.title,onDismiss) {
        Text("${c.name} · ${timeLabel(p.start)} – ${timeLabel(p.end)}",color=LocalAccent.current,fontSize=12.sp);Text(p.description.ifBlank {"No description supplied by this source."},color=Muted,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(p.start<=now && p.end>now) EzButton("Watch live",primary=true,onClick={a.play(c);onDismiss()})
            if(p.start<now && c.catchupDays>0) EzButton("Watch from start",primary=true,onClick={a.guarded(c){val provider=s.providers.firstOrNull {it.id==c.provider};if(provider!=null) try {a.startPlayback(Sources.catchup(provider,c,p));onDismiss()}catch(e:Exception){a.message=e.message?:"Catch-up unavailable"}}})
            if(p.end>now) EzButton("Record program",Icons.Outlined.FiberManualRecord,onClick={a.guarded(c){schedule=true}})
            if(p.start>now) EzButton("Remind me",Icons.Outlined.NotificationsNone,onClick={a.guarded(c){a.permissions();Scheduler.add(a,a.store,Recording(channel=c,title=p.title,start=p.start,end=p.end,reminder=true));a.message="Reminder set for one minute before the program";onDismiss()}})
        }
        if(p.start>now) EzButton("Remind & switch while Ezano is open",onClick={a.guarded(c){a.permissions();Scheduler.add(a,a.store,Recording(channel=c,title=p.title,start=p.start,end=p.end,reminder=true,autoPlay=true));a.message="Reminder and in-app channel switch set";onDismiss()}})
        if(!Scheduler.exact(a)) Text("Precise timing is disabled on this device. Enable scheduled alarm access in Settings for timely reminders and recordings.",color=Color(0xFFFFCA89),fontSize=11.sp)
    }
    if(schedule) ScheduleDialog(a,c,p) {schedule=false;onDismiss()}
}
@Composable fun ScheduleDialog(a: MainActivity,c: Channel,p: Program?,onDismiss: ()->Unit) {
    val fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");var date by remember {mutableStateOf(Instant.ofEpochMilli(p?.start?.coerceAtLeast(System.currentTimeMillis()+60000)?:System.currentTimeMillis()+60000).atZone(ZoneId.systemDefault()).format(fmt))};var duration by remember {mutableStateOf(if(p==null) "60" else ((p.end-p.start)/60000).toString())};var repeat by remember {mutableStateOf("once")};var error by remember {mutableStateOf("")}
    Modal("Schedule a recording",onDismiss) {Text(c.name,color=LocalAccent.current);Field("Local start (yyyy-MM-dd HH:mm)",date,{date=it});Field("Duration in minutes (1–360)",duration,{duration=it.filter(Char::isDigit)})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("once","daily","weekly").forEach {r->EzButton(r.replaceFirstChar {it.uppercase()},selected=repeat==r,onClick={repeat=r})}}
        Text("${ZoneId.systemDefault()} · Daily and weekly repeats keep the same local start time. The device must be powered and online. Each recording consumes a provider connection.",color=Muted,fontSize=11.sp)
        if(!Scheduler.exact(a)) Text("Scheduled alarm access is off. Timing will be approximate until you enable it in Settings.",color=Color(0xFFFFCA89),fontSize=11.sp)
        if(error.isNotBlank()) Text(error,color=Color(0xFFFF8592),fontSize=12.sp)
        EzButton("Schedule",primary=true,onClick={try {val start=LocalDateTime.parse(date,fmt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();val minutes=duration.toInt();require(start>System.currentTimeMillis()) {"Choose a future start time"};require(minutes in 1..360) {"Duration must be 1–360 minutes"};val overlap=a.store.state.value.recordings.any {it.status in listOf("scheduled","recording") && !it.reminder && start<it.end && start+minutes*60000L>it.start};a.permissions();Scheduler.add(a,a.store,Recording(channel=c,title=p?.title?:c.name,start=start,end=start+minutes*60000L,repeat=repeat));a.message=if(overlap) "Scheduled. Overlapping recordings require extra provider connections." else "Recording scheduled";onDismiss()}catch(e:Exception){error=e.message?:"Check the date and duration"}})
    }
}
