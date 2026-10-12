package tv.ezano.app

import java.util.Locale

/** Language priority uses provider metadata and explicit labels, never artwork or a title's origin. */
object EnglishContent {
    private val english = Regex("(?i)(^|[^a-z])(en|eni|eng|english)([^a-z]|$)")
    private val regions = Regex("(?i)(^|[^a-z])(uk|gb|gbr|us|usa|au|aus|ca|can|ie|irl|nz|nzl|british|american|australian|canadian)([^a-z]|$)|united kingdom|united states|new zealand|ireland")
    private val foreign = Regex("(?i)(^|[^a-z])(fr|fra|french|de|deu|german|es|spa|spanish|it|ita|italian|pt|por|portuguese|ar|ara|arabic|hi|hin|hindi|ru|rus|russian|pl|pol|polish|tr|tur|turkish)([^a-z]|$)")
    private val languageNames = setOf("en", "eni", "eng", "english")
    fun rank(name: String, group: String, language: String = "", country: String = ""): Int {
        val labels = "$group $name"
        val languages = language.lowercase(Locale.ROOT).split(',', ';', '/', '|').map { it.trim().substringBefore('-').substringBefore('_') }
        if (english.containsMatchIn(labels) || languages.any { it in languageNames }) return 0
        if (foreign.containsMatchIn(group) || (languages.any { it.isNotBlank() && it != "und" })) return 3
        if (regions.containsMatchIn("$labels $country")) return 1
        return 2
    }
    fun rank(c: Channel) = rank(c.name, c.group, c.language, c.country)
    fun groupRank(group: String) = rank("", group)
}

/** Stable buckets retain provider/manual ordering within each language priority. No artwork pages decode. */
fun List<Channel>.englishFirst(): List<Channel> {
    val buckets = Array(4) { ArrayList<Int>() }
    for (index in indices) buckets[catalogLabel(index).englishRank.coerceIn(0, 3)] += index
    return catalogIndices(buckets.flatMap { it }.toIntArray())
}

data class BrowseShelf(val title: String, val channels: List<Channel>, val filter: String = "")

object BrowseShelves {
    private val genres = listOf("Comedy" to "comedy|sitcom", "Action & adventure" to "action|adventure", "Drama" to "drama", "Documentaries" to "documentary|documentaries|nature", "Animation" to "animation|anime|cartoon", "Science fiction" to "science.?fiction|sci.?fi", "Family favorites" to "family|kids|children")
    /** Kept off the front rows; the full catalog, categories and search still reach everything. */
    private val unsafe = Regex("(?i)\\b(?:xxx|adult|adults only|porn\\w*|erotic\\w*|sex\\w*|nsfw|18\\+|uncensored|explicit|playboy|hentai|nude|nudity|horror)\\b")
    private fun safe(label: CatalogLabel) = !unsafe.containsMatchIn("${label.name} ${label.group} ${label.genre}")

