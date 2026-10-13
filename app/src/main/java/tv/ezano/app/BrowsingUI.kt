package tv.ezano.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun SectionSearchField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var pendingSelectKey by remember { mutableIntStateOf(0) }
    val requester = focusRequester ?: remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val inputMode = LocalInputModeManager.current
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) { if(imeOpen) imeWasOpen=true else if(imeWasOpen) {imeWasOpen=false;editing=false;pendingSelectKey=0} }
    LaunchedEffect(editing) { if (editing) { delay(80); inputMode.requestInputMode(InputMode.Keyboard); keyboard?.show() } else keyboard?.hide() }
    LaunchedEffect(interactionSource) { interactionSource.interactions.collect { if (it is PressInteraction.Release) editing = true } }
    BackHandler(enabled = editing) { editing = false; pendingSelectKey = 0; keyboard?.hide() }
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth().focusRequester(requester)
        .onFocusChanged { if (!it.isFocused) {editing=false;pendingSelectKey=0} else if(!editing) keyboard?.hide() }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val select = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            val direction = remoteDirection(native.keyCode)
            val vertical = direction == androidx.compose.ui.focus.FocusDirection.Up || direction == androidx.compose.ui.focus.FocusDirection.Down
            if (direction != null && (vertical || !editing)) { if (native.action == android.view.KeyEvent.ACTION_DOWN) { editing = false; pendingSelectKey = 0; keyboard?.hide(); focus.moveFocus(direction) }; true }
            else if (!select) false else when (native.action) {
                android.view.KeyEvent.ACTION_DOWN -> { if (pendingSelectKey == 0) pendingSelectKey = native.keyCode; true }
                android.view.KeyEvent.ACTION_UP -> {
                    val wasPending = pendingSelectKey == native.keyCode
                    pendingSelectKey = 0
                    if (wasPending && !native.isCanceled && !editing) editing = true
                    wasPending
                }
                else -> pendingSelectKey == native.keyCode
            }
        }
        , interactionSource = interactionSource,
        placeholder = { Text(label, fontSize = 12.sp, maxLines = 1) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(17.dp)) } }) else null,
        readOnly = !editing,
        singleLine = true, shape = RoundedCornerShape(4.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onSearch = { editing = false; pendingSelectKey = 0; keyboard?.hide(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccent.current, unfocusedBorderColor = Color.White.copy(.14f),
            focusedContainerColor = Panel, unfocusedContainerColor = Panel.copy(.72f), focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLeadingIconColor = LocalAccent.current, unfocusedLeadingIconColor = Muted, cursorColor = LocalAccent.current))
}

// Catalogs can contain hundreds of thousands of titles. Keep the disk-backed view and
// perform scans away from the UI thread; only composed cells decode their artwork rows.
@Composable internal fun visibleCatalog(a: MainActivity, kind: Kind? = null): List<Channel> {
    return visibleCatalogState(a,kind).value.orEmpty()
}

@Composable private fun visibleCatalogState(a: MainActivity, kind: Kind? = null): State<List<Channel>?> {
    val s by a.store.libraryState()
    val loaded by a.store.catalogReady.collectAsState()
    val visible = uiPendingListState<Channel>(uiKey(s.channels), s.providers, s.hidden, s.hiddenGroups, s.order, kind, loaded) {
        a.store.visible(kind)
    }
    // While the saved catalog is still opening, screens show "Opening…" rather than an empty library.
    val opening = remember { mutableStateOf<List<Channel>?>(null) }
    return if (loaded) visible else opening
}

@Composable private fun catalogGroups(channels: List<Channel>): List<String> {
    val groups by uiListState<String>(uiKey(channels)) {
        channels.catalogLabels().map { it.group }.distinct().sortedWith(compareBy<String> {
                EnglishContent.groupRank(it)
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }).toList()
    }
    return groups
}

@Composable private fun settledQuery(query: String): String {
    var settled by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(100); settled = query.trim() }
    return settled
}

