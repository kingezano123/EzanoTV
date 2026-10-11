package tv.ezano.app

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Large immutable collections are effect dependencies, not values to compare row by row. */
internal class UiIdentity(private val value: Any?) {
    override fun equals(other: Any?) = other is UiIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
internal fun uiKey(value: Any?): Any = UiIdentity(value)

/** Titles are available immediately; cold artwork/detail pages are decoded off the TV UI thread. */
@Composable internal fun CatalogPoster(channels: List<Channel>, index: Int, favorites: Set<String>,
    portrait: Boolean = true, home: Boolean = false, onDetails: (Channel) -> Unit) {
    val label=channels.catalogLabel(index)
    var loaded by remember(uiKey(channels),index) { mutableStateOf<Channel?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(uiKey(channels),index) { loaded=withContext(Dispatchers.Default) {channels[index]} }
    val item=loaded ?: remember(label.id) {Channel(label.id,label.provider,"",label.name,"",label.kind,label.group)}
    Poster(if(home) HomeRecommendations.homeCard(item) else item, portrait=portrait,
        favorite=label.id in favorites, onClick={
            val ready=loaded
            if(ready!=null) onDetails(ready)
            else scope.launch { val full=withContext(Dispatchers.Default) {channels[index]};onDetails(full) }
        })
}

/** Null means preparing the view, not an empty saved library. */
@Composable internal fun <T> uiPendingListState(vararg keys: Any?, compute: suspend () -> List<T>): State<List<T>?> {
    val result = remember(*keys) { mutableStateOf<List<T>?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(*keys) { result.value = withContext(Dispatchers.Default) { compute() } }
    return result
}

@Composable internal fun <T> uiListState(vararg keys: Any?, compute: suspend () -> List<T>): State<List<T>> {
    val result = remember { mutableStateOf<List<T>>(emptyList(), referentialEqualityPolicy()) }
    LaunchedEffect(*keys) { result.value = withContext(Dispatchers.Default) { compute() } }
    return result
}

/** Collect snapshots without traversing their catalog in Compose's structural state policy. */
@Composable internal fun Store.libraryState(): State<Library> {
    val result = remember(this) { mutableStateOf(state.value, referentialEqualityPolicy()) }
    LaunchedEffect(this) { state.collect { result.value = it } }
    return result
}
