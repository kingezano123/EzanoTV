package tv.ezano.app

import java.text.Normalizer

/** Widely known English kids & family titles, most featured first. Matched against provider titles by name (+ year when known). */
object FeaturedTitles {

    data class Entry(val title: String, val year: Int, val aliases: List<String> = emptyList())

    // ---------------------------------------------------------------------------------------------
    // Precompiled patterns and lookup tables (declared first: used by the lazily built indexes).
    // ---------------------------------------------------------------------------------------------

    /** Leading bracketed provider/language tag: "[EN] ", "(EN) ", "|EN| ", "[EN 4K] - ". */
    private val BRACKET_PREFIX =
        Regex("""^\s*[\[({|]\s*([A-Za-z0-9+]{1,5}(?:[ ._/-][A-Za-z0-9+]{1,5})?)\s*[\])}|]\s*(?:[-|:]\s*)?""")

    /** Leading tag followed by a separator: "EN - ", "EN| ", "US: ", "4K-EN - ", "D+ | ", "VOD: ". */
    private val SEP_PREFIX =
        Regex("""^\s*([A-Za-z0-9+]{1,5}(?:-[A-Za-z0-9+]{1,5})*)(?:\s*[|:]|\s+-|-\s)\s*""")

    /** Any bracketed chunk (innermost). */
    private val BRACKET_CHUNK = Regex("""[\[({]([^\[\](){}]*)[\])}]""")

    /** Combining marks left after NFD decomposition. */
    private val MARKS = Regex("""\p{Mn}+""")

    /** A standalone 4-digit year 1930..2099. */
    private val YEAR = Regex("""(?<![0-9])(19[3-9][0-9]|20[0-9][0-9])(?![0-9])""")

    /** Quality / format / release tags dropped anywhere as whole words. */
    private val QUALITY: Set<String> = hashSetOf(
        "4k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "hevc", "x264", "x265", "h264", "h265",
        "1080p", "720p", "2160p", "480p", "multi", "multisub", "sub", "subs", "subbed", "dub", "dubbed",
        "vostfr", "ita", "cam", "ts", "hdcam", "web", "webrip", "webdl", "bluray", "remux", "imax",
        "extended", "hdtv", "hdrip", "dvdrip", "bdrip", "brrip", "10bit", "atmos",
    )

    /** Language / provider codes that are recognised as prefixes even when written in lowercase. */
    private val KNOWN_TAGS: Set<String> = hashSetOf(
        "en", "eng", "uk", "us", "usa", "gb", "ca", "au", "ie", "fr", "de", "es", "it", "nl", "pt", "ar",
        "tr", "pl", "vod", "nf", "d+", "dsnp", "amz", "atv", "hbo", "max", "multi", "kids", "4k", "hd",
        "fhd", "uhd", "sd", "new", "top",
    )

    private fun e(title: String, year: Int, vararg aliases: String) = Entry(title, year, aliases.toList())

    // ---------------------------------------------------------------------------------------------
    // Movies: 2026 releases first, then 2025, 2024, then all-time classics by fame. All rated G/PG.
    // ---------------------------------------------------------------------------------------------

