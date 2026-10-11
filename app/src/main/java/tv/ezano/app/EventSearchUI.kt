package tv.ezano.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable fun LiveEventSearch(a: MainActivity, channels: List<Channel>, query: String, now: Long,
    onProgram: (Channel,Program)->Unit) {
    val s by a.store.libraryState()
    val guideBusy by a.store.guideBusy.collectAsState()
    var window by remember { mutableStateOf(EventWindow.NOW) }
    var limit by remember { mutableIntStateOf(60) }
    var result by remember { mutableStateOf(EventResults()) }
    var searching by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val labels by uiListState<CatalogLabel>(uiKey(channels)) { channels.catalogLabels().toList() }
    val allowed = remember(labels) { labels.map {it.id}.toSet() }
    val offsets = remember(labels) { labels.mapIndexed {index,c->c.id to index}.toMap() }
    LaunchedEffect(query,window) { limit=60 }
    LaunchedEffect(query,window,limit,now/30_000,uiKey(s.programs),allowed) {
        result=EventResults();failure=false;searching=query.isNotBlank()
        if(query.isNotBlank()) {
            delay(100)
            try { result=a.store.events.search(s.programs,query,window,now,allowed,limit) }
            catch(e: CancellationException) {throw e}
            catch(_:Exception) {failure=true}
        }
        searching=false
    }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            EventWindow.entries.forEach { w -> EzButton(w.label,selected=window==w,onClick={window=w}) }
            Spacer(Modifier.weight(1f))
            Text("Search program titles & descriptions",color=Muted,fontSize=10.sp)
        }
        Spacer(Modifier.height(12.dp))
        val status = when {
            failure -> "Event search could not open its index. Try again or refresh the guide."
            query.isBlank() -> "Find the event. We'll find the channel."
            searching -> "Searching your TV guide…"
            s.programs.isEmpty() && guideBusy -> "Your provider's TV guide is loading…"
            s.programs.isEmpty() -> "No EPG loaded. Add an XMLTV URL or refresh your provider's guide in Settings."
            result.total==0 -> "No ${if(window==EventWindow.NOW) "currently playing " else ""}events match “${query.trim()}”"
            else -> "${result.total} matching ${if(result.total==1) "broadcast" else "broadcasts"} · ${window.label}"
        }
        Text(status,color=if(failure) Color(0xFFFFA6AF) else Muted,fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp))
        if(query.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ManageSearch,null,Modifier.size(45.dp),tint=LocalAccent.current)
                Text("Every channel. One search.",fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Color.White)
                Text("Try a team, tournament, concert, or show. Start with On now to find it live.",fontSize=12.sp,color=Muted)
            }
        } else if(searching) {
            Box(Modifier.fillMaxWidth().padding(30.dp),contentAlignment=Alignment.Center) {CircularProgressIndicator(color=LocalAccent.current,modifier=Modifier.size(28.dp))}
        } else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            items(result.programs,key={it.id}) { p ->
                val index=offsets[p.channelId]
                if(index!=null) EventCard(labels[index],p,now) {
                    a.scope.launch {
                        val c=withContext(Dispatchers.Default) {channels[index]}
                        if(p.start<=System.currentTimeMillis() && p.end>System.currentTimeMillis()) a.play(c)
                        else a.guarded(c) {onProgram(c,p)}
                    }
                }
            }
            if(result.total>result.programs.size) item {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(limit<1000) EzButton("Show more broadcasts",onClick={limit+=60})
                    Text("Showing ${result.programs.size} of ${result.total}${if(limit>=1000) " · Narrow your search to see more" else ""}",color=Muted,fontSize=11.sp)
                }
            }
        }
    }
}

@Composable private fun EventCard(channel: CatalogLabel, program: Program, now: Long, onClick: ()->Unit) {
    var focused by remember {mutableStateOf(false)}
    val live=program.start<=now && program.end>now
    val accent=LocalAccent.current
    Row(Modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused}.clip(RoundedCornerShape(12.dp))
        .background(if(focused) Color.White else Panel.copy(.72f)).border(1.dp,if(focused) accent else Color.White.copy(.07f),RoundedCornerShape(12.dp))
        .clickable(onClick=onClick).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(155.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(channel.name,color=if(focused) Ink else accent,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(channel.group,color=if(focused) Ink.copy(.65f) else Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(program.title,color=if(focused) Ink else Color.White,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            val day=java.time.Instant.ofEpochMilli(program.start).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
            Text("$day · ${timeLabel(program.start)} – ${timeLabel(program.end)}",color=if(focused) Ink.copy(.65f) else Muted,fontSize=10.sp)
            if(program.description.isNotBlank()) Text(program.description,color=if(focused) Ink.copy(.65f) else Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Text(if(live) "● WATCH LIVE" else "DETAILS  ›",color=if(focused) Ink else if(live) accent else Muted,fontSize=10.sp,fontWeight=FontWeight.Bold)
    }
}