@Composable fun CatalogScreen(a: MainActivity, kind: Kind, onDetails: (Channel) -> Unit, searchFocus: FocusRequester) {
    val s by a.store.libraryState()
    val busy by a.store.busy.collectAsState()
    val catalog by visibleCatalogState(a,kind)
    val all=catalog.orEmpty()
    val groups=catalogGroups(all)
    var group by remember(kind) {mutableStateOf("")}
    var provider by remember(kind) {mutableStateOf("")}
    var query by remember(kind) {mutableStateOf("")}
    var genre by remember(kind) {mutableStateOf("")}
    var categories by remember {mutableStateOf(false)}
    val settled=settledQuery(query)
    val results by uiRetainedListState<Channel>(uiKey(all),group,provider,settled,genre) {
        val regex=genre.takeIf {it.isNotEmpty() && it!="__all"}?.let {Regex(it,RegexOption.IGNORE_CASE)}
        if(group.isEmpty() && provider.isEmpty() && settled.isEmpty() && regex==null) all
        else all.searchCatalog {(group.isEmpty() || it.group==group) && (provider.isEmpty() || it.provider==provider) && (settled.isEmpty() || it.name.contains(settled,true) || it.group.contains(settled,true)) && (regex==null || regex.containsMatchIn("${it.genre} ${it.group}"))}
    }
    val filtered=results.orEmpty()
    val shelves by produceState<List<BrowseShelf>?>(null,uiKey(filtered)) {value=withContext(Dispatchers.Default) {BrowseShelves.build(filtered,kind,Curated.ranks(a.store.state.value.channels))}}
    val gridState=rememberLazyGridState()
    val title=if(kind==Kind.MOVIE) "Movies" else "Shows"
    val shelfMode=settled.isEmpty() && group.isEmpty() && genre.isEmpty()
    LaunchedEffect(group,provider,settled,genre) {gridState.scrollToItem(0)}
    LaunchedEffect(a.page,a.focusCatalogSearch) {if(a.page==(if(kind==Kind.MOVIE) "Movies" else "Series") && a.focusCatalogSearch) {runCatching {searchFocus.requestFocus()};a.focusCatalogSearch=false}}
    Column(Modifier.fillMaxSize().padding(horizontal=30.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(title,fontSize=28.sp,color=Color.White,fontWeight=FontWeight.ExtraBold);Text(if(catalog==null) "Opening your saved collection…" else "${all.size} ${if(kind==Kind.MOVIE) "movies" else "series"} in your collection",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=3.dp))}
            SectionSearchField("Search ${title.lowercase()}",query,{query=it},Modifier.width(300.dp),searchFocus)
            EzButton("Categories",Icons.Outlined.FilterList,onClick={categories=true})
        }
        Row(Modifier.fillMaxWidth().padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("English first",color=Muted,fontSize=10.sp)
            if(!shelfMode || provider.isNotEmpty()) EzButton("All ${title.lowercase()}",onClick={query="";group="";genre="";provider=""})
            if(!shelfMode) Text("${filtered.size} results${if(settled.isNotEmpty()) " for “$settled”" else ""}",color=Muted,fontSize=10.sp)
        }
        when {
            catalog==null || results==null -> LoadingCatalog("Opening your ${title.lowercase()}…")
            all.isEmpty() && busy -> LoadingCatalog("Loading your ${title.lowercase()}…")
            all.isEmpty() -> EmptyState("No ${title.lowercase()} yet","Your playlist's ${title.lowercase()} will appear here.") {a.pendingImport=true}
            filtered.isEmpty() -> NoSearchResults(settled) {query="";group="";genre="";provider=""}
            shelfMode && shelves==null -> LoadingCatalog("Finding popular ${title.lowercase()}…")
            shelfMode -> LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=26.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                items(shelves.orEmpty(),key={it.title}) {shelf->
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {Text(shelf.title,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));BrowseChip("View all",false) {genre=shelf.filter.ifBlank {"__all"}}}
                        LazyRow(contentPadding=PaddingValues(3.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {items(shelf.channels.size,key={shelf.channels.catalogLabel(it).id}) {index->CatalogPoster(shelf.channels,index,s.favorites,onDetails=onDetails)}}
                    }
                }
            }
            else -> LazyVerticalGrid(GridCells.Adaptive(118.dp),state=gridState,modifier=Modifier.weight(1f),contentPadding=PaddingValues(top=10.dp,bottom=26.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {items(filtered.size,key={filtered.catalogLabel(it).id}) {index->CatalogPoster(filtered,index,s.favorites,onDetails=onDetails)}}
        }
    }
    if(categories) Modal("${title} categories",{categories=false}) {
        EzButton("All ${title.lowercase()}",primary=true,onClick={group="";genre="";provider="";categories=false})
        LazyColumn(Modifier.heightIn(max=290.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            if(s.providers.count {it.enabled}>1) {items(s.providers.filter {it.enabled},key={"provider:${it.id}"}) {p->EzButton(p.name,onClick={provider=p.id;group="";genre="";categories=false})}}
            items(groups,key={it}) {g->EzButton(g,onClick={group=g;genre="";categories=false})}
        }
    }
    if(a.pendingImport) ProvidersDialog(a) {a.pendingImport=false}
}