    val movies: List<Entry> = listOf(
        // ---- 2026 (released by October 2026) ----
        e("Toy Story 5", 2026),
        e("Minions & Monsters", 2026, "Minions 3", "Minions 3: Minions & Monsters", "Despicable Me: Minions & Monsters"),
        e("The Super Mario Galaxy Movie", 2026, "Super Mario Galaxy", "The Super Mario Bros. Movie 2", "Super Mario Bros. Movie 2"),
        e("Hoppers", 2026),
        e("Moana", 2026, "Moana Live Action", "Moana (Live Action)"),
        e("GOAT", 2026, "G.O.A.T."),
        e("PAW Patrol: The Dino Movie", 2026, "PAW Patrol Dino Movie"),
        e("Swapped", 2026),
        e("Coyote vs. Acme", 2026, "Coyote versus Acme"),
        e("Forgotten Island", 2026),
        // ---- 2025 ----
        e("Zootopia 2", 2025, "Zootropolis 2"),
        e("Lilo & Stitch", 2025, "Lilo & Stitch Live Action"),
        e("KPop Demon Hunters", 2025, "K-Pop Demon Hunters", "K Pop Demon Hunters"),
        e("A Minecraft Movie", 2025, "Minecraft Movie", "Minecraft: The Movie", "The Minecraft Movie"),
        e("How to Train Your Dragon", 2025, "How to Train Your Dragon Live Action"),
        e("Wicked: For Good", 2025, "Wicked For Good", "Wicked: Part II", "Wicked Part Two", "Wicked 2"),
        e("The SpongeBob Movie: Search for SquarePants", 2025, "Search for SquarePants",
            "The SpongeBob SquarePants Movie: Search for SquarePants"),
        e("The Bad Guys 2", 2025),
        e("Elio", 2025),
        e("Dog Man", 2025),
        e("Paddington in Peru", 2024, "Paddington 3"),
        e("Smurfs", 2025, "The Smurfs", "The Smurfs Movie", "Smurfs Movie"),
        e("Gabby's Dollhouse: The Movie", 2025, "Gabby's Dollhouse Movie"),
        e("Freakier Friday", 2025),
        e("The Day the Earth Blew Up: A Looney Tunes Movie", 2025, "The Day the Earth Blew Up"),
        e("Snow White", 2025),
        e("The Twits", 2025, "Roald Dahl's The Twits"),
        e("Wallace & Gromit: Vengeance Most Fowl", 2024),
        // ---- 2024 ----
        e("Inside Out 2", 2024),
        e("Moana 2", 2024),
        e("Despicable Me 4", 2024),
        e("Wicked", 2024, "Wicked: Part I", "Wicked Part One"),
        e("The Wild Robot", 2024),
        e("Sonic the Hedgehog 3", 2024, "Sonic 3"),
        e("Mufasa: The Lion King", 2024, "Mufasa"),
        e("Kung Fu Panda 4", 2024),
        e("The Garfield Movie", 2024, "Garfield"),
        e("Transformers One", 2024),
        e("IF", 2024),
        // ---- All-time family classics, by fame ----
        e("Frozen", 2013),
        e("Toy Story", 1995),
        e("Shrek", 2001),
        e("Despicable Me", 2010),
        e("Minions", 2015),
        e("The Lion King", 1994),
        e("Finding Nemo", 2003),
        e("Frozen II", 2019, "Frozen 2"),
        e("Moana", 2016),
        e("Encanto", 2021),
        e("Coco", 2017),
        e("Zootopia", 2016, "Zootropolis"),
        e("Inside Out", 2015),
        e("The Super Mario Bros. Movie", 2023, "The Super Mario Brothers Movie", "Super Mario Bros"),
        e("Minions: The Rise of Gru", 2022, "Minions 2", "Minions Rise of Gru"),
        e("Despicable Me 2", 2013),
        e("Despicable Me 3", 2017),
        e("Toy Story 2", 1999),
        e("Toy Story 3", 2010),
        e("Toy Story 4", 2019),
        e("Shrek 2", 2004),
        e("Harry Potter and the Philosopher's Stone", 2001, "Harry Potter and the Sorcerer's Stone", "Harry Potter 1"),
        e("Harry Potter and the Chamber of Secrets", 2002, "Harry Potter 2"),
        e("Harry Potter and the Prisoner of Azkaban", 2004, "Harry Potter 3"),
        e("Home Alone", 1990),
        e("Home Alone 2: Lost in New York", 1992, "Home Alone 2"),
        e("The Incredibles", 2004, "Incredibles"),
        e("Incredibles 2", 2018, "The Incredibles 2"),
        e("Monsters, Inc.", 2001, "Monsters Incorporated"),
        e("Up", 2009),
        e("Cars", 2006),
        e("Finding Dory", 2016),
        e("Spider-Man: Into the Spider-Verse", 2018, "Spiderman: Into the Spiderverse", "Into the Spider-Verse"),
        e("Spider-Man: Across the Spider-Verse", 2023, "Spiderman: Across the Spiderverse", "Across the Spider-Verse"),
        e("Paddington", 2014),
        e("Paddington 2", 2017),
        e("Wonka", 2023),
        e("Puss in Boots: The Last Wish", 2022, "Puss in Boots 2", "Puss in Boots The Last Wish"),
        e("Kung Fu Panda", 2008),
        e("How to Train Your Dragon", 2010),
        e("Ratatouille", 2007),
        e("WALL-E", 2008, "Walle"),
        e("Tangled", 2010),
        e("Big Hero 6", 2014),
        e("The Lion King", 2019),
        e("Aladdin", 1992),
        e("Aladdin", 2019),
        e("Beauty and the Beast", 1991),
        e("Beauty and the Beast", 2017),
        e("The Little Mermaid", 1989),
        e("The Little Mermaid", 2023),
        e("Lilo & Stitch", 2002),
        e("Sing", 2016),
        e("Sing 2", 2021),
        e("The Secret Life of Pets", 2016),
        e("The Secret Life of Pets 2", 2019),
        e("Ice Age", 2002),
        e("Madagascar", 2005),
        e("Trolls", 2016),
        e("Jumanji", 1995),
        e("Matilda", 1996),
        e("Charlie and the Chocolate Factory", 2005),
        e("Willy Wonka & the Chocolate Factory", 1971),
        e("Soul", 2020),
        e("Luca", 2021),
        e("Turning Red", 2022),
        e("Elemental", 2023),
        e("Wish", 2023),
        e("Migration", 2023),
        e("Kung Fu Panda 2", 2011),
        e("Kung Fu Panda 3", 2016),
        e("How to Train Your Dragon 2", 2014),
        e("How to Train Your Dragon: The Hidden World", 2019, "How to Train Your Dragon 3"),
        e("Shrek the Third", 2007, "Shrek 3"),
        e("Shrek Forever After", 2010, "Shrek 4"),
        e("Cars 2", 2011),
        e("Brave", 2012),
        e("A Bug's Life", 1998),
        e("Wreck-It Ralph", 2012),
        e("Ralph Breaks the Internet", 2018, "Wreck-It Ralph 2", "Ralph Breaks the Internet: Wreck-It Ralph 2"),
        e("Mulan", 1998),
        e("The Jungle Book", 1967),
        e("The Jungle Book", 2016),
        e("Cinderella", 1950),
        e("Snow White and the Seven Dwarfs", 1937, "Snow White"),
        e("101 Dalmatians", 1961, "One Hundred and One Dalmatians"),
        e("Peter Pan", 1953),
        e("Hercules", 1997),
        e("The Princess and the Frog", 2009),
        e("Raya and the Last Dragon", 2021),
        e("Mary Poppins", 1964),
        e("Onward", 2020),
        e("Ice Age: The Meltdown", 2006, "Ice Age 2", "Ice Age 2: The Meltdown"),
        e("Madagascar: Escape 2 Africa", 2008, "Madagascar 2"),
        e("Trolls World Tour", 2020, "Trolls 2"),
        e("The Boss Baby", 2017),
        e("The Bad Guys", 2022),
        e("The Croods", 2013),
        e("Hotel Transylvania", 2012),
        e("The Lego Movie", 2014),
        e("The Lego Batman Movie", 2017),
        e("Sonic the Hedgehog", 2020),
        e("Sonic the Hedgehog 2", 2022, "Sonic 2"),
        e("The Grinch", 2018, "Dr. Seuss' The Grinch"),
        e("Rio", 2011),
        e("Teenage Mutant Ninja Turtles: Mutant Mayhem", 2023, "TMNT: Mutant Mayhem"),
        e("Chicken Run", 2000),
        e("Klaus", 2019),
        e("Coraline", 2009),
        e("Fantastic Mr. Fox", 2009),
        e("Night at the Museum", 2006),
        e("Elf", 2003),
        e("The Polar Express", 2004),
        e("E.T. the Extra-Terrestrial", 1982, "E.T.", "ET the Extra-Terrestrial"),
        e("The Chronicles of Narnia: The Lion, the Witch and the Wardrobe", 2005,
            "The Lion, the Witch and the Wardrobe", "Narnia: The Lion, the Witch and the Wardrobe"),
        e("Roald Dahl's Matilda the Musical", 2022, "Matilda the Musical", "Matilda"),
        e("PAW Patrol: The Movie", 2021),
        e("PAW Patrol: The Mighty Movie", 2023),
        e("The SpongeBob SquarePants Movie", 2004),
        e("Pokémon Detective Pikachu", 2019, "Detective Pikachu"),
        e("The Nightmare Before Christmas", 1993, "Tim Burton's The Nightmare Before Christmas"),
    )

