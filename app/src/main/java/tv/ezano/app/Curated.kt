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
)

object Curated {
    private val lock = Any()
    @Volatile private var cached: Pair<Any, CuratedRanks>? = null

    fun ranks(catalog: List<Channel>): CuratedRanks {
        cached?.let { (source, ranks) -> if (source === catalog) return ranks }
        synchronized(lock) {
            cached?.let { (source, ranks) -> if (source === catalog) return ranks }
            val popular = HashMap<String, Int>(); val kids = HashMap<String, Int>()
            for (index in catalog.indices) {
                val label = catalog.catalogLabel(index)
                if (label.kind != Kind.MOVIE && label.kind != Kind.SERIES) continue
                val movie = label.kind == Kind.MOVIE
                val rank = if (movie) PopularTitles.movieRank(label.name, label.year) else PopularTitles.showRank(label.name, label.year)
                if (rank >= 0) popular[label.id] = rank
                val kid = if (movie) FeaturedTitles.movieRank(label.name, label.year) else FeaturedTitles.showRank(label.name, label.year)
                if (kid >= 0) kids[label.id] = kid
            }
            return CuratedRanks(popular, kids).also { cached = catalog to it }
        }
    }
}