@Composable private fun LoadingCatalog(label: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalAccent.current, strokeWidth = 2.dp)
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun NoSearchResults(query: String, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(32.dp), tint = Muted)
        Text(if (query.isBlank()) "Nothing in this selection" else "No matches for “$query”", fontSize = 18.sp, color = Color.White)
        Text("Try a different title or choose another category.", color = Muted, fontSize = 12.sp)
        EzButton("Clear filters", onClick = onReset)
    }
}

@Composable private fun BrowseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Text(label, color = if (focused) Ink else if (selected) accent else Muted, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(9.dp)).background(if (focused) Color.White else if (selected) accent.copy(.10f) else Color.White.copy(.035f))
            .border(1.dp, if (focused) accent else if (selected) accent.copy(.28f) else Color.Transparent, RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 10.dp))
}

@Composable fun LiveScreen(a: MainActivity, onDetails: (Channel) -> Unit, onProgram: (Channel, Program) -> Unit, onConnect: () -> Unit) {
    val s by a.store.libraryState()
    val guideStatus by a.store.guideStatus.collectAsState()
    val busy by a.store.busy.collectAsState()
    val catalog by visibleCatalogState(a, Kind.LIVE)
    val all = catalog.orEmpty()
    val groups = catalogGroups(all)
    var group by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var selectedProgram by remember { mutableStateOf<Program?>(null) }
    var favoritePrompt by remember { mutableStateOf<Channel?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val settled = settledQuery(query)
    val results by uiRetainedListState<Channel>(uiKey(all), group, settled, events, s.favorites, s.groups) {
        if(events) all else all.searchCatalog { (group.isEmpty() || (group == "__favorites" && it.id in s.favorites) || it.group == group || it.id in s.groups[group].orEmpty()) && (settled.isBlank() || it.name.contains(settled, true)) }
    }
    val channels = results.orEmpty()
    val listState = rememberLazyListState()
    val importedChannelFocus = remember { FocusRequester() }
    // Back from a channel returns to the same spot in the guide: same filter, scrolled to it, its row focused.
    val restoreFocus = remember { FocusRequester() }
    var restoreId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a.fullPlayer) {
        if (a.fullPlayer || a.page != "Live TV") return@LaunchedEffect
        val id = a.lastWatchedId ?: return@LaunchedEffect
        a.lastWatchedId = null
        val index = withContext(Dispatchers.Default) { (0 until channels.size).firstOrNull { channels.catalogLabel(it).id == id } } ?: return@LaunchedEffect
        restoreId = id
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        selected = withContext(Dispatchers.Default) { channels[index] }; selectedProgram = null
        delay(160)
        // The list may still be settling after playback; retry briefly until the row holds focus.
        repeat(6) { if (runCatching { restoreFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(120) }
    }
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(uiKey(channels)) { selected = withContext(Dispatchers.Default) {channels.firstOrNull()}; selectedProgram = null; listState.scrollToItem(0) }
    LaunchedEffect(channels.isNotEmpty(), a.focusImportedChannel, windowFocused) {
        if (channels.isNotEmpty() && a.focusImportedChannel && windowFocused) {
            listState.scrollToItem(0)
            delay(250)
            inputMode.requestInputMode(InputMode.Keyboard)
            importedChannelFocus.requestFocus()
            a.focusImportedChannel = false
        }
    }
    val start = now - now % 1_800_000L + offset * GUIDE_WINDOW_MS
    val end = start + GUIDE_WINDOW_MS
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) { Text("Live TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text(if(catalog==null) "Opening your saved channels…" else "${all.size} channels · Your TV guide", color = Muted, fontSize = 10.sp) }
            SectionSearchField(if(events) "Search events, teams, or shows" else "Search channels", query, { query = it }, Modifier.width(300.dp))
            BrowseChip("Channels", !events) {events=false;query=""}
            BrowseChip("Find event", events) {events=true;query=""}
        }
        if(guideStatus.isNotBlank()) Text(guideStatus,color=Muted,fontSize=10.sp,lineHeight=13.sp,modifier=Modifier.padding(bottom=7.dp))
        if(events && catalog!=null) {
            LiveEventSearch(a,all,query,now,onProgram)
            return@Column
        }
        if(catalog==null || results==null) {
            LoadingCatalog("Opening your saved channels and guide…")
            return@Column
        }
        if (all.isEmpty()) {
            if (busy) LoadingCatalog("Loading channels and the TV guide…") else EmptyState("Live starts here", "Connect your playlist to watch channels and browse the guide.", onConnect)
            return@Column
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LazyColumn(Modifier.width(148.dp).fillMaxHeight().clip(RoundedCornerShape(13.dp)).background(Color.White.copy(.025f)), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item { Text("CHANNEL GROUPS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(9.dp)) }
                // Right from a group goes straight into the channel list (to its first row when it is on screen).
                val intoChannels = Modifier.focusProperties { if (channels.isNotEmpty() && listState.firstVisibleItemIndex == 0) right = importedChannelFocus }
                item { GuideCategory("All channels", group.isEmpty(), intoChannels) { group = "" } }
                item { GuideCategory("Favorites", group == "__favorites", intoChannels) { group = "__favorites" } }
                items((groups + s.groups.keys).distinct(), key = { it }) { g -> GuideCategory(g, group == g, intoChannels) { group = g } }
            }
            Column(Modifier.weight(1f)) {
                selected?.let { c ->
                    val current by produceState<Program?>(null,uiKey(s.programs),c.id,now/30_000) {value=withContext(Dispatchers.Default) {a.store.now(c)}}
                    GuidePreview(a, c, selectedProgram ?: current, { a.play(c) }, { onDetails(c) })
                }
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Muted, fontSize = 10.sp, modifier = Modifier.width(177.dp).padding(start = 8.dp))
                    repeat(3) { slot -> Text(timeLabel(start + slot * 1_800_000L), color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(start = 6.dp)) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.padding(bottom=6.dp)) {
                    BrowseChip("Earlier", false) { offset-- }
                    BrowseChip("Now", offset==0) { offset=0 }
                    BrowseChip("Later", false) { offset++ }
                }
                if (channels.isEmpty()) NoSearchResults(settled) { query = ""; group = "" }
                else LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(channels.size, key = { channels.catalogLabel(it).id }) { index ->
                        val label=channels.catalogLabel(index)
                        var loaded by remember(uiKey(channels),index) {mutableStateOf<Channel?>(null)}
                        LaunchedEffect(uiKey(channels),index) {loaded=withContext(Dispatchers.Default) {channels[index]}}
                        val c=loaded ?: remember(label.id) {Channel(label.id,label.provider,"",label.name,"",Kind.LIVE,label.group)}
                        val programs=remember(uiKey(s.programs),c.id) {a.store.programs(c)}
                        fun ready(action: (Channel)->Unit) {
                            val full=loaded
                            if(full!=null) action(full) else a.scope.launch {action(withContext(Dispatchers.Default) {channels[index]})}
                        }
                        GuideChannelRow(c, index + 1, c.id == selected?.id, c.id in s.favorites, programs, start, end, now,
                            channelModifier = Modifier.then(if (index == 0) Modifier.focusRequester(importedChannelFocus) else Modifier).then(if (label.id == restoreId) Modifier.focusRequester(restoreFocus) else Modifier),
                            onChannelFocus = { ready {selected = it; selectedProgram = null} }, onChannelClick = { ready(a::play) }, onChannelLongPress = { ready {favoritePrompt = it} },
                            onProgramFocus = { p -> ready {selected = it; selectedProgram = p} }, onProgramClick = { p -> ready {onProgram(it, p)} })
                    }
                }
            }
        }
    }
    favoritePrompt?.let { channel ->
        val isFavorite = channel.id in s.favorites
        AlertDialog(onDismissRequest = { favoritePrompt = null }, title = { Text(if (isFavorite) "Remove favorite?" else "Add favorite?") },
            text = { Text("${if (isFavorite) "Unfavorite" else "Favorite"} ${channel.name}?") },
            confirmButton = { TextButton(onClick = { a.store.favorite(channel); favoritePrompt = null }) { Text(if (isFavorite) "Unfavorite" else "Favorite") } },
            dismissButton = { TextButton(onClick = { favoritePrompt = null }) { Text("Cancel") } })
    }
}

@Composable private fun GuideCategory(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(7.dp)).background(if (focused) Color.White else if (selected) accent.copy(.13f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (focused) Ink else if (selected) accent else Color.White.copy(.75f), fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun GuidePreview(a: MainActivity, c: Channel, program: Program?, onPlay: () -> Unit, onDetails: () -> Unit) {
    val s by a.store.libraryState()
    Row(Modifier.fillMaxWidth().height(134.dp).padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.width(190.dp).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (s.prefs.preview && !a.store.needsPin(c) && a.player == null) PreviewVideo(a, c)
            else ChannelLogo(c, Modifier.size(100.dp))
            Text("LIVE", color = LocalAccent.current, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(c.name, color = LocalAccent.current, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(program?.title ?: "Watch live", color = Color.White, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(program?.let { "${timeLabel(it.start)} – ${timeLabel(it.end)}" } ?: c.group, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
            Text(program?.description?.ifBlank { "Select a program for details, recording and reminders." } ?: "No guide data for this channel. You can still watch live.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { BrowseChip("▶ Watch live", true, onPlay); BrowseChip("Channel options", false, onDetails) }
        }
    }
}

@Composable private fun ChannelLogo(c: Channel, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (c.logo.isBlank()) Icon(Icons.Outlined.Tv, null, Modifier.size(27.dp), tint = Muted)
        else AsyncImage(c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun GuideChannelRow(c: Channel, number: Int, selected: Boolean, favorite: Boolean, programs: List<Program>, start: Long, end: Long, now: Long, channelModifier: Modifier = Modifier, onChannelFocus: () -> Unit, onChannelClick: () -> Unit, onChannelLongPress: () -> Unit, onProgramFocus: (Program) -> Unit, onProgramClick: (Program) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var selectDownAt by remember(c.id) { mutableLongStateOf(0L) }
    var selectKey by remember(c.id) { mutableIntStateOf(0) }
    val accent = LocalAccent.current
    val segments by uiListState<GuideSegment>(uiKey(programs), start, end) { guideSegments(programs, start, end) }
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(channelModifier.width(174.dp).fillMaxHeight().onFocusChanged { focused = it.isFocused; if (it.isFocused) onChannelFocus() else { selectDownAt = 0L; selectKey = 0 } }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val isSelect = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (!isSelect) false else when (native.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        if (selectKey == 0 && native.repeatCount == 0) { selectKey = native.keyCode; selectDownAt = native.eventTime }
                        true
                    }
                    android.view.KeyEvent.ACTION_UP -> {
                        val matching = selectKey == native.keyCode
                        val held = if (matching) native.eventTime - selectDownAt else 0L
                        if (matching) { selectKey = 0; selectDownAt = 0L; if (!native.isCanceled) { if (held >= 500L) onChannelLongPress() else onChannelClick() } }
                        matching
                    }
                    else -> selectKey == native.keyCode
                }
            }
            .clip(RoundedCornerShape(6.dp)).background(if (focused) Color.White else if (selected) accent.copy(.10f) else Color.White.copy(.035f))
            .clickable(onClick = onChannelClick).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(number.toString(), color = if (focused) Ink.copy(.55f) else Muted, fontSize = 8.sp, modifier = Modifier.width(19.dp))
            ChannelLogo(c, Modifier.width(29.dp).height(27.dp))
            Text(c.name, color = if (focused) Ink else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (favorite) Text("★", color = if (focused) Ink else accent, fontSize = 9.sp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(.022f))) {
            val width = maxWidth
            segments.forEach { segment ->
                val p = segment.program
                var cellFocused by remember(p?.id, segment.start) { mutableStateOf(false) }
                val active = p != null && p.start <= now && p.end > now
                val left = ((segment.start - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                val fraction = ((segment.end - segment.start).toFloat() / (end - start)).coerceIn(0f, 1f)
                Column(Modifier.offset(x = width * left).width((width * fraction - 2.dp).coerceAtLeast(1.dp)).fillMaxHeight()
                    .onFocusChanged { cellFocused = it.isFocused; if (it.isFocused) { if (p == null) onChannelFocus() else onProgramFocus(p) } }
                    .clip(RoundedCornerShape(5.dp)).background(if (cellFocused) Color.White else if (active) accent.copy(.12f) else Color.White.copy(.035f))
                    .clickable { if (p == null) onChannelClick() else onProgramClick(p) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(p?.title ?: "Guide unavailable", color = if (cellFocused) Ink else if (p == null) Muted else Color.White, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (active) Text("ON NOW", color = if (cellFocused) Ink.copy(.65f) else accent, fontSize = 7.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (now in start until end) Box(Modifier.offset(x = width * ((now - start).toFloat() / (end - start))).width(1.dp).fillMaxHeight().background(accent.copy(.6f)))
        }
    }
}

// Retained for an older navigation state; new navigation exposes search in each section.
@Composable fun SearchScreen(a: MainActivity, onDetails: (Channel) -> Unit, onProgram: (Channel, Program) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = visibleCatalog(a)
    val settled = settledQuery(query)
    val results by uiListState<Channel>(uiKey(all), settled) { if (settled.isBlank()) emptyList() else all.searchCatalog { it.name.contains(settled, true) || it.group.contains(settled, true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Heading("Find your next favorite", "Movies, shows and channels from your playlists")
        SectionSearchField("Search your library", query, { query = it })
        Text("${results.size} results", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
        LazyVerticalGrid(GridCells.Adaptive(132.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp), contentPadding = PaddingValues(bottom = 25.dp)) {
            items(results.size, key = { results.catalogLabel(it).id }) { index -> CatalogPoster(results,index,emptySet(),portrait=results.catalogLabel(index).kind != Kind.LIVE,onDetails=onDetails) }
        }
    }
}