    // ---------------------------------------------------------------------------------------------
    // Shows: ordered by current popularity with kids. Year = first-air year. All TV-Y..TV-PG.
    // ---------------------------------------------------------------------------------------------

    val shows: List<Entry> = listOf(
        e("Bluey", 2018),
        e("Peppa Pig", 2004),
        e("PAW Patrol", 2013),
        e("Gabby's Dollhouse", 2021),
        e("SpongeBob SquarePants", 1999, "SpongeBob"),
        e("CoComelon", 2019, "CoComelon Lane"),
        e("Ms. Rachel", 2019, "Songs for Littles"),
        e("Spidey and His Amazing Friends", 2021, "Marvel's Spidey and His Amazing Friends"),
        e("Miraculous: Tales of Ladybug & Cat Noir", 2015, "Miraculous", "Miraculous Ladybug"),
        e("Pokémon", 1997, "Pokémon Horizons", "Pokémon Horizons: The Series", "Pokémon Journeys", "Pokémon the Series"),
        e("Hey Duggee", 2014),
        e("Blippi", 2014, "Blippi Wonders"),
        e("The Loud House", 2016),
        e("Teen Titans Go!", 2013),
        e("Phineas and Ferb", 2007),
        e("The Amazing World of Gumball", 2011, "The Wonderfully Weird World of Gumball"),
        e("Avatar: The Last Airbender", 2005),
        e("Gravity Falls", 2012),
        e("PJ Masks", 2015),
        e("Mickey Mouse Clubhouse", 2006, "Mickey Mouse Clubhouse+"),
        e("Mickey Mouse Funhouse", 2021),
        e("Octonauts", 2010),
        e("Ninjago", 2011, "LEGO Ninjago", "LEGO Ninjago: Masters of Spinjitzu", "Ninjago: Masters of Spinjitzu",
            "Ninjago: Dragons Rising", "LEGO Ninjago: Dragons Rising"),
        e("Sonic Prime", 2022),
        e("Kung Fu Panda: The Dragon Knight", 2022),
        e("DreamWorks Dragons", 2012, "Dragons", "Dragons: Riders of Berk", "Dragons: Defenders of Berk",
            "Dragons: Race to the Edge"),
        e("Rubble & Crew", 2023),
        e("Ben 10", 2005),
        e("SuperKitties", 2023),
        e("Bing", 2014),
        e("Ben and Holly's Little Kingdom", 2009),
        e("Dino Ranch", 2021),
        e("Baby Shark's Big Show!", 2020),
        e("Numberblocks", 2017),
        e("Masha and the Bear", 2009),
        e("Pocoyo", 2005),
        e("Thomas & Friends", 1984, "Thomas the Tank Engine & Friends", "Thomas the Tank Engine",
            "Thomas & Friends: All Engines Go"),
        e("Fireman Sam", 1987),
        e("Bubble Guppies", 2011),
        e("Blue's Clues & You!", 2019, "Blue's Clues"),
        e("Dora the Explorer", 2000, "Dora"),
        e("Daniel Tiger's Neighborhood", 2012),
        e("Super Wings", 2015),
        e("Doc McStuffins", 2012),
        e("Puppy Dog Pals", 2017),
        e("Sofia the First", 2013),
        e("Vampirina", 2017),
        e("Big City Greens", 2018),
        e("The Owl House", 2020),
        e("Amphibia", 2019),
        e("DuckTales", 2017),
        e("Kim Possible", 2002),
        e("Hilda", 2018),
        e("The Fairly OddParents", 2001),
        e("Rugrats", 1991),
        e("Danny Phantom", 2004),
        e("Scooby-Doo, Where Are You!", 1969),
        e("Tom and Jerry", 1940, "The Tom and Jerry Show", "Tom and Jerry Tales"),
        e("Teenage Mutant Ninja Turtles", 1987),
        e("Shaun the Sheep", 2007),
        e("Curious George", 2006),
        e("Wild Kratts", 2011),
        e("Sesame Street", 1969),
        e("Arthur", 1996),
        e("Alphablocks", 2010),
        e("Barbie Dreamhouse Adventures", 2018),
        e("My Little Pony: Friendship Is Magic", 2010),
        e("Jurassic World: Camp Cretaceous", 2020),
        e("Star Wars: Young Jedi Adventures", 2023),
        e("Ask the StoryBots", 2016, "StoryBots", "StoryBots: Answer Time"),
    )

