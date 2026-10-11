package tv.ezano.app

import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ln

/** Home is an opt-in showcase: missing suitability/language metadata never means safe. */
object HomeRecommendations {
    private val adult = Regex("(?i)(?:\\b(?:xxx|adult|porn|erotic|horror|uncensored|explicit|thriller|crime|mature|18\\+|16\\+)\\b|\\b(?:tv[ -]?ma|nc[ -]?17|pg[ -]?13|tv[ -]?14)\\b)")
    private val family = Regex("(?i)\\b(?:family|families|kids|children|children's|preschool|pre-school)\\b")
    private val englishGroup = Regex("(?i)(?:\\[(?:en|eng|us|usa)\\]|(?:^|[|:/ ])(?:en|eng|us|usa)(?:$|[|:/ ])|\\b(?:english|american|united states)\\b)")
    private val foreignGroup = Regex("(?i)(?:\\[(?:pl|fr|tr|de|es|it|pt|ar|hi|ru|nl|ro|gr|in|br)\\]|^(?:pl|fr|tr|de|es|it|pt|ar|hi|ru|nl|ro|gr|in|br)\\s*[|:/]|\\b(?:polish|french|turkish|german|spanish|italian|portuguese|arabic|hindi|russian|dutch|romanian|greek|bollywood)\\b)")
    private val safeCertificates = setOf("G","PG","TV-Y","TV-Y7","TV-Y7-FV","TV-G","U","UC")
    private val englishLanguages = setOf("en","eng","english","en-us","en-gb","en-ca","en-au")
    private val usCountries = setOf("us","usa","united states","united states of america")

    fun isEligible(c: Channel): Boolean {
        if(c.kind != Kind.MOVIE && c.kind != Kind.SERIES) return false
        val label="${c.name} ${c.group} ${c.genre}"
        if(adult.containsMatchIn(label)) return false
        val certificate=c.certification.trim().uppercase(Locale.ROOT).replace('_','-').replace("TV ","TV-")
        // Unknown, unrated, teen or mature certificates override a misleading family category.
        if(certificate.isNotEmpty() && certificate !in safeCertificates) return false
        if(certificate.isEmpty() && !family.containsMatchIn("${c.group} ${c.genre}")) return false
        return EnglishContent.rank(c) <= 1
    }

    fun isUs(c: Channel): Boolean = c.country.lowercase(Locale.ROOT).split(',', ';', '/', '|').any {it.trim() in usCountries} || Regex("(?i)(?:\\[(?:us|usa)\\]|\\b(?:us|usa|american|united states)\\b)").containsMatchIn(c.group)

    fun score(c: Channel): Double {
        val review=c.rating.toDoubleOrNull()?.takeIf {it.isFinite()}?.coerceIn(0.0,10.0)?:0.0
        val popularity=c.popularity.takeIf {it.isFinite() && it>0}?:0.0
        return (if(isUs(c)) 25.0 else 0.0) + review*5 + ln(1+popularity).coerceAtMost(15.0)*2
    }

    data class Selection(val movies: List<Channel> = emptyList(),val shows: List<Channel> = emptyList()) {
        val mixed: List<Channel> get() = buildList {
            for(i in 0 until maxOf(movies.size,shows.size)) {movies.getOrNull(i)?.let(::add);shows.getOrNull(i)?.let(::add)}
        }
    }

    /** Rank the resident index, then read only the small shelves we actually display. */
    fun selectIndexed(channels: List<Channel>, perKind: Int = 30, allowed: (CatalogLabel) -> Boolean = { true }): Selection {
        if(perKind<=0) return Selection()
        val comparator=compareBy<Int> {channels.catalogLabel(it).familyScore}
            .thenByDescending {channels.catalogLabel(it).name.lowercase(Locale.ROOT)}
            .thenByDescending {channels.catalogLabel(it).id}
        val movies=PriorityQueue(comparator); val shows=PriorityQueue(comparator)
        for(index in channels.indices) {
            val label=channels.catalogLabel(index)
            if(!label.familyEligible || !allowed(label)) continue
            val queue=if(label.kind==Kind.MOVIE) movies else shows
            if(queue.size<perKind) queue.add(index)
            else if(comparator.compare(index,queue.peek())>0) {queue.poll();queue.add(index)}
        }
        fun materialize(queue: PriorityQueue<Int>) = queue.toList().sortedWith(comparator.reversed()).map {channels[it]}
        return Selection(materialize(movies),materialize(shows))
    }

    /** Retains at most two small shelves even when the provider has hundreds of thousands of titles. */
    fun select(channels: Sequence<Channel>, perKind: Int = 30): Selection {
        if(perKind<=0) return Selection()
        data class Ranked(val channel: Channel,val score: Double)
        val comparator=compareBy<Ranked> {it.score}.thenByDescending {it.channel.name.lowercase(Locale.ROOT)}.thenByDescending {it.channel.id}
        val movies=PriorityQueue(comparator); val shows=PriorityQueue(comparator)
        channels.forEach {c->
            if(isEligible(c)) {
                val queue=if(c.kind==Kind.MOVIE) movies else shows
                val ranked=Ranked(c,score(c))
                if(queue.size<perKind) queue.add(ranked)
                else if(comparator.compare(ranked,queue.peek())>0) {queue.poll();queue.add(ranked)}
            }
        }
        fun sorted(queue: PriorityQueue<Ranked>) = queue.toList().sortedWith(comparator.reversed()).map {it.channel}
        return Selection(sorted(movies),sorted(shows))
    }

    fun title(c: Channel): String = c.name
        .replace(Regex("^(?:\\[[A-Za-z]{2,3}\\]|[A-Za-z]{2,3}\\s*[|:])\\s*"),"")
        .replace(Regex("\\s*[-–]\\s*(?:19|20)\\d{2}\\s*$"),"").trim()

    /** Search still covers the full library, but unclassified artwork never lands on Home. */
    fun homeCard(c: Channel): Channel = c.copy(name=title(c),logo=if(isEligible(c)) c.logo else "",backdrop=if(isEligible(c)) c.backdrop else "")
}
