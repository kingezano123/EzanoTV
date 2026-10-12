package tv.ezano.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopularTitlesTest {

    private fun idx(list: List<PopularTitles.Entry>, title: String, year: Int): Int =
        list.indexOfFirst { it.title == title && it.year == year }.also { assertTrue("$title ($year) missing", it >= 0) }

    private val movies = PopularTitles.movies
    private val shows = PopularTitles.shows

    // ---------------------------------------------------------------- messy provider names

    @Test
    fun movieRank_messyProviderNames() {
        val bnd = idx(movies, "Spider-Man: Brand New Day", 2026)
        assertEquals(bnd, PopularTitles.movieRank("EN - Spider-Man: Brand New Day (2026) 4K"))
        assertEquals(bnd, PopularTitles.movieRank("[EN] SPIDER-MAN: BRAND NEW DAY"))
        assertEquals(bnd, PopularTitles.movieRank("Spiderman Brand New Day 1080p HEVC"))
        assertEquals(bnd, PopularTitles.movieRank("US: Spider-Man - Brand New Day", "2026-07-31"))
        assertTrue("Brand New Day rank $bnd", bnd in 0..2)

        val superman = idx(movies, "Superman", 2025)
        assertEquals(superman, PopularTitles.movieRank("4K-EN - Superman - 2025"))
        assertEquals(superman, PopularTitles.movieRank("|US| Superman [MULTI-SUB]", "2025"))
        assertEquals(idx(movies, "The Mandalorian and Grogu", 2026), PopularTitles.movieRank("D+ | The Mandalorian & Grogu (2026)"))
        assertEquals(idx(movies, "Avengers: Endgame", 2019), PopularTitles.movieRank("NF - Avengers - Endgame (2019) [4K HDR]"))
        assertEquals(idx(movies, "The Dark Knight", 2008), PopularTitles.movieRank("Dark Knight"))
        assertEquals(idx(movies, "Thunderbolts*", 2025), PopularTitles.movieRank("EN - Thunderbolts (2025)"))
        assertEquals(idx(movies, "Mission: Impossible – The Final Reckoning", 2025),
            PopularTitles.movieRank("Mission Impossible - The Final Reckoning 2025"))
    }

    @Test
    fun showRank_messyProviderNames() {
        val flash = idx(shows, "The Flash", 2014)
        assertEquals(flash, PopularTitles.showRank("|US| The Flash"))
        assertEquals(flash, PopularTitles.showRank("EN - The Flash (2014) FHD"))
        assertEquals(flash, PopularTitles.showRank("Flash", "2023")) // latest-season year still matches
        assertEquals(idx(shows, "The Office", 2005), PopularTitles.showRank("[US] The Office (US)"))
        assertEquals(idx(shows, "Brooklyn Nine-Nine", 2013), PopularTitles.showRank("Brooklyn 99"))
        assertEquals(idx(shows, "Parks and Recreation", 2009), PopularTitles.showRank("Parks & Recreation"))
        assertEquals(idx(shows, "Grey's Anatomy", 2005), PopularTitles.showRank("EN| Greys Anatomy"))
        assertEquals(idx(shows, "9-1-1", 2018), PopularTitles.showRank("911"))
        assertEquals(idx(shows, "Marvel's Agents of S.H.I.E.L.D.", 2013), PopularTitles.showRank("Agents of SHIELD"))
        assertEquals(idx(shows, "X-Men '97", 2024), PopularTitles.showRank("X-Men 97"))
    }

    // ---------------------------------------------------------------- year disambiguation

    @Test
    fun movieRank_remakesDisambiguatedByYear() {
        assertEquals(idx(movies, "Superman", 1978), PopularTitles.movieRank("Superman (1978)"))
        assertEquals(idx(movies, "Superman", 2025), PopularTitles.movieRank("Superman (2025)"))
        assertEquals(idx(movies, "Superman", 2025), PopularTitles.movieRank("Superman")) // no year: best-ranked
        assertEquals(idx(movies, "Moana", 2016), PopularTitles.movieRank("Moana - 2016"))
        assertEquals(idx(movies, "Moana", 2026), PopularTitles.movieRank("Moana", "2026-07-10"))
        assertEquals(idx(movies, "The Lion King", 1994), PopularTitles.movieRank("The Lion King 1994"))
        assertEquals(idx(movies, "The Lion King", 2019), PopularTitles.movieRank("Lion King (2019)"))
        assertEquals(idx(movies, "Batman", 1989), PopularTitles.movieRank("Batman (1989)"))
        assertEquals(idx(movies, "The Batman", 2022), PopularTitles.movieRank("The Batman (2022)"))
        assertEquals(idx(movies, "The Naked Gun", 2025), PopularTitles.movieRank("The Naked Gun (2025)"))
        assertEquals(idx(movies, "The Naked Gun: From the Files of Police Squad!", 1988), PopularTitles.movieRank("The Naked Gun 1988"))
        // Year far from every curated release: not the curated film.
        assertEquals(-1, PopularTitles.movieRank("Superman (2006)"))
        assertEquals(-1, PopularTitles.movieRank("Dune (1984)"))
    }

    @Test
    fun showRank_rebootsDisambiguatedByYear() {
        assertEquals(idx(shows, "The Flash", 1990), PopularTitles.showRank("The Flash (1990)"))
        assertEquals(idx(shows, "The Flash", 2014), PopularTitles.showRank("The Flash", "2014"))
        assertEquals(idx(shows, "Matlock", 1986), PopularTitles.showRank("Matlock (1986)"))
        assertEquals(idx(shows, "Matlock", 2024), PopularTitles.showRank("Matlock (2024)"))
        assertTrue(PopularTitles.showRank("The Flash", "2014") < PopularTitles.showRank("The Flash", "1990"))
        // UK originals that predate the US version are not the curated show.
        assertEquals(-1, PopularTitles.showRank("The Office (2001)"))
        assertEquals(-1, PopularTitles.showRank("Ghosts (2019)"))
    }

    @Test
    fun everyEntryMatchesItself() {
        movies.forEachIndexed { i, e -> assertEquals("${e.title} (${e.year})", i, PopularTitles.movieRank(e.title, e.year.toString())) }
        shows.forEachIndexed { i, e -> assertEquals("${e.title} (${e.year})", i, PopularTitles.showRank(e.title, e.year.toString())) }
    }

    // ---------------------------------------------------------------- non-matches

    @Test
    fun nonMatches() {
        assertEquals(-1, PopularTitles.movieRank(""))
        assertEquals(-1, PopularTitles.movieRank("Some Random Catalog Title"))
        assertEquals(-1, PopularTitles.movieRank("Spider-Man: Brand New Day Behind the Scenes"))
        assertEquals(-1, PopularTitles.movieRank("Superman Returns (2006)"))
        assertEquals(-1, PopularTitles.movieRank("FR - La Haine (1995)"))
        assertEquals(-1, PopularTitles.showRank("Breaking Bad"))
        assertEquals(-1, PopularTitles.showRank("Flash Gordon"))
        assertNotEquals(PopularTitles.movieRank("Iron Man"), PopularTitles.movieRank("Iron Man 2"))
        assertNotEquals(PopularTitles.movieRank("Spider-Man"), PopularTitles.movieRank("Spider-Man 2"))
    }

    @Test
    fun ratingsAndReleaseRules_excludedTitlesAreNotCurated() {
        // R / TV-MA titles stay out of the front rows.
        for (r in listOf("Deadpool & Wolverine", "Joker", "Logan", "The Odyssey", "Oppenheimer", "Sinners", "Backrooms", "Obsession")) {
            assertEquals(r, -1, PopularTitles.movieRank(r))
        }
        for (r in listOf("Lanterns", "Peacemaker", "The Boys", "Squid Game", "Yellowstone", "Ted Lasso", "Reacher", "Stranger Things", "The Penguin")) {
            assertEquals(r, -1, PopularTitles.showRank(r))
        }
        // Not yet released on 2026-10-12.
        for (u in listOf("Avengers: Doomsday", "Dune: Part Three", "The Hunger Games: Sunrise on the Reaping")) {
            assertEquals(u, -1, PopularTitles.movieRank(u))
        }
        assertEquals(-1, PopularTitles.showRank("VisionQuest"))
        assertTrue(movies.all { it.year <= 2026 } && shows.all { it.year <= 2026 })
    }

    // ---------------------------------------------------------------- ordering & tags

    @Test
    fun ordering_newestHitsFirst() {
        val last2026 = movies.indexOfLast { it.year == 2026 }
        val first2025 = movies.indexOfFirst { it.year == 2025 }
        val last2025 = movies.indexOfLast { it.year == 2025 }
        val first2024 = movies.indexOfFirst { it.year == 2024 && it.title != "Paddington in Peru" }
        assertTrue(last2026 < first2025)
        assertTrue(last2025 < first2024)
        assertTrue(PopularTitles.movieRank("Spider-Man: Brand New Day") < PopularTitles.movieRank("Spider-Man", "2002"))
        assertTrue(PopularTitles.movieRank("Supergirl", "2026") < PopularTitles.movieRank("The Dark Knight"))
        assertTrue(PopularTitles.movieRank("Toy Story 5") < PopularTitles.movieRank("Toy Story"))
        assertTrue(PopularTitles.showRank("Wonder Man") < PopularTitles.showRank("Friends"))
        assertTrue(PopularTitles.showRank("Tracker") < PopularTitles.showRank("Seinfeld"))
        assertTrue(movies.size in 240..360)
        assertTrue(shows.size in 140..200)
        assertEquals(movies.size, movies.map { it.title to it.year }.toSet().size)
        assertEquals(shows.size, shows.map { it.title to it.year }.toSet().size)
    }

    @Test
    fun tags_driveShelves() {
        val m = movies.associateBy { it.title to it.year }
        val s = shows.associateBy { it.title to it.year }
        fun mt(title: String, year: Int) = m.getValue(title to year).tags
        fun st(title: String, year: Int) = s.getValue(title to year).tags
        assertTrue(mt("Spider-Man: Brand New Day", 2026).containsAll(setOf(PopularTitles.Tag.SUPERHERO, PopularTitles.Tag.MARVEL)))
        assertTrue(mt("Superman", 2025).containsAll(setOf(PopularTitles.Tag.SUPERHERO, PopularTitles.Tag.DC)))
        assertTrue(mt("The Batman", 2022).contains(PopularTitles.Tag.DC))
        assertTrue(PopularTitles.Tag.ACTION in mt("Top Gun: Maverick", 2022))
        assertTrue(PopularTitles.Tag.COMEDY in mt("Barbie", 2023))
        assertTrue(PopularTitles.Tag.SCIFI in mt("Dune: Part Two", 2024))
        assertTrue(PopularTitles.Tag.ANIMATION in mt("Toy Story 5", 2026))
        assertTrue(st("The Flash", 2014).containsAll(setOf(PopularTitles.Tag.SUPERHERO, PopularTitles.Tag.DC)))
        assertTrue(st("Loki", 2021).containsAll(setOf(PopularTitles.Tag.SUPERHERO, PopularTitles.Tag.MARVEL)))
        assertTrue(PopularTitles.Tag.COMEDY in st("The Office", 2005))
        assertTrue(PopularTitles.Tag.REALITY in st("Survivor", 2000))
        assertTrue((movies + shows).all { it.tags.isNotEmpty() })
        assertTrue((movies + shows).none { PopularTitles.Tag.MARVEL in it.tags && PopularTitles.Tag.DC in it.tags })
        assertTrue((movies + shows).filter { PopularTitles.Tag.MARVEL in it.tags || PopularTitles.Tag.DC in it.tags }
            .all { PopularTitles.Tag.SUPERHERO in it.tags })
        assertTrue(movies.count { PopularTitles.Tag.SUPERHERO in it.tags } >= 50)
        assertTrue(shows.count { PopularTitles.Tag.SUPERHERO in it.tags } >= 20)
    }

    // ---------------------------------------------------------------- performance

    @Test
    fun performance_80kTitles() {
        val prefixes = listOf("", "EN - ", "[EN] ", "|US| ", "4K-EN - ", "US: ", "NF - ", "VOD: ", "FR - ")
        val suffixes = listOf("", " (2019)", " 4K", " - 2021", " [MULTI-SUB] 1080p", " HEVC", " (Dubbed)")
        val curated = movies.map { it.title } + shows.map { it.title }
        val titles = ArrayList<String>(80_000)
        for (i in 0 until 80_000) {
            val base = if (i % 10 == 0) curated[i % curated.size] else "Some Random Catalog Title Number $i"
            titles.add(prefixes[i % prefixes.size] + base + suffixes[i % suffixes.size])
        }
        PopularTitles.movieRank("warm up")
        PopularTitles.showRank("warm up")
        val start = System.nanoTime()
        var hits = 0
        for ((i, t) in titles.withIndex()) {
            val r = if (i % 2 == 0) PopularTitles.movieRank(t, "") else PopularTitles.showRank(t, "")
            if (r >= 0) hits++
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PopularTitles: normalized+ranked ${titles.size} titles in $ms ms ($hits hits)")
        assertTrue("took $ms ms", ms < 3000)
        assertTrue(hits > 0)
    }
}