    // ---------------------------------------------------------------------------------------------
    // Indexes: normalized key -> ranks, built once, O(1) lookups (see TitleMatcher).
    // ---------------------------------------------------------------------------------------------

    private val movieMatcher: TitleMatcher by lazy {
        TitleMatcher(movies.map { TitleMatcher.Candidate(listOf(it.title) + it.aliases, it.year) }, TitleMatcher.YearRule.NEAR)
    }
    private val showMatcher: TitleMatcher by lazy {
        TitleMatcher(shows.map { TitleMatcher.Candidate(listOf(it.title) + it.aliases, it.year) }, TitleMatcher.YearRule.NOT_BEFORE)
    }

    // ---------------------------------------------------------------------------------------------
    // Public matching API
    // ---------------------------------------------------------------------------------------------

    /** Normalized comparison key. */
    fun normalize(name: String): String {
        if (name.isEmpty()) return ""
        return core(stripPrefixes(name))
    }

    /** 4-digit year (1930..2099) found in a provider title, e.g. "Toy Story 5 (2026)", "Moana - 2016", "[EN] Up 2009 4K", else null. */
    fun yearIn(name: String): Int? {
        var lead = 0
        while (lead < name.length && name[lead].isWhitespace()) lead++
        var found: Int? = null
        var m = YEAR.find(name)
        while (m != null) {
            // A number at the very start is part of the title ("2012", "1917"), not a release year.
            if (m.range.first != lead) found = m.value.toInt()
            m = m.next()
        }
        return found
    }

