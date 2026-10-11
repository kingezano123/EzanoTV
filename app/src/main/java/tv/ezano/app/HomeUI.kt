package tv.ezano.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable fun TopNavigation(a: MainActivity, onConnect: ()->Unit) {
    val s by a.store.libraryState()
    val first=remember {FocusRequester()}
    val inputMode=LocalInputModeManager.current
    // Capture navigation intent before the content effect consumes its one-shot focus request.
    val enteringSearch=a.focusCatalogSearch
    LaunchedEffect(a.page) {if(!a.focusImportedChannel && !enteringSearch && !a.focusCatalogSearch) {inputMode.requestInputMode(InputMode.Keyboard);runCatching {first.requestFocus()}} }
    Row(Modifier.fillMaxWidth().height(66.dp).background(Color.Black.copy(.97f)).padding(horizontal=30.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        Text("EZANO",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=1.sp,modifier=Modifier.padding(end=18.dp))
        listOf("Home" to "Home","Live TV" to "Live TV","Movies" to "Movies","Series" to "Shows","My list" to "My stuff").forEachIndexed {i,(page,label)->
            var focused by remember {mutableStateOf(false)}
            val selected=a.page==page
            Column((if(selected || (a.page !in listOf("Home","Live TV","Movies","Series","My list") && i==0)) Modifier.focusRequester(first) else Modifier)
                .onPreviewKeyEvent {event->
                    if(event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN && page in listOf("Movies","Series")) {
                        val action={a.page=page;a.focusCatalogSearch=true};if(page in s.prefs.lockedSections) a.admin(action) else action();true
                    } else false
                }.onFocusChanged {focused=it.isFocused}.clip(RoundedCornerShape(3.dp)).background(if(focused) Color(0xFF252525) else Color.Transparent)
                .clickable {if(page in s.prefs.lockedSections) a.admin {a.page=page} else a.page=page}.padding(horizontal=11.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(label,color=if(selected||focused) Color.White else Muted,fontSize=11.sp,fontWeight=if(selected||focused) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.padding(top=5.dp).size(24.dp,2.dp).background(if(selected||focused) Color.White else Color.Transparent))
            }
        }
        Spacer(Modifier.weight(1f))
        NavIcon("Search",Icons.Outlined.Search) {a.page="Search"}
        NavIcon("Playlists",Icons.Outlined.PlaylistPlay,onConnect)
        NavIcon("Recordings",Icons.Outlined.FiberManualRecord) {a.page="Recordings"}
        NavIcon("Settings",Icons.Outlined.Settings) {a.admin {a.page="Settings"}}
    }
}

@Composable private fun NavIcon(label: String,icon: androidx.compose.ui.graphics.vector.ImageVector,onClick: ()->Unit) {
    var focused by remember {mutableStateOf(false)}
    Box(Modifier.size(30.dp).onFocusChanged {focused=it.isFocused}.clip(RoundedCornerShape(3.dp)).border(if(focused) 1.dp else 0.dp,if(focused) Color.White else Color.Transparent,RoundedCornerShape(3.dp)).clickable(onClick=onClick),contentAlignment=Alignment.Center) {Icon(icon,label,Modifier.size(18.dp),tint=Color.White)}
}

object WatchHistory {
    private val mature=Regex("(?i)\\b(xxx|adult|porn|erotic|explicit|uncensored)\\b")
    fun allowed(c: Channel,s: Library): Boolean {
        if(c.kind==Kind.LIVE || c.id in s.hidden || c.group in s.hiddenGroups || c.id in s.locked || c.seriesId in s.locked || c.seriesId in s.hidden) return false
        if(c.seriesId.isNotBlank() && s.hiddenGroups.isNotEmpty() && s.channels.catalogLabels().any {it.id==c.seriesId && it.group in s.hiddenGroups}) return false
        if((if(c.kind==Kind.MOVIE) "Movies" else "Series") in s.prefs.lockedSections) return false
        if(c.provider!="demo" && s.providers.none {it.id==c.provider && it.enabled}) return false
        return !mature.containsMatchIn("${c.name} ${c.group} ${c.genre}") && c.certification.uppercase() !in setOf("R","NC-17","TV-MA","18","18+")
    }
    fun resume(s: Library): List<Channel> {
        val saved=s.channels.selectCatalog {(s.positions[it.id]?:0)>0 && it.kind==Kind.MOVIE}.take(30)
        return (s.recent+saved).distinctBy {it.id}.filter {(s.positions[it.id]?:0)>0 && allowed(it,s)}.take(24)
    }
}

@Composable fun CinemaHome(a: MainActivity,onDetails: (Channel)->Unit,onConnect: ()->Unit) {
    val s by a.store.libraryState()
    val catalogOpen by a.store.catalogReady.collectAsState()
    val cached by a.store.home.collectAsState()
    var computed by remember {mutableStateOf<HomeSnapshot?>(null)}
    var resume by remember {mutableStateOf<List<Channel>>(emptyList(),referentialEqualityPolicy())}
    LaunchedEffect(uiKey(s.channels),s.providers,s.hidden,s.hiddenGroups,s.locked,s.prefs.lockedSections,catalogOpen) {
        if(!catalogOpen) return@LaunchedEffect
        val shelves=withContext(Dispatchers.Default) {HomeRecommendations.featured(a.store.visible(),allowed={it.id !in s.locked && (if(it.kind==Kind.MOVIE) "Movies" else "Series") !in s.prefs.lockedSections})}
        computed=shelves
        a.store.saveHome(shelves)
    }
    LaunchedEffect(uiKey(s.channels),s.recent,s.positions,s.providers,s.hidden,s.hiddenGroups,s.locked,s.prefs.lockedSections) {resume=withContext(Dispatchers.Default) {WatchHistory.resume(s)}}
    // Until the catalog opens, last launch's shelves appear instantly (still honoring current locks).
    val shelves=computed ?: remember(cached,s.locked,s.hidden,s.prefs.lockedSections) {cached.filter {it.id !in s.locked && it.id !in s.hidden && (if(it.kind==Kind.MOVIE) "Movies" else "Series") !in s.prefs.lockedSections}}
    val chosen=shelves.hero ?: resume.firstOrNull()
    var hero by remember(chosen?.id) {mutableStateOf(chosen)}
    LaunchedEffect(chosen?.id) {val c=chosen?:return@LaunchedEffect;val p=s.providers.firstOrNull {it.id==c.provider}?:return@LaunchedEffect;if(c.kind==Kind.MOVIE) runCatching {withContext(Dispatchers.IO){Sources.details(p,c)}}.onSuccess {hero=it.takeIf {value->if(resume.any {it.id==c.id}) WatchHistory.allowed(value,s) else HomeRecommendations.isEligible(value) || HomeRecommendations.isFeatured(value)}}}
    LazyColumn(Modifier.fillMaxSize().background(Ink).padding(top=66.dp),contentPadding=PaddingValues(bottom=28.dp)) {
        hero?.let {c->item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Art(c.backdrop.ifBlank {c.logo},Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(.96f),Color.Black.copy(.65f),Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(.10f),.65f to Color.Transparent,1f to Color.Black)))
                Column(Modifier.align(Alignment.CenterStart).padding(start=38.dp).width(440.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(HomeRecommendations.isFeatured(c)) Text("FEATURED FOR KIDS & FAMILY",color=LocalAccent.current,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)
                    Text(if(c.kind==Kind.EPISODE) c.group else HomeRecommendations.title(c),fontSize=33.sp,lineHeight=37.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOf(c.year.take(4),c.certification,if(c.kind==Kind.MOVIE) "Movie" else "Series").filter {it.isNotBlank()}.joinToString("   ·   "),color=Color(0xFFCCCCCC),fontSize=11.sp)
                    if(c.description.isNotBlank()) Text(c.description,color=Color(0xFFDDDDDD),fontSize=12.sp,lineHeight=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        EzButton(if((s.positions[c.id]?:0)>0) "Resume" else "Play",Icons.Outlined.PlayArrow,primary=true,onClick={if(c.kind==Kind.SERIES) onDetails(c) else a.play(c)})
                        EzButton("More info",Icons.Outlined.Info,onClick={onDetails(c)})
                    }
                }
            }
        }}
        if(resume.isNotEmpty()) item {
            Column(Modifier.padding(top=if(hero==null) 24.dp else 4.dp,bottom=8.dp)) {
                Text("Continue Watching",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Color.White,modifier=Modifier.padding(horizontal=28.dp,vertical=10.dp))
                LazyRow(contentPadding=PaddingValues(horizontal=25.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {items(resume,key={it.id}) {c->Poster(c,favorite=c.id in s.favorites,onClick={a.play(c)})}}
            }
        }
        if(shelves.popular.isNotEmpty()) item {ContentRow(if(shelves.classics.isEmpty()) "Family picks" else "Popular kids movies",shelves.popular,s.favorites,onDetails,portrait=true)}
        if(shelves.shows.isNotEmpty()) item {ContentRow("Kids & family shows",shelves.shows,s.favorites,onDetails,portrait=true)}
        if(shelves.classics.isNotEmpty()) item {ContentRow("Family classics",shelves.classics,s.favorites,onDetails,portrait=true)}
        if(shelves.more.isNotEmpty()) item {ContentRow("More family movies & shows",shelves.more,s.favorites,onDetails,portrait=true)}
        if(shelves.isEmpty && !catalogOpen) item {
            Row(Modifier.padding(horizontal=36.dp,vertical=30.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp),color=LocalAccent.current,strokeWidth=2.dp);Text("Loading your movies and shows…",color=Muted,fontSize=12.sp)
            }
        }
        if(shelves.isEmpty && catalogOpen) item {
            Column(Modifier.padding(horizontal=36.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Your collection",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                if(s.providers.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {EzButton("Movies",onClick={a.page="Movies"});EzButton("Shows",onClick={a.page="Series"})}
                else EzButton("Add a playlist",primary=true,onClick=onConnect)
                Text(if(s.providers.isEmpty()) "Your movies and shows appear here once a playlist is available." else "Browse your movies and shows to find your next watch.",color=Muted,fontSize=11.sp)
            }
        }
    }
}
