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
    val first=a.navFocus
    val inputMode=LocalInputModeManager.current
    // Capture navigation intent before the content effect consumes its one-shot focus request.
    val enteringSearch=a.focusCatalogSearch
    LaunchedEffect(a.page) {if(!a.focusImportedChannel && !enteringSearch && !a.focusCatalogSearch) {inputMode.requestInputMode(InputMode.Keyboard);runCatching {first.requestFocus()}} }
    // On Home the bar floats over the full-screen artwork, like Disney+; other pages keep a solid bar.
    val navBackground=if(a.page=="Home" && !a.homeScrolled) Brush.verticalGradient(listOf(Color.Black.copy(.82f),Color.Black.copy(.35f),Color.Transparent)) else Brush.verticalGradient(listOf(Color.Black,Color.Black))
    Row(Modifier.fillMaxWidth().height(if(a.page=="Home") 84.dp else 66.dp).background(navBackground).padding(horizontal=28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("EZANO",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=1.sp,maxLines=1,modifier=Modifier.padding(end=12.dp))
        listOf("Home" to "Home","Live TV" to "Live TV","Movies" to "Movies","Series" to "Shows","My list" to "Favorite channels","Saved" to "Favorite movies/shows").forEachIndexed {i,(page,label)->
            var focused by remember {mutableStateOf(false)}
            val selected=a.page==page
            Column((if(selected || (a.page !in listOf("Home","Live TV","Movies","Series","My list","Saved") && i==0)) Modifier.focusRequester(first) else Modifier)
                // Down from the bar always lands on Home's main control instead of relying on geometric search.
                .focusProperties {if(a.page=="Home") down=if(a.homeEntryReady) a.homeEntry else a.homeContent}
                .onPreviewKeyEvent {event->
                    if(event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.nativeKeyEvent.action==android.view.KeyEvent.ACTION_DOWN && page in listOf("Movies","Series")) {
                        val action={a.page=page;a.focusCatalogSearch=true};if(page in s.prefs.lockedSections) a.admin(action) else action();true
                    } else false
                }.onFocusChanged {focused=it.isFocused}.clip(RoundedCornerShape(3.dp)).background(if(focused) Color.White.copy(.16f) else Color.Transparent)
                .clickable {if(page in s.prefs.lockedSections) a.admin {a.page=page} else a.page=page}.padding(horizontal=9.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(label,color=if(selected||focused) Color.White else Color(0xFFCFCFCF),fontSize=12.sp,maxLines=1,softWrap=false,fontWeight=if(selected||focused) FontWeight.ExtraBold else FontWeight.SemiBold)
                Box(Modifier.padding(top=5.dp).size(24.dp,2.dp).background(if(selected||focused) Color.White else Color.Transparent))
            }
        }
        Spacer(Modifier.weight(1f))
        NavIcon(a,"Search",Icons.Outlined.Search) {a.page="Search"}
        NavIcon(a,"Playlists",Icons.Outlined.PlaylistPlay,onConnect)
        NavIcon(a,"Recordings",Icons.Outlined.FiberManualRecord) {a.page="Recordings"}
        NavIcon(a,"Settings",Icons.Outlined.Settings) {a.admin {a.page="Settings"}}
    }
}

@Composable private fun NavIcon(a: MainActivity,label: String,icon: androidx.compose.ui.graphics.vector.ImageVector,onClick: ()->Unit) {
    var focused by remember {mutableStateOf(false)}
    // Home content starts under the floating bar, so geometric focus search would skip it; route Down explicitly.
    Box(Modifier.size(30.dp).focusProperties {if(a.page=="Home") down=if(a.homeEntryReady) a.homeEntry else a.homeContent}.onFocusChanged {focused=it.isFocused}.clip(RoundedCornerShape(3.dp)).border(if(focused) 1.dp else 0.dp,if(focused) Color.White else Color.Transparent,RoundedCornerShape(3.dp)).clickable(onClick=onClick),contentAlignment=Alignment.Center) {Icon(icon,label,Modifier.size(18.dp),tint=Color.White)}
}

/** Registers the control that receives focus when Down is pressed in the top bar; Up returns to the bar. */
@Composable private fun Modifier.homeEntry(a: MainActivity): Modifier {
    DisposableEffect(a) {a.homeEntryReady=true;onDispose {a.homeEntryReady=false;a.homeEntryFocused=false}}
    return this.focusRequester(a.homeEntry).focusProperties {up=a.navFocus}.onFocusChanged {a.homeEntryFocused=it.isFocused}
}

private val homeCache=ScreenCache<HomeSnapshot>(2)

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
        val shelves=withContext(Dispatchers.Default) {
            val visible=a.store.visible();val ranks=Curated.ranks(s.channels)
            val key=listOf(uiKey(visible),uiKey(ranks),s.locked,s.prefs.lockedSections)
            homeCache.get(key)?:HomeRecommendations.featured(visible,allowed={it.id !in s.locked && (if(it.kind==Kind.MOVIE) "Movies" else "Series") !in s.prefs.lockedSections},ranks=ranks).also {homeCache.put(key,it)}
        }
        computed=shelves
        a.store.saveHome(shelves)
    }
    LaunchedEffect(uiKey(s.channels),s.recent,s.positions,s.providers,s.hidden,s.hiddenGroups,s.locked,s.prefs.lockedSections) {resume=withContext(Dispatchers.Default) {WatchHistory.resume(s)}}
    // Until the catalog opens, last launch's shelves appear instantly (still honoring current locks).
    val shelves=computed ?: remember(cached,s.locked,s.hidden,s.prefs.lockedSections) {cached.filter {it.id !in s.locked && it.id !in s.hidden && (if(it.kind==Kind.MOVIE) "Movies" else "Series") !in s.prefs.lockedSections}}
    // Up to six heroes rotate like a streaming service's billboard; artwork details load once per title.
    val heroes=remember(shelves,resume) {(shelves.popular.take(4)+shelves.shows.take(1)+shelves.classics.take(1)).ifEmpty {listOfNotNull(shelves.hero?:resume.firstOrNull())}}
    var heroIndex by remember(heroes) {mutableIntStateOf(0)}
    var heroFocused by remember {mutableStateOf(false)}
    val details=remember {mutableStateMapOf<String,Channel>()}
    // The billboard pauses while focused and while a video plays over the (still composed) Home.
    LaunchedEffect(heroes,heroFocused,a.fullPlayer) {if(heroes.size>1 && !heroFocused && !a.fullPlayer) while(true){delay(9000);heroIndex=(heroIndex+1)%heroes.size}}
    LaunchedEffect(heroes) {
        for(c in heroes) {
            if(c.id in details || c.kind!=Kind.MOVIE) continue
            val p=s.providers.firstOrNull {it.id==c.provider}?:continue
            runCatching {withContext(Dispatchers.IO){Sources.details(p,c)}}.onSuccess {full->if(HomeRecommendations.isEligible(full) || HomeRecommendations.isFeatured(full)) details[c.id]=full}
        }
    }
    val hero=heroes.getOrNull(heroIndex)?.let {details[it.id]?:it}
    // After an import the dialogs close first; keep asking until Home's main control actually holds focus.
    val windowFocused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(a.focusHomeContent,a.homeEntryReady,windowFocused) {
        if(a.focusHomeContent && a.homeEntryReady && windowFocused) {
            delay(200)
            for(attempt in 0 until 12) {runCatching {a.homeEntry.requestFocus()};delay(150);if(heroFocused || a.homeEntryFocused) break}
            a.focusHomeContent=false
        }
    }
    val listState=rememberLazyListState();val heroScroll=rememberCoroutineScope()
    val scrolled by remember {derivedStateOf {listState.firstVisibleItemIndex>0 || listState.firstVisibleItemScrollOffset>160}}
    LaunchedEffect(scrolled) {a.homeScrolled=scrolled}
    DisposableEffect(Unit) {onDispose {a.homeScrolled=false}}
    val screenHeight=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val motion=LocalMotion.current
    LazyColumn(Modifier.fillMaxSize().focusRequester(a.homeContent).focusGroup().background(Ink),state=listState,contentPadding=PaddingValues(bottom=28.dp)) {
        hero?.let {c->item(key="hero") {
            Box(Modifier.fillMaxWidth().height(screenHeight*.80f)) {
                val art=c.backdrop.takeIf(::hasArt)?:c.logo
                if(motion) androidx.compose.animation.Crossfade(art,animationSpec=androidx.compose.animation.core.tween(700),label="hero art") {url->Art(url,Modifier.fillMaxSize(),Alignment.TopEnd)}
                else Art(art,Modifier.fillMaxSize(),Alignment.TopEnd)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(.94f),.33f to Color.Black.copy(.62f),.62f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(.45f),.22f to Color.Transparent,.62f to Color.Transparent,1f to Ink)))
                Column(Modifier.align(Alignment.BottomStart).padding(start=46.dp,bottom=44.dp).width(470.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    Text(if(c.kind==Kind.SERIES) "FEATURED SERIES" else if(HomeRecommendations.isFeatured(c)) "FEATURED FOR KIDS & FAMILY" else "FEATURED",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=1.8.sp,
                        modifier=Modifier.clip(RoundedCornerShape(3.dp)).background(LocalAccent.current).padding(horizontal=8.dp,vertical=4.dp))
                    Text(HomeRecommendations.title(if(c.kind==Kind.EPISODE) c.copy(name=c.group) else c),fontSize=44.sp,lineHeight=47.sp,fontWeight=FontWeight.ExtraBold,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis,
                        style=androidx.compose.ui.text.TextStyle(shadow=Shadow(Color.Black.copy(.6f),androidx.compose.ui.geometry.Offset(0f,2f),8f)))
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(c.certification.isNotBlank()) Text(c.certification.uppercase(),color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.border(1.dp,Color.White.copy(.7f),RoundedCornerShape(3.dp)).padding(horizontal=6.dp,vertical=2.dp))
                        Text(listOf(c.year.take(4),if(c.kind==Kind.MOVIE) "Movie" else "Series",c.genre.split(',','/').firstOrNull()?.trim().orEmpty()).filter {it.isNotBlank()}.joinToString("  •  "),color=Color(0xFFE0E0E0),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    }
                    if(c.description.isNotBlank()) Text(c.description,color=Color(0xFFE6E6E6),fontSize=12.5.sp,lineHeight=19.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.onFocusChanged {heroFocused=it.hasFocus;if(it.hasFocus) heroScroll.launch {listState.animateScrollToItem(0)}}.padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        EzButton(if((s.positions[c.id]?:0)>0) "Resume" else "Play",Icons.Outlined.PlayArrow,primary=true,modifier=Modifier.homeEntry(a),onClick={if(c.kind==Kind.SERIES) onDetails(c) else a.play(c)})
                        EzButton("More info",Icons.Outlined.Info,onClick={onDetails(c)})
                    }
                }
                if(heroes.size>1) Row(Modifier.align(Alignment.BottomEnd).padding(end=46.dp,bottom=54.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    heroes.indices.forEach {i->Box(Modifier.height(5.dp).width(if(i==heroIndex) 22.dp else 7.dp).clip(RoundedCornerShape(50)).background(if(i==heroIndex) Color.White else Color.White.copy(.4f)))}
                }
            }
        }}
        if(hero==null) item {Spacer(Modifier.height(84.dp))}
        if(resume.isNotEmpty()) item {
            Column(Modifier.padding(top=0.dp,bottom=8.dp)) {
                Text("Continue Watching",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(horizontal=28.dp,vertical=10.dp))
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
                if(s.providers.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {EzButton("Movies",primary=true,modifier=if(hero==null) Modifier.homeEntry(a) else Modifier,onClick={a.page="Movies"});EzButton("Shows",onClick={a.page="Series"});EzButton("Live TV",onClick={a.page="Live TV"})}
                else {
                    EzButton("Add a playlist",Icons.Outlined.PlaylistAdd,primary=true,modifier=if(hero==null) Modifier.homeEntry(a) else Modifier,onClick=onConnect)
                    // First launch: the only useful action is focused, so Select opens setup immediately.
                    LaunchedEffect(Unit) {delay(250);runCatching {a.homeEntry.requestFocus()}}
                }
                Text(if(s.providers.isEmpty()) "Your movies and shows appear here once a playlist is available." else "Browse your movies and shows to find your next watch.",color=Muted,fontSize=11.sp)
            }
        }
    }
}