    /** 0-based rank of the best matching movie entry, or -1. `year` is provider metadata (may be blank or like "2026-06-19"). */
    fun movieRank(name: String, year: String = ""): Int = movieMatcher.rank(name, year)

    /**
     * 0-based rank of the best matching show entry, or -1.
     * Series metadata years are often the latest season / upload year rather than the first-air year,
     * so a show only fails the year check when the provider year is before the show started.
     */
    fun showRank(name: String, year: String = ""): Int = showMatcher.rank(name, year)

    /** Convenience dispatch by catalog [Kind]: movies and series are ranked, everything else is -1. */
    fun rank(kind: Kind, name: String, year: String = ""): Int = when (kind) {
        Kind.MOVIE -> movieRank(name, year)
        Kind.SERIES -> showRank(name, year)
        else -> -1
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** Year from provider metadata (blank or like "2026-06-19") when present, else the year found in the title. */
    internal fun providerYear(name: String, year: String): Int? {
        if (year.isNotBlank()) {
            YEAR.find(year)?.let { return it.value.toInt() }
        }
        return yearIn(name)
    }

    /** Repeatedly removes leading provider / language tags, never consuming the real title. */
    private fun stripPrefixes(input: String): String {
        var s = input
        for (pass in 0 until 4) {
            var i = 0
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return s
            val c0 = s[i]
            var match: MatchResult? = null
            if (c0 == '[' || c0 == '(' || c0 == '{' || c0 == '|') {
                match = BRACKET_PREFIX.find(s)
            }
            if (match == null && hasEarlySeparator(s, i)) {
                match = SEP_PREFIX.find(s)?.takeIf { isPrefixToken(it.groupValues[1]) }
            }
            if (match == null) return s
            val rest = s.substring(match.range.last + 1)
            // Never strip the whole title (e.g. "UP - 2009", "WALL-E - 4K").
            if (!hasLetter(core(rest))) return s
            s = rest
        }
        return s
    }

    /** Cheap pre-check: a separator within the first few characters, so most titles skip the regex. */
    private fun hasEarlySeparator(s: String, from: Int): Boolean {
        val end = minOf(s.length, from + 16)
        for (j in from until end) {
            val c = s[j]
            if (c == '-' || c == '|' || c == ':') return true
        }
        return false
    }

    /** An unbracketed prefix token must look like a tag: upper-case ("EN", "4K-EN", "D+") or a known code. */
    private fun isPrefixToken(token: String): Boolean {
        for (part in token.split('-')) {
            if (part.isEmpty()) continue
            if (part.any { it.isLowerCase() } && part.lowercase() !in KNOWN_TAGS) return false
        }
        return true
    }

    private fun hasLetter(s: String): Boolean {
        for (c in s) if (c.isLetter()) return true
        return false
    }

    private fun isAscii(s: String): Boolean {
        for (c in s) if (c.code > 127) return false
        return true
    }

    private fun isYearToken(t: String): Boolean {
        if (t.length != 4) return false
        for (c in t) if (c !in '0'..'9') return false
        val y = t.toInt()
        return y in 1930..2099
    }

    private fun isApostrophe(c: Char): Boolean =
        c == '\'' || c == '’' || c == '‘' || c == '`' || c == '´' || c == 'ʼ'

    /** Splits on anything that is not a letter or digit, dropping apostrophes and mapping '&' to "and". */
    private fun words(s: String): ArrayList<String> {
        val out = ArrayList<String>(8)
        val cur = StringBuilder(16)
        for (c in s) {
            when {
                c.isLetterOrDigit() -> cur.append(c)
                isApostrophe(c) -> Unit
                c == '&' -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                    out.add("and")
                }
                else -> if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** True for bracketed chunks such as "(2026)", "[4K HDR]", "(Dubbed)", "[MULTI-SUB]", "(EN)". */
    private fun isJunkChunk(content: String): Boolean {
        if (YEAR.containsMatchIn(content)) return true
        for (w in words(content)) {
            if (w in QUALITY || w in KNOWN_TAGS) return true
        }
        return false
    }

    /** Everything after prefix stripping: case, diacritics, brackets, punctuation, tags, trailing year. */
    private fun core(raw: String): String {
        var s = raw.lowercase()
        if (!isAscii(s)) s = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        if (s.indexOf('(') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0) {
            s = BRACKET_CHUNK.replace(s) { m ->
                val inner = m.groupValues[1]
                if (isJunkChunk(inner)) " " else " $inner "
            }
        }
        val tokens = words(s)
        val kept = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = if (i + 1 < tokens.size) tokens[i + 1] else null
            if ((t == "directors" && next == "cut") || (t == "web" && next == "dl") ||
                (t == "blu" && next == "ray") || (t == "h" && (next == "264" || next == "265"))
            ) {
                i += 2
                continue
            }
            if (t !in QUALITY) kept.add(t)
            i++
        }
        while (kept.size > 1 && isYearToken(kept[kept.size - 1])) kept.removeAt(kept.size - 1)
        if (kept.isEmpty()) return ""
        if (kept.size == 1) return kept[0]
        val sb = StringBuilder(s.length)
        for ((idx, t) in kept.withIndex()) {
            if (idx > 0) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }
}

/**
 * Ranked title index shared by [FeaturedTitles] and [PopularTitles]: rank = position in the curated list.
 * Every name (title + aliases) is keyed by [FeaturedTitles.normalize], plus a variant without a leading "the",
 * so a lookup is one normalization and one hash probe. Entries sharing a key (remakes, reboots) are tried
 * best rank first and disambiguated by year according to [yearRule].
 */
internal class TitleMatcher(entries: List<Candidate>, private val yearRule: YearRule) {

    class Candidate(val names: List<String>, val year: Int)

    enum class YearRule {
        /** Movies: the provider year must be within one year of the release year. */
        NEAR,

        /**
         * Series: metadata years are often the latest season / upload year rather than the first-air year,
         * so a show only fails the year check when the provider year is before the show started.
         */
        NOT_BEFORE;

        fun accepts(providerYear: Int, entryYear: Int): Boolean = when (this) {
            NEAR -> kotlin.math.abs(providerYear - entryYear) <= 1
            NOT_BEFORE -> providerYear >= entryYear - 1
        }
    }

    private val years = IntArray(entries.size) { entries[it].year }
    private val index: HashMap<String, IntArray>

    init {
        val tmp = HashMap<String, MutableList<Int>>(entries.size * 4)
        entries.forEachIndexed { rank, entry ->
            val keys = LinkedHashSet<String>()
            for (n in entry.names) {
                val k = FeaturedTitles.normalize(n)
                if (k.isEmpty()) continue
                keys.add(k)
                // Providers frequently omit a leading article; index that variant too.
                if (k.startsWith("the ") && k.length > 4) keys.add(k.substring(4))
            }
            for (k in keys) tmp.getOrPut(k) { ArrayList(2) }.add(rank)
        }
        index = HashMap(tmp.size * 2)
        for ((k, v) in tmp) index[k] = v.sorted().toIntArray()
    }

    /** 0-based rank of the best matching entry, or -1. `year` is provider metadata (may be blank or like "2026-06-19"). */
    fun rank(name: String, year: String = ""): Int {
        val ranks = index[FeaturedTitles.normalize(name)] ?: return -1
        val py = FeaturedTitles.providerYear(name, year)
        for (r in ranks) {
            if (py == null || yearRule.accepts(py, years[r])) return r
        }
        return -1
    }
}