    /** Front rows lead with the most popular, recognisable titles the provider carries (PopularTitles: US hits,
     * G–PG-13 / up to TV-14), grouped by genre, then kids favourites, then the provider's own best-rated titles.
     * Only a few shelves materialize; the complete library stays reachable through View all/search.
     */
    fun build(channels: List<Channel>, kind: Kind = Kind.MOVIE, ranks: CuratedRanks? = null): List<BrowseShelf> {
        if (channels.isEmpty()) return emptyList()
        val movie = kind == Kind.MOVIE
        val result = ArrayList<BrowseShelf>()
        val shown = HashSet<String>()
        if (ranks != null) {
            val entries = if (movie) PopularTitles.movies else PopularTitles.shows
            val popular = HashMap<Int, Int>(); val kids = HashMap<Int, Int>()
            fun better(a: CatalogLabel, b: CatalogLabel) = compareValuesBy(a, b, { it.englishRank }, { it.name.length }) < 0
            for (index in channels.indices) {
                val label = channels.catalogLabel(index)
                if (label.englishRank > 2 || !safe(label)) continue
                ranks.popular[label.id]?.let { r -> val p = popular[r]; if (p == null || better(label, channels.catalogLabel(p))) popular[r] = index }
                ranks.kids[label.id]?.let { r -> val p = kids[r]; if (p == null || better(label, channels.catalogLabel(p))) kids[r] = index }
            }
            val ranked = popular.entries.sortedBy { it.key }.map { entries[it.key] to it.value }
            fun shelf(title: String, filter: String, pick: (PopularTitles.Entry) -> Boolean, min: Int = 4) {
                val hits = ranked.filter { pick(it.first) }.take(30).map { it.second }
                if (hits.size >= min) { result += BrowseShelf(title, channels.catalogIndices(hits.toIntArray()), filter); hits.forEach { shown += channels.catalogLabel(it).id } }
            }
            shelf(if (movie) "Popular movies" else "Popular shows", "__all", { true }, min = 1)
            shelf(if (movie) "New releases" else "New & trending", "__all", { it.year >= 2025 })
            shelf(if (movie) "Superheroes" else "Superhero shows", "superhero|marvel|comic", { PopularTitles.Tag.SUPERHERO in it.tags })
            shelf("Action & adventure", "action|adventure", { PopularTitles.Tag.ACTION in it.tags || PopularTitles.Tag.ADVENTURE in it.tags })
            shelf("Comedy", "comedy|sitcom", { PopularTitles.Tag.COMEDY in it.tags })
            shelf("Sci-fi & fantasy", "science.?fiction|sci.?fi|fantasy", { PopularTitles.Tag.SCIFI in it.tags || PopularTitles.Tag.FANTASY in it.tags })
            shelf(if (movie) "Drama" else "Drama series", "drama", { PopularTitles.Tag.DRAMA in it.tags })
            shelf("Crime & thrillers", "crime|thriller|mystery", { PopularTitles.Tag.CRIME in it.tags || PopularTitles.Tag.THRILLER in it.tags })
            if (!movie) shelf("Reality & competition", "reality|competition", { PopularTitles.Tag.REALITY in it.tags })
            val family = kids.entries.sortedBy { it.key }.take(30).map { it.value }
            if (family.size >= 4) { result += BrowseShelf("Kids & family", channels.catalogIndices(family.toIntArray()), "family|kids|children|animation"); family.forEach { shown += channels.catalogLabel(it).id } }
        }
        fun best(compare: Comparator<Int>): IntArray {
            val queue=java.util.PriorityQueue(24,compare.reversed())
            for(index in channels.indices) {
                val label=channels.catalogLabel(index)
                if(label.id in shown || !safe(label)) continue
                if(queue.size<24) queue.add(index) else if(compare.compare(index,queue.peek())<0) {queue.poll();queue.add(index)}
            }
            return queue.sortedWith(compare).toIntArray()
        }
        val top = best(compareBy<Int> { channels.catalogLabel(it).englishRank }
            .thenByDescending { channels.catalogLabel(it).rating + kotlin.math.ln(1 + channels.catalogLabel(it).popularity.coerceAtLeast(0.0)) }.thenBy { it })
        if (top.isNotEmpty()) result += BrowseShelf(if (result.isEmpty()) "Recommended for you" else "More to explore", channels.catalogIndices(top), "__all")
        if (ranks == null || result.size < 4) {
            val newest = best(compareBy<Int> { channels.catalogLabel(it).englishRank }.thenByDescending { channels.catalogLabel(it).year }.thenBy { it })
            if (newest.isNotEmpty()) result += BrowseShelf("Recently added", channels.catalogIndices(newest), "__all")
            for ((title, pattern) in genres) {
                if (result.any { it.title == title }) continue
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val hits = channels.indices.asSequence().filter { val label = channels.catalogLabel(it); safe(label) && label.englishRank <= 2 && regex.containsMatchIn("${label.genre} ${label.group}") }.take(24).toList()
                if (hits.isNotEmpty()) result += BrowseShelf(title, channels.catalogIndices(hits.toIntArray()), pattern)
            }
        }
        return result
    }
}
