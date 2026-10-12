package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationsTest {
    private val family=Channel("safe","p","1","Family feature","",Kind.MOVIE,group="[EN] FAMILY",rating="8.1",language="en",country="US",certification="PG")
    @Test fun reviewRatingAloneNeverMakesAnUnknownFilmFamilySuitable() {
        assertFalse(HomeRecommendations.isEligible(family.copy(group="[EN] NEW RELEASES",certification="",rating="9.9")))
        assertFalse(HomeRecommendations.isEligible(family.copy(group="[PL] POLISH MOVIES",language="pl")))
        assertFalse(HomeRecommendations.isEligible(family.copy(certification="R")))
        assertFalse(HomeRecommendations.isEligible(family.copy(certification="TV-MA")))
        assertFalse(HomeRecommendations.isEligible(family.copy(genre="Horror")))
        assertTrue(HomeRecommendations.isEligible(family))
    }
    @Test fun movieAndSeriesShelvesMixAndRankUsingAvailableMetadata() {
        val popular=family.copy(id="popular",popularity=100.0)
        val show=family.copy(id="show",kind=Kind.SERIES,certification="TV-Y")
        val picked=HomeRecommendations.select(sequenceOf(family,show,popular,family.copy(id="adult",certification="R")))
        assertEquals(listOf("popular","show","safe"),picked.mixed.map {it.id})
    }
    @Test fun unknownSearchResultsNeverShowUnclassifiedArtworkOnHome() {
        val unknown=family.copy(certification="R",logo="https://example.test/adult.jpg",backdrop="https://example.test/bg.jpg")
        assertEquals("",HomeRecommendations.homeCard(unknown).logo)
        assertEquals("",HomeRecommendations.homeCard(unknown).backdrop)
    }
    @Test fun homeShowcasesFamousEnglishKidsTitlesNewestFirst() {
        fun movie(id: String,name: String,group: String="EN | Movies",year: String="")=Channel(id,"p",id,name,"",Kind.MOVIE,group=group,year=year)
        val catalog=CompactCatalog.Builder().apply {
            add(movie("old","Frozen (2013)"))
            add(movie("generic","Frozen",group="Movies"))
            add(movie("pl","[PL] Toy Story 5",group="PL | Bajki"))
            add(movie("ts5","EN - Toy Story 5 (2026) 4K"))
            add(movie("ts5-hd","Toy Story 5 HD",group="Movies"))
            add(movie("min","4K-EN - Minions 3 - 2026"))
            add(movie("adult","Toy Story 5 XXX parody"))
            add(Channel("bluey","p","1","EN - Bluey","",Kind.SERIES,group="EN | Kids"))
            add(movie("unknown","Some Indie Drama (2026)"))
        }.build()
        val home=HomeRecommendations.featured(catalog)
        assertEquals(listOf("ts5","min"),home.popular.map {it.id})
        assertEquals(listOf("old"),home.classics.map {it.id})
        assertEquals(listOf("bluey"),home.shows.map {it.id})
        assertEquals("ts5",home.hero?.id)
        assertTrue(HomeRecommendations.featured(catalog) {it.id!="ts5"}.popular.none {it.id=="ts5"})
    }
    @Test fun titlesDropProviderDecorations() {
        fun t(name: String)=HomeRecommendations.title(Channel("x","p","1",name,""))
        assertEquals("Toy Story 5",t("EN - Toy Story 5 (2026) 4K"))
        assertEquals("Minions 3",t("4K-EN - Minions 3 - 2026"))
        assertEquals("Zootopia 2",t("|UK| Zootopia 2 FHD"))
        assertEquals("WALL-E",t("WALL-E"))
        assertEquals("Spider-Man: Into the Spider-Verse",t("[EN] Spider-Man: Into the Spider-Verse (2018)"))
        assertEquals("Up",t("Up"))
        assertEquals("Henry Danger",t("ENI Henry Danger"))
        assertEquals("Enigma",t("Enigma"))
    }
}
