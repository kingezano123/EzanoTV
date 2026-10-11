package tv.ezano.app

import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ln

/** Home is an opt-in showcase: missing suitability/language metadata never means safe. */
object HomeRecommendations {
    private val adult = Regex("(?i)(?:\\b(?:xxx|adult|porn|erotic|horror|uncensored|explicit|thriller|crime|mature|18\\+|16\\+)\\b|\\b(?:tv[ -]?ma|nc[ -]?17|pg[ -]?13|tv[ -]?14)\\b)")
    private val family = Regex("(?i)\\b(?:family|families|kids|children|children's|preschool|pre-school)\\b")
    private val familyHint = Regex("(?i)family|kids|children|animation|animated|cartoon|disney|pixar|dreamworks|illumination|nick|junior")
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

    // Provider decorations: "EN - ", "[EN] ", "|UK| ", "4K-EN - " prefixes and quality/year suffixes.
    private val titlePrefix = Regex("^(?:\\[[^\\]]{1,10}]|\\|[^|]{1,10}\\||\\([A-Za-z]{2,3}\\)|[A-Z0-9+]{1,6}(?:-[A-Z0-9+]{1,6})?\\s*[|:]|[A-Z0-9+]{1,6}(?:-[A-Z0-9+]{1,6})?\\s+[-–]\\s)\\s*")
    private val titleSuffix = Regex("(?i)\\s*(?:[\\[(](?:[^\\])]*\\b(?:4k|uhd|fhd|hd|hevc|multi|sub|subs|dub|dubbed|(?:19|20)\\d{2})\\b[^\\])]*)[\\])]|[-–|]?\\s*\\b(?:4k|uhd|fhd|hd|sd|hdr|hevc|h\\.?26[45]|x26[45]|1080p|720p|2160p|multi(?:-?subs?)?|dubbed)\\b|\\s[-–]\\s*(?:19|20)\\d{2}|\\s(?:19|20)\\d{2})\\s*$")
    fun title(c: Channel): String {
        var name=c.name.trim()
        repeat(3) {val next=titlePrefix.replaceFirst(name,"").trim();if(next.length>=2) name=next}
        repeat(4) {val next=titleSuffix.replace(name,"").trim();if(next.length>=2) name=next}
        return name
    }

    data class Shelves(val popular: List<Channel> = emptyList(), val shows: List<Channel> = emptyList(), val classics: List<Channel> = emptyList(), val more: List<Channel> = emptyList())

    /** Home showcase: the most famous kids & family titles that this provider actually has, newest blockbusters
     * first (see FeaturedTitles), then the provider's best-rated family titles. One pass over resident labels;
     * only the chosen titles are decoded. Locked, hidden, mature and non-English copies never appear.
     */
    fun featured(channels: List<Channel>, allowed: (CatalogLabel) -> Boolean = { true }): HomeSnapshot {
        val movies=HashMap<Int,Int>(); val shows=HashMap<Int,Int>()
        fun better(a: CatalogLabel, b: CatalogLabel) = compareValuesBy(a,b,{it.englishRank},{!it.familyEligible},{it.name.length}) < 0
        for(index in channels.indices) {
            val label=channels.catalogLabel(index)
            if((label.kind!=Kind.MOVIE && label.kind!=Kind.SERIES) || label.englishRank>2 || !allowed(label)) continue
            if(adult.containsMatchIn("${label.name} ${label.group} ${label.genre}")) continue
            val movie=label.kind==Kind.MOVIE
            val rank=if(movie) FeaturedTitles.movieRank(label.name,label.year) else FeaturedTitles.showRank(label.name,label.year)
            if(rank<0) continue
            // One-word titles ("Frozen", "Soul") need a year or a family label to be the famous film.
            if(label.year.isBlank() && FeaturedTitles.yearIn(label.name)==null && ' ' !in FeaturedTitles.normalize(label.name)
                && !label.familyEligible && !familyHint.containsMatchIn("${label.group} ${label.genre}")) continue
            val best=if(movie) movies else shows
            val previous=best[rank]
            if(previous==null || better(label,channels.catalogLabel(previous))) best[rank]=index
        }
        val rankedMovies=movies.entries.sortedBy {it.key}
        val (recent,older)=rankedMovies.partition {FeaturedTitles.movies[it.key].year>=2024}
        val popular=recent.take(30).map {channels[it.value]}
        val classics=older.take(40).map {channels[it.value]}
        val kidsShows=shows.entries.sortedBy {it.key}.take(30).map {channels[it.value]}
        val shown=(movies.values+shows.values).mapTo(HashSet()) {channels.catalogLabel(it).id}
        val more=selectIndexed(channels,24) {it.id !in shown && allowed(it)}.mixed
        // A provider without the famous titles still gets a full Home from its own family catalog.
        return if(popular.isEmpty() && classics.isEmpty()) HomeSnapshot(popular=more.take(24),shows=kidsShows)
        else HomeSnapshot(popular=popular,shows=kidsShows,classics=classics,more=more)
    }

    fun isFeatured(c: Channel): Boolean = when(c.kind) {
        Kind.MOVIE -> FeaturedTitles.movieRank(c.name,c.year)>=0
        Kind.SERIES -> FeaturedTitles.showRank(c.name,c.year)>=0
        else -> false
    }

    /** Search still covers the full library, but unclassified artwork never lands on Home. */
    fun homeCard(c: Channel): Channel = c.copy(name=title(c),logo=if(isEligible(c)) c.logo else "",backdrop=if(isEligible(c)) c.backdrop else "")
}
