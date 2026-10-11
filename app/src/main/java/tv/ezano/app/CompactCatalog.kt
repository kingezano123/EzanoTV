package tv.ezano.app

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Immutable catalog pages retain compressed data, not hundreds of thousands of artwork URLs.
 * Only recently used pages are decoded. Index views keep filtering from expanding the catalog.
 */
class CompactCatalog private constructor(
    private val pages: List<ByteArray>, private val pageRows: List<Int>, private val labels: List<CatalogLabel>, override val size: Int
) : AbstractList<Channel>(), java.util.RandomAccess {
    internal val decodedPages = java.util.concurrent.atomic.AtomicInteger()
    internal var restoredIndex = false
        private set
    internal fun label(index: Int): CatalogLabel = labels[index]
    private val cache = object : LinkedHashMap<Int, List<Channel>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Channel>>?) = size > 6
    }
    override fun get(index: Int): Channel {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Catalog index $index")
        val page = index / PAGE_SIZE
        val rows = synchronized(cache) { cache[page] } ?: run {
            // A cold page must not block UI access to other cached pages while it inflates.
            val decoded = decodePage(page)
            synchronized(cache) { cache[page] ?: decoded.also { cache[page] = it } }
        }
        return rows[index % PAGE_SIZE]
    }
    private fun decodePage(page: Int): List<Channel> = GZIPInputStream(pages[page].inputStream()).reader().use {
        decodedPages.incrementAndGet()
        val json = JSONArray(it.readText())
        require(json.length() == pageRows[page]) { "Catalog page row count mismatch" }
        List(json.length()) { row -> Json.channel(json.getJSONObject(row)).also { channel ->
            require(channel.id == labels[page * PAGE_SIZE + row].id) { "Catalog page label mismatch" }
        } }
    }
    internal fun scanReader(): (Int) -> Channel {
        var currentPage = -1
        var rows = emptyList<Channel>()
        return { index ->
            val page = index / PAGE_SIZE
            if (page != currentPage) {
                // Each scan retains just its current page and never churns the UI's LRU.
                rows = synchronized(cache) { cache[page] } ?: decodePage(page)
                currentPage = page
            }
            rows[index % PAGE_SIZE]
        }
    }
    override fun iterator(): Iterator<Channel> = listIterator()
    override fun listIterator(): ListIterator<Channel> = listIterator(0)
    override fun listIterator(index: Int): ListIterator<Channel> = CatalogIterator(size, index, scanReader())
    internal val compressedBytes: Long get() = pages.sumOf { it.size.toLong() }
    internal fun storedPages(): List<StoredCatalogPage> = pages.indices.map { StoredCatalogPage(pageRows[it], pages[it]) }
    internal fun storedLabels(): List<CatalogLabel> = labels

    class Builder {
        private val pages = ArrayList<ByteArray>()
        private val pageRows = ArrayList<Int>()
        private val pending = ArrayList<Channel>(PAGE_SIZE)
        private val ids = HashSet<String>()
        private val labels = ArrayList<CatalogLabel>()
        private val strings = HashMap<String, String>()
        private fun shared(value: String) = strings.getOrPut(value) { value }
        var size = 0; private set
        fun add(channel: Channel) {
            if (!ids.add(channel.id)) return
            labels += CatalogLabel.from(channel).let { it.copy(provider=shared(it.provider), group=shared(it.group)) }
            pending += channel; size++
            if (pending.size == PAGE_SIZE) flushPage()
        }
        fun addAll(channels: Iterable<Channel>) { channels.forEach(::add) }
        private fun flushPage() {
            if (pending.isEmpty()) return
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).writer().use { output ->
                output.write("[")
                pending.forEachIndexed { index, channel ->
                    if (index > 0) output.write(",")
                    output.write(with(Json) { channel.json().toString() })
                }
                output.write("]")
            }
            pages += bytes.toByteArray(); pageRows += pending.size; pending.clear()
        }
        fun build(): CompactCatalog { flushPage(); return CompactCatalog(pages.toList(), pageRows.toList(), labels.toList(), size) }
    }
    companion object {
        internal const val PAGE_SIZE = 32
        internal fun fromStorage(pageSize: Int, size: Int, pages: List<StoredCatalogPage>, labels: List<CatalogLabel>): CompactCatalog {
            require(pageSize == PAGE_SIZE) { "Unsupported compact catalog page size" }
            require(size >= 0 && labels.size == size) { "Compact catalog label count mismatch" }
            val expectedPages = if (size == 0) 0 else (size + PAGE_SIZE - 1) / PAGE_SIZE
            require(pages.size == expectedPages) { "Compact catalog page count mismatch" }
            var rowTotal = 0
            pages.forEachIndexed { index, page ->
                val expectedRows = minOf(PAGE_SIZE, size - rowTotal)
                require(page.rows == expectedRows && page.bytes.isNotEmpty()) { "Compact catalog page row count mismatch" }
                rowTotal += page.rows
            }
            require(rowTotal == size) { "Compact catalog row count mismatch" }
            val ids = HashSet<String>(size)
            val shared = HashMap<String, String>()
            val interned = labels.map { label ->
                require(label.id.isNotEmpty() && Kind.values().contains(label.kind) && label.familyScore.isFinite()) { "Invalid compact catalog label" }
                require(ids.add(label.id)) { "Duplicate compact catalog channel id" }
                label.copy(provider=shared.getOrPut(label.provider) { label.provider },group=shared.getOrPut(label.group) { label.group })
            }
            return CompactCatalog(pages.map { it.bytes }, pages.map { it.rows }, interned, size).also { it.restoredIndex = true }
        }
    }
}

