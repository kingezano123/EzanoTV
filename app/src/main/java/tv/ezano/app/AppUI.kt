package tv.ezano.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable fun EzanoRoot(a: MainActivity) {
    val s by a.store.libraryState(); val ready by a.store.ready.collectAsState(); val busy by a.store.busy.collectAsState(); val error by a.store.error.collectAsState()
    var providerModal by remember { mutableStateOf(false) }; var details by remember { mutableStateOf<Channel?>(null) }; var program by remember { mutableStateOf<Pair<Channel,Program>?>(null) }; var exit by remember { mutableStateOf(false) }
    LaunchedEffect(a.playing?.id) {if(a.playing!=null){details=null;program=null}}
    val accent=if(s.prefs.accent=="white") Color.White else Lime
    MaterialTheme(colorScheme=darkColorScheme(primary=accent,background=Ink,surface=Panel,onSurface=Color.White),typography=EzanoTypography) { CompositionLocalProvider(LocalAccent provides accent,LocalMotion provides !s.prefs.reduceMotion) {
        if(!ready) {StartupRecovery(a);return@CompositionLocalProvider}
        val movieSearchFocus = remember { FocusRequester() }
        BackHandler { when { a.fullPlayer -> a.browsePlayback(); a.page!="Home" -> a.page="Home"; else ->exit=true } }
        Box(Modifier.fillMaxSize().background(Ink)) {
            if(a.fullPlayer && a.player!=null) PlayerScreen(a)
            else {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(top=if(a.page=="Home") 0.dp else 82.dp)) { when(a.page) {
                        "Home"->CinemaHome(a,{details=it},{a.admin {providerModal=true}})
                        "Live TV"->LiveScreen(a,{details=it},{c,p->program=c to p},{a.admin {providerModal=true}})
                        "Movies"->CatalogScreen(a,Kind.MOVIE,{details=it},movieSearchFocus)
                        "Series"->CatalogScreen(a,Kind.SERIES,{details=it},movieSearchFocus)
                        "Search"->SearchScreen(a,{details=it},{c,p->program=c to p})
                        "My list"->SavedScreen(a) {details=it}
                        "Recordings"->RecordingsScreen(a)
                        "Settings"->SettingsScreen(a) {providerModal=true}
                    } }
                    TopNavigation(a) {a.admin {providerModal=true}}
                }
            }
            if(error.isNotBlank() && !a.fullPlayer) Row(Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF47262A)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) { Text(error,color=Color.White,fontSize=12.sp,modifier=Modifier.weight(1f)); EzButton("Dismiss",onClick={a.store.error.value=""}) }
            if(a.message.isNotBlank()) { val message=a.message; LaunchedEffect(message) { delay(6500); if(a.message==message) a.message="" }; Text(a.message,color=Color.White,fontSize=12.sp,modifier=Modifier.align(Alignment.BottomCenter).padding(22.dp).clip(RoundedCornerShape(10.dp)).background(Panel).border(1.dp,accent.copy(.3f),RoundedCornerShape(10.dp)).padding(15.dp)) }
        }
        if(providerModal) ProvidersDialog(a) { providerModal=false }
        details?.let { c-> DetailDialog(a,c,onDismiss={details=null},onProgram={p->details=null; program=c to p}) }
        program?.let { (c,p)->ProgramDialog(a,c,p) {program=null} }
        a.pinRequest?.let { action->PinDialog(a,onDismiss={a.pinRequest=null}) { a.pinRequest=null; action() } }
        if(exit) Modal("Leave Ezano TV?",{exit=false}) { Text("Your playlists and watch progress are saved.",color=Muted); Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { EzButton("Keep watching",primary=true,onClick={exit=false}); EzButton("Exit",onClick={a.finish()}) } }
    } }
}
@Composable fun ContentRow(title: String,items: List<Channel>,favorites: Set<String>,onDetails: (Channel)->Unit,subtitle: String="",portrait: Boolean=false) {
    if(items.isEmpty()) return
    Column(Modifier.padding(top=10.dp,bottom=6.dp)) { Row(Modifier.padding(start=28.dp,end=28.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) { Text(title,fontSize=17.sp,color=Color.White,fontWeight=FontWeight.SemiBold); Spacer(Modifier.weight(1f)); if(subtitle.isNotBlank()) Text(subtitle,color=Muted,fontSize=10.sp) }; LazyRow(contentPadding=PaddingValues(horizontal=25.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { items(items,key={it.id}) {c->Poster(c,favorite=c.id in favorites,portrait=portrait,onClick={onDetails(c)})} } }
}
@Composable fun EmptyState(title: String,body: String,onConnect: ()->Unit) { Column(Modifier.fillMaxWidth().padding(vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) { Icon(Icons.Outlined.Tv,null,Modifier.size(43.dp),tint=LocalAccent.current); Text(title,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Color.White); Text(body,fontSize=12.sp,color=Muted); EzButton("Manage playlists",primary=true,onClick=onConnect) } }
@Composable fun SavedScreen(a: MainActivity,onDetails: (Channel)->Unit) {
    val s by a.store.libraryState();val real=visibleCatalog(a);var group by remember {mutableStateOf("Favorites")}
    val saved by uiListState<Channel>(uiKey(real),group,s.favorites,s.groups){val ids=if(group=="Favorites")s.favorites else s.groups[group].orEmpty().toSet();real.searchCatalog {it.id in ids}}
    val sections by uiListState<Pair<Kind,List<Channel>>>(uiKey(saved)){listOf(Kind.LIVE,Kind.MOVIE,Kind.SERIES).map {kind->kind to saved.searchCatalog {it.kind==kind}}.filter {it.second.isNotEmpty()}}
    Column(Modifier.fillMaxSize().padding(horizontal=28.dp)) {
        Heading("My stuff", "Your favorite channels, movies and shows")
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=5.dp)){item{EzButton("Favorites",selected=group=="Favorites",onClick={group="Favorites"})};items(s.groups.keys.toList()){g->EzButton(g,selected=group==g,onClick={group=g})}}
        Spacer(Modifier.height(14.dp));if(saved.isEmpty())Text("Add channels and titles to your list to find them here.",color=Muted,fontSize=12.sp)
        LazyVerticalGrid(GridCells.Adaptive(118.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=30.dp)){
            sections.forEach {(kind,list)->
                item(span={GridItemSpan(maxLineSpan)}){Text(when(kind){Kind.LIVE->"Favorite channels";Kind.MOVIE->"Movies";else->"Shows"},color=Color.White,fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))}
                items(list.size,key={list.catalogLabel(it).id}){index->if(kind==Kind.LIVE)FavoriteChannel(list,index,{a.play(it)},onDetails)else CatalogPoster(list,index,s.favorites,portrait=true,onDetails=onDetails)}
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun FavoriteChannel(channels: List<Channel>,index: Int,onWatch:(Channel)->Unit,onDetails:(Channel)->Unit){
    val label=channels.catalogLabel(index);var c by remember(uiKey(channels),index){mutableStateOf<Channel?>(null)};var focused by remember {mutableStateOf(false)}
    LaunchedEffect(uiKey(channels),index){c=withContext(Dispatchers.Default){channels[index]}}
    Column(Modifier.onFocusChanged{focused=it.isFocused}.combinedClickable(onClick={c?.let(onWatch)},onLongClick={c?.let(onDetails)})){
        Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(4.dp)).background(Panel).border(if(focused)2.dp else 0.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(4.dp)),contentAlignment=Alignment.Center){
            c?.logo?.takeIf {it.isNotBlank()}?.let {coil.compose.AsyncImage(it,contentDescription=null,contentScale=androidx.compose.ui.layout.ContentScale.Fit,modifier=Modifier.padding(13.dp).fillMaxSize())}?:Icon(Icons.Outlined.LiveTv,null,tint=Color.White,modifier=Modifier.size(25.dp))
        }
        Text(label.name,color=Color.White,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
    }
}

@Composable private fun StartupRecovery(a: MainActivity) {
    val opening by a.store.opening.collectAsState();val status by a.store.openingStatus.collectAsState();val error by a.store.error.collectAsState()
    var restore by remember {mutableStateOf(false)};var passphrase by remember {mutableStateOf("")}
    BackHandler {a.finish()}
    Box(Modifier.fillMaxSize().background(Ink),contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=560.dp).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("EZANO",color=Color.White,fontSize=40.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=3.sp)
            // Opening always stays on this screen until the library is ready; it never asks to close the app.
            if(opening || error.isBlank()){
                CircularProgressIndicator(Modifier.size(30.dp),color=LocalAccent.current,strokeWidth=2.5.dp)
                Text(status.ifBlank {"Loading your library…"},color=Muted,fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Text("Saved library needs attention",color=Color.White,fontSize=21.sp)
                Text(error,color=Muted,fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    EzButton("Retry",primary=true,onClick={a.store.retryOpening()});EzButton("Restore backup",onClick={restore=true})
                }
            }
        }
    }
    if(restore)Modal("Restore encrypted backup",{restore=false}){
        Text("Choose an exported Ezano backup to restore your saved collection.",color=Muted)
        Field("Backup passphrase",passphrase,{passphrase=it},secret=true)
        EzButton("Choose backup",onClick={a.backup(passphrase,true);passphrase="";restore=false})
    }
    if(a.message.isNotBlank())Modal("Backup recovery",{a.message=""}){Text(a.message,color=Color.White)}
}
