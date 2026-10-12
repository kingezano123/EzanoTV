package tv.ezano.app

/** Curated-list positions for every catalog title, matched once per catalog and shared by Home,
 * Movies and Shows. Matching normalizes each provider title, which is the slow part on entry-level
 * TV sticks with 150,000+ titles, so it is never repeated per screen or per filter.
 */
class CuratedRanks(
    /** Title id → position in PopularTitles (movies or shows by the title's kind). */
    val popular: Map<String, Int>,
    /** Title id → position in FeaturedTitles (kids & family). */
    val kids: Map<String, Int>,
    /** Movies/series whose labels look adult or horror; kept off front rows. */
    val unsafe: Set<String> = emptySet(),
) {
    fun safe(label: CatalogLabel) = label.id !in unsafe
}

object Curated {
    /** Kept off the front rows; the full catalog, categories and search still reach everything. */
    internal val unsafeTitle = Regex("(?i)\\b(?:xxx|adult|adults only|porn\\w*|erotic\\w*|sex\\w*|nsfw|18\\+|uncensored|explicit|playboy|hentai|nude|nudity|horror)\\b")
    private val lock = Any()
    @Volatile private var cached: Pair<Any, CuratedRanks>? = null

    fun ranks(catalog: List<Channel>): CuratedRanks {
        cached?.let { (source, ranks) -> if (source === catalog) return ranks }
        synchronized(lock) {
            cached?.let { (source, ranks) -> if (source === catalog) return ranks }
            val popular = HashMap<String, Int>(); val kids = HashMap<String, Int>(); val unsafe = HashSet<String>()
            for (index in catalog.indices) {
                val label = catalog.catalogLabel(index)
                if (label.kind != Kind.MOVIE && label.kind != Kind.SERIES) continue
                val movie = label.kind == Kind.MOVIE
                if (unsafeTitle.containsMatchIn(label.name) || unsafeTitle.containsMatchIn(label.group) || unsafeTitle.containsMatchIn(label.genre)) unsafe += label.id
                // Normalize once and reuse the key for both curated lists.
                val key = FeaturedTitles.normalize(label.name)
                if (key.isEmpty()) continue
                val year = FeaturedTitles.providerYear(label.name, label.year)
                val rank = if (movie) PopularTitles.movieRankKey(key, year) else PopularTitles.showRankKey(key, year)
                if (rank >= 0) popular[label.id] = rank
                val kid = if (movie) FeaturedTitles.movieRankKey(key, year) else FeaturedTitles.showRankKey(key, year)
                if (kid >= 0) kids[label.id] = kid
            }
            return CuratedRanks(popular, kids, unsafe).also { cached = catalog to it }
        }
    }
}

/** Small identity-keyed memo for derived screens (shelves, Home), so revisiting a tab or returning from
 * playback reuses the previous result instead of rescanning a 150,000-title catalog.
 */
internal class ScreenCache<T>(private val capacity: Int = 4) {
    private val entries = LinkedHashMap<List<Any?>, T>(8, .75f, true)
    @Synchronized fun get(key: List<Any?>): T? = entries[key]
    @Synchronized fun put(key: List<Any?>, value: T) { entries[key] = value; while (entries.size > capacity) entries.remove(entries.keys.first()) }
}
