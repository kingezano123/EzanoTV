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
}