internal data class StoredCatalogPage(val rows: Int, val bytes: ByteArray)

private class ChannelIndexView(private val source: List<Channel>, private val indices: IntArray) : AbstractList<Channel>(), java.util.RandomAccess {
    override val size get() = indices.size
    override fun get(index: Int): Channel = source[indices[index]]
    fun label(index: Int): CatalogLabel = source.catalogLabel(indices[index])
    fun scanReader(): (Int) -> Channel {
        val read = source.scanReader()
        return { index -> read(indices[index]) }
    }
    override fun iterator(): Iterator<Channel> = listIterator()
    override fun listIterator(): ListIterator<Channel> = listIterator(0)
    override fun listIterator(index: Int): ListIterator<Channel> = CatalogIterator(size, index, scanReader())
}

private fun List<Channel>.scanReader(): (Int) -> Channel = when (this) {
    is CompactCatalog -> scanReader()
    is ChannelIndexView -> scanReader()
    else -> { index -> this[index] }
}

/** One local page per traversal, including filtered/reordered views; List equality is unchanged. */
private class CatalogIterator(private val size: Int, index: Int, private val read: (Int) -> Channel) : ListIterator<Channel> {
    private var cursor = index
    init { if (index !in 0..size) throw IndexOutOfBoundsException("Catalog iterator index $index") }
    override fun hasNext() = cursor < size
    override fun hasPrevious() = cursor > 0
    override fun nextIndex() = cursor
    override fun previousIndex() = cursor - 1
    override fun next(): Channel {
        if (!hasNext()) throw NoSuchElementException()
        return read(cursor).also { cursor++ }
    }
    override fun previous(): Channel {
        if (!hasPrevious()) throw NoSuchElementException()
        return read(cursor - 1).also { cursor-- }
    }
}

/** Like filter, but retains only integer offsets and leaves the catalog compressed. */
fun List<Channel>.selectChannels(predicate: (Channel) -> Boolean): List<Channel> {
    val matches = IntArray(size); var count = 0
    forEachIndexed { index, channel -> if (predicate(channel)) matches[count++] = index }
    return if (count == size) this else ChannelIndexView(this, matches.copyOf(count))
}

fun List<Channel>.orderChannels(order: Map<String, Int>): List<Channel> {
    if (order.isEmpty()) return this
    val pinned = ArrayList<Pair<Int, Int>>()
    val rest = IntArray(size); var count = 0
    for (index in indices) {
        val channel = catalogLabel(index)
        val rank = order[channel.id]
        if (rank == null) rest[count++] = index else pinned += index to rank
    }
    val indices = IntArray(size); var cursor = 0
    pinned.sortedBy { it.second }.forEach { indices[cursor++] = it.first }
    for (i in 0 until count) indices[cursor++] = rest[i]
    return ChannelIndexView(this, indices)
}

/** Resident browsing metadata. Large descriptions, URLs and artwork stay in compressed pages.
 * Built once while importing/restoring, never by opening a tab or typing a query.
 */
data class CatalogLabel(val id: String, val provider: String, val name: String, val kind: Kind,
    val group: String, val familyEligible: Boolean, val familyScore: Double,
    val englishRank: Int = EnglishContent.rank(name,group), val genre: String = "", val year: String = "", val rating: Double = 0.0, val popularity: Double = 0.0) {
    companion object {
        fun from(c: Channel): CatalogLabel {
            val eligible = HomeRecommendations.isEligible(c)
            return CatalogLabel(c.id,c.provider,c.name,c.kind,c.group,eligible,if(eligible) HomeRecommendations.score(c) else 0.0,EnglishContent.rank(c),c.genre,c.year,c.rating.toDoubleOrNull()?.takeIf {it.isFinite()}?:0.0,c.popularity)
        }
    }
}

internal fun List<Channel>.catalogIndices(indices: IntArray): List<Channel> = ChannelIndexView(this,indices)

fun List<Channel>.catalogLabel(index: Int): CatalogLabel = when(this) {
    is CompactCatalog -> label(index)
    is ChannelIndexView -> label(index)
    else -> CatalogLabel.from(this[index])
}

fun List<Channel>.catalogLabels(): Sequence<CatalogLabel> = indices.asSequence().map { catalogLabel(it) }

/** Filtering and lazy-layout keys must never inflate catalog pages. */
fun List<Channel>.selectCatalog(predicate: (CatalogLabel) -> Boolean): List<Channel> {
    val matches=IntArray(size); var count=0
    for(index in indices) if(predicate(catalogLabel(index))) matches[count++]=index
    return if(count==size) this else ChannelIndexView(this,matches.copyOf(count))
}

/** Superseded searches stop promptly instead of competing with the newest query. */
suspend fun List<Channel>.searchCatalog(predicate: (CatalogLabel) -> Boolean): List<Channel> {
    val matches=IntArray(size); var count=0
    for(index in indices) {
        if(index % 256 == 0) kotlinx.coroutines.yield()
        if(predicate(catalogLabel(index))) matches[count++]=index
    }
    return if(count==size) this else ChannelIndexView(this,matches.copyOf(count))
}
