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
    /** Only a few visible shelves materialize; the complete library stays reachable through View all/search. */
    fun build(channels: List<Channel>): List<BrowseShelf> {
        if (channels.isEmpty()) return emptyList()
        fun best(compare: Comparator<Int>): IntArray {
            val queue=java.util.PriorityQueue(24,compare.reversed())
            for(index in channels.indices) {if(queue.size<24) queue.add(index) else if(compare.compare(index,queue.peek())<0) {queue.poll();queue.add(index)}}
            return queue.sortedWith(compare).toIntArray()
        }
        val top = best(compareBy<Int> { channels.catalogLabel(it).englishRank }
            .thenByDescending { channels.catalogLabel(it).rating + kotlin.math.ln(1 + channels.catalogLabel(it).popularity.coerceAtLeast(0.0)) }.thenBy { it })
        val result = arrayListOf(BrowseShelf("Recommended for you", channels.catalogIndices(top)))
        val newest = best(compareBy<Int> { channels.catalogLabel(it).englishRank }.thenByDescending { channels.catalogLabel(it).year }.thenBy { it })
        result += BrowseShelf("New releases", channels.catalogIndices(newest))
        for ((title, pattern) in genres) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val hits = channels.indices.asSequence().filter { val label = channels.catalogLabel(it); regex.containsMatchIn("${label.genre} ${label.group}") }.take(24).toList()
            if (hits.isNotEmpty()) result += BrowseShelf(title, channels.catalogIndices(hits.toIntArray()), pattern)
        }
        return result
    }
}
