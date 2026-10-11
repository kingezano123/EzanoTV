package tv.ezano.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturedTitlesTest {

    private fun idx(list: List<FeaturedTitles.Entry>, title: String, year: Int): Int =
        list.indexOfFirst { it.title == title && it.year == year }.also { assertTrue("$title ($year) missing", it >= 0) }

    // ---------------------------------------------------------------- normalize

    @Test
    fun normalize_messyProviderTitles() {
        assertEquals("toy story 5", FeaturedTitles.normalize("EN - Toy Story 5 (2026) 4K"))
        assertEquals("toy story 5", FeaturedTitles.normalize("[EN] TOY STORY 5"))
        assertEquals("zootropolis 2", FeaturedTitles.normalize("|UK| Zootropolis 2 FHD"))
        assertEquals("minions 3", FeaturedTitles.normalize("4K-EN - Minions 3 - 2026"))
        assertEquals("toy story 5", FeaturedTitles.normalize("EN| Toy Story 5"))
        assertEquals("toy story 5", FeaturedTitles.normalize("(EN) Toy Story 5"))
        assertEquals("toy story 5", FeaturedTitles.normalize("EN: Toy Story 5 [MULTI-SUB]"))
        assertEquals("toy story 5", FeaturedTitles.normalize("D+ | Toy Story 5 1080p HEVC"))
        assertEquals("toy story 5", FeaturedTitles.normalize("VOD: US: Toy Story 5 (Dubbed)"))
        assertEquals("toy story 5", FeaturedTitles.normalize("NF - MULTI - Toy Story 5 WEB-DL"))
        assertEquals("up", FeaturedTitles.normalize("[EN] Up 2009 4K"))
        assertEquals("moana", FeaturedTitles.normalize("Moana - 2016"))
    }

    @Test
    fun normalize_keepsRealTitleWords() {
        assertEquals("up", FeaturedTitles.normalize("Up"))
        assertEquals("up", FeaturedTitles.normalize("UP - 2009"))
        assertEquals("elio", FeaturedTitles.normalize("Elio"))
        assertEquals("cars 2", FeaturedTitles.normalize("Cars 2"))
        assertEquals("sing 2", FeaturedTitles.normalize("Sing 2 (2021)"))
        assertEquals("ice age the meltdown", FeaturedTitles.normalize("Ice Age: The Meltdown"))
        assertEquals("the lion king", FeaturedTitles.normalize("The Lion King"))
        assertEquals("paw patrol the movie", FeaturedTitles.normalize("PAW Patrol: The Movie"))
    }

    @Test
    fun normalize_wallE_variantsAgree() {
        val key = FeaturedTitles.normalize("WALL-E")
        assertEquals("wall e", key)
        assertEquals(key, FeaturedTitles.normalize("Wall-E"))
        assertEquals(key, FeaturedTitles.normalize("WALL·E"))
        assertEquals(key, FeaturedTitles.normalize("WALL-E - 2008"))
    }

    @Test
    fun normalize_punctuationDiacriticsAmpersandApostrophes() {
        assertEquals(FeaturedTitles.normalize("Gabby's Dollhouse"), FeaturedTitles.normalize("Gabbys Dollhouse"))
        assertEquals(FeaturedTitles.normalize("Gabby’s Dollhouse"), FeaturedTitles.normalize("Gabbys Dollhouse"))
        assertEquals("lilo and stitch", FeaturedTitles.normalize("Lilo & Stitch"))
        assertEquals("pokemon", FeaturedTitles.normalize("Pokémon"))
        assertEquals("monsters inc", FeaturedTitles.normalize("Monsters, Inc."))
        assertEquals("frozen", FeaturedTitles.normalize("Frozen Director's Cut IMAX HDR10"))
        assertEquals("", FeaturedTitles.normalize(""))
    }

    // ---------------------------------------------------------------- yearIn

    @Test
    fun yearIn_extractsYears() {
        assertEquals(2026, FeaturedTitles.yearIn("Toy Story 5 (2026)"))
        assertEquals(2016, FeaturedTitles.yearIn("Moana - 2016"))
        assertEquals(2009, FeaturedTitles.yearIn("[EN] Up 2009 4K"))
        assertEquals(2026, FeaturedTitles.yearIn("4K-EN - Minions 3 - 2026"))
        assertNull(FeaturedTitles.yearIn("Toy Story 5"))
        assertNull(FeaturedTitles.yearIn("Up 1080p 2160p x264"))
        assertNull(FeaturedTitles.yearIn("Despicable Me 4"))
    }

    // ---------------------------------------------------------------- movieRank

    @Test
    fun movieRank_headlineTitlesRankFirst() {
        val ts5 = FeaturedTitles.movieRank("EN - Toy Story 5 (2026) 4K")
        val minions3 = FeaturedTitles.movieRank("4K-EN - Minions 3 - 2026")
        assertTrue("Toy Story 5 rank $ts5", ts5 in 0..4)
        assertTrue("Minions 3 rank $minions3", minions3 in 0..4)
        assertEquals(minions3, FeaturedTitles.movieRank("Minions & Monsters", "2026-07-01"))
        assertTrue(FeaturedTitles.movieRank("The Super Mario Galaxy Movie") in 0..4)
        assertEquals(FeaturedTitles.movieRank("Zootopia 2"), FeaturedTitles.movieRank("|UK| Zootropolis 2 FHD"))
        assertTrue(FeaturedTitles.movieRank("Zootopia 2") >= 0)
    }

    @Test
    fun movieRank_classicsRankBelowNewReleases() {
        val frozen = FeaturedTitles.movieRank("Frozen (2013)")
        val ts5 = FeaturedTitles.movieRank("Toy Story 5")
        assertTrue(frozen > 0)
        assertTrue(frozen > ts5)
        assertTrue(FeaturedTitles.movieRank("Toy Story") > FeaturedTitles.movieRank("Toy Story 5"))
        assertTrue(FeaturedTitles.movieRank("Up", "2009") > FeaturedTitles.movieRank("Hoppers", "2026"))
    }

    @Test
    fun movieRank_ordering_newestFirst() {
        val m = FeaturedTitles.movies
        val first2025 = m.indexOfFirst { it.year == 2025 }
        val last2026 = m.indexOfLast { it.year == 2026 }
        assertTrue(last2026 < first2025)
        assertTrue(m.size in 120..200)
        assertTrue(FeaturedTitles.shows.size in 50..80)
    }

    @Test
    fun movieRank_remakesDisambiguatedByYear() {
        val m = FeaturedTitles.movies
        assertEquals(idx(m, "The Lion King", 1994), FeaturedTitles.movieRank("The Lion King (1994)"))
        assertEquals(idx(m, "The Lion King", 2019), FeaturedTitles.movieRank("The Lion King", "2019-07-19"))
        assertEquals(idx(m, "Aladdin", 1992), FeaturedTitles.movieRank("EN - Aladdin 1992"))
        assertEquals(idx(m, "Aladdin", 2019), FeaturedTitles.movieRank("Aladdin (2019) 4K"))
        assertEquals(idx(m, "Moana", 2016), FeaturedTitles.movieRank("Moana - 2016"))
        assertEquals(idx(m, "Moana", 2026), FeaturedTitles.movieRank("Moana", "2026"))
        assertEquals(idx(m, "How to Train Your Dragon", 2010), FeaturedTitles.movieRank("How to Train Your Dragon (2010)"))
        assertEquals(idx(m, "How to Train Your Dragon", 2025), FeaturedTitles.movieRank("How to Train Your Dragon (2025)"))
        assertEquals(idx(m, "Lilo & Stitch", 2002), FeaturedTitles.movieRank("Lilo and Stitch", "2002"))
        assertEquals(idx(m, "Lilo & Stitch", 2025), FeaturedTitles.movieRank("[EN] Lilo & Stitch (2025)"))
        // No year known: highest-ranked entry with that key.
        assertEquals(idx(m, "Lilo & Stitch", 2025), FeaturedTitles.movieRank("Lilo & Stitch"))
        // Year known but far from every entry: no match.
        assertEquals(-1, FeaturedTitles.movieRank("The Lion King (1957)"))
    }

    @Test
    fun movieRank_aliasesAndUkTitles() {
        val m = FeaturedTitles.movies
        val hp1 = idx(m, "Harry Potter and the Philosopher's Stone", 2001)
        assertEquals(hp1, FeaturedTitles.movieRank("Harry Potter and the Sorcerer's Stone (2001)"))
        assertEquals(hp1, FeaturedTitles.movieRank("Harry Potter And The Philosophers Stone"))
        assertEquals(idx(m, "Zootopia", 2016), FeaturedTitles.movieRank("Zootropolis"))
        assertEquals(idx(m, "Minions: The Rise of Gru", 2022), FeaturedTitles.movieRank("Minions 2"))
        assertEquals(idx(m, "KPop Demon Hunters", 2025), FeaturedTitles.movieRank("K-Pop Demon Hunters"))
        assertEquals(idx(m, "Spider-Man: Into the Spider-Verse", 2018), FeaturedTitles.movieRank("Spiderman Into The Spiderverse"))
        assertEquals(idx(m, "Puss in Boots: The Last Wish", 2022), FeaturedTitles.movieRank("Puss in Boots - The Last Wish"))
        assertEquals(idx(m, "The Super Mario Bros. Movie", 2023), FeaturedTitles.movieRank("Super Mario Bros. Movie"))
        assertEquals(idx(m, "WALL-E", 2008), FeaturedTitles.movieRank("WALL·E"))
    }

    @Test
    fun movieRank_everyEntryMatchesItself() {
        FeaturedTitles.movies.forEachIndexed { i, e ->
            assertEquals("${e.title} (${e.year})", i, FeaturedTitles.movieRank(e.title, e.year.toString()))
        }
        FeaturedTitles.shows.forEachIndexed { i, e ->
            assertEquals("${e.title} (${e.year})", i, FeaturedTitles.showRank(e.title, e.year.toString()))
        }
    }

    @Test
    fun movieRank_nonMatches() {
        assertEquals(-1, FeaturedTitles.movieRank("Up Close"))
        assertEquals(-1, FeaturedTitles.movieRank("Pixar Up Close"))
        assertEquals(-1, FeaturedTitles.movieRank("Up in the Air"))
        assertEquals(-1, FeaturedTitles.movieRank("Fifty Shades of Grey (2015)"))
        assertEquals(-1, FeaturedTitles.movieRank("FR - La Haine (1995)"))
        assertEquals(-1, FeaturedTitles.movieRank(""))
        val cars = FeaturedTitles.movieRank("Cars")
        val cars2 = FeaturedTitles.movieRank("Cars 2")
        assertTrue(cars >= 0 && cars2 >= 0)
        assertNotEquals(cars, cars2)
        assertNotEquals(FeaturedTitles.movieRank("Toy Story"), FeaturedTitles.movieRank("Toy Story 2"))
    }

    // ---------------------------------------------------------------- showRank

    @Test
    fun showRank_popularShows() {
        assertEquals(0, FeaturedTitles.showRank("Bluey"))
        assertTrue(FeaturedTitles.showRank("PAW Patrol") in 0..4)
        assertEquals(FeaturedTitles.showRank("Peppa Pig"), FeaturedTitles.showRank("EN - Peppa Pig"))
        assertTrue(FeaturedTitles.showRank("EN - Peppa Pig") in 0..4)
        assertTrue(FeaturedTitles.showRank("Bluey (2018)", "2024") == 0) // season year after start still matches
        assertEquals(FeaturedTitles.showRank("Gabby's Dollhouse"), FeaturedTitles.showRank("|EN| Gabbys Dollhouse HD"))
        assertEquals(FeaturedTitles.showRank("Ninjago"), FeaturedTitles.showRank("LEGO Ninjago: Masters of Spinjitzu"))
        assertEquals(-1, FeaturedTitles.showRank("Breaking Bad"))
        assertEquals(-1, FeaturedTitles.showRank("Bluey", "1990"))
    }

    @Test
    fun rank_dispatchesByKind() {
        assertEquals(FeaturedTitles.movieRank("Toy Story 5"), FeaturedTitles.rank(Kind.MOVIE, "Toy Story 5"))
        assertEquals(0, FeaturedTitles.rank(Kind.SERIES, "Bluey"))
        assertEquals(-1, FeaturedTitles.rank(Kind.LIVE, "Bluey"))
    }

    // ---------------------------------------------------------------- performance

    @Test
    fun performance_80kTitles() {
        val prefixes = listOf("", "EN - ", "[EN] ", "|UK| ", "4K-EN - ", "US: ", "NF - ", "VOD: ", "FR - ")
        val suffixes = listOf("", " (2019)", " 4K", " - 2021", " [MULTI-SUB] 1080p", " HEVC", " (Dubbed)")
        val featured = FeaturedTitles.movies.map { it.title } + FeaturedTitles.shows.map { it.title }
        val titles = ArrayList<String>(80_000)
        for (i in 0 until 80_000) {
            val base = if (i % 10 == 0) featured[i % featured.size] else "Some Random Catalog Title Number $i"
            titles.add(prefixes[i % prefixes.size] + base + suffixes[i % suffixes.size])
        }
        FeaturedTitles.movieRank("warm up")
        FeaturedTitles.showRank("warm up")
        val start = System.nanoTime()
        var hits = 0
        for ((i, t) in titles.withIndex()) {
            val r = if (i % 2 == 0) FeaturedTitles.movieRank(t, "") else FeaturedTitles.showRank(t, "")
            if (r >= 0) hits++
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("FeaturedTitles: ranked ${titles.size} titles in $ms ms ($hits hits)")
        assertTrue("took $ms ms", ms < 3000)
        assertTrue(hits > 0)
    }
}
