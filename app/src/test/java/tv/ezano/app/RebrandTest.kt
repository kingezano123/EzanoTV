package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.StringWriter

class RebrandTest {
    @Test fun englishLabelsAndRegionsOverrideArtworkAndKeepDubbedTitles() {
        assertEquals(0,EnglishContent.rank("[ENI] Film","International","ja","JP"))
        assertEquals(0,EnglishContent.rank("Channel","World","en-GB","GB"))
        assertEquals(1,EnglishContent.rank("BBC One","UK Entertainment"))
        assertEquals(1,EnglishContent.rank("News","GB"))
        assertEquals(3,EnglishContent.rank("News","CA French","fr","CA"))
        assertEquals(2,EnglishContent.rank("Unknown","General"))
        assertEquals(1,EnglishContent.rank(Channel("a","p","1","UK News","",group="UK",logo="https://example.test/japanese-poster.jpg")))
    }
    @Test fun englishOrderingAndShelvesUseResidentLabelsAfterDiskRoundTrip() {
        val source=CompactCatalog.Builder().apply {
            add(Channel("foreign","p","1","Movie","",Kind.MOVIE,"French",language="fr",genre="Comedy"))
            add(Channel("uk","p","2","BBC Film","",Kind.MOVIE,"UK",year="2023"))
            add(Channel("en","p","3","ENI: Film","",Kind.MOVIE,"International",year="2025",rating="8.5",genre="Comedy"))
            add(Channel("unknown","p","4","Another film","",Kind.MOVIE,"General"))
        }.build()
        val text=StringWriter().also {LibraryStreams.write(Library(channels=source),it)}.toString()
        val restored=LibraryStreams.read(text.reader()).channels as CompactCatalog
        val sorted=restored.englishFirst()
        assertEquals(listOf("en","uk","unknown","foreign"),sorted.catalogLabels().map {it.id}.toList())
        assertEquals("en",BrowseShelves.build(sorted).first().channels.catalogLabel(0).id)
        assertEquals(0,restored.decodedPages.get())
    }
    @Test fun episodeHistoryAndProviderCaptionsSurviveBackup() {
        val ep=Channel("ep","p","1","S1 · E2 Test","https://example.test/ep.mp4",Kind.EPISODE,"Show",seriesId="series",subtitleTracks=listOf(SubtitleSource("https://example.test/en.vtt")))
        val s=Library(providers=listOf(Provider(id="p",name="Test",url="https://provider.test")),recent=listOf(ep),positions=mapOf("ep" to 42000L))
        val restored=Json.decode(Json.encode(s))
        assertEquals(ep,restored.recent.single())
        assertEquals(ep,WatchHistory.resume(restored).single())
        assertTrue(WatchHistory.resume(restored.copy(locked=setOf("series"))).isEmpty())
        assertTrue(WatchHistory.resume(restored.copy(providers=emptyList())).isEmpty())
        assertTrue(Json.decode(Json.encode(Library()).let {JSONObject(it).apply {getJSONObject("prefs").remove("autoSubtitles")}.toString()}).prefs.autoSubtitles)
    }
    @Test fun providerSubtitleParserAcceptsDirectFilesAndRejectsWebpages() {
        val tracks=ProviderSubtitles.parse(JSONObject("""{"info":{"subtitles":[{"src":"/caption.vtt","srclang":"en","label":"English"},{"url":"https://example.test/sub.srt?token=123","language":"eng"},{"url":"https://example.test/watch","language":"en"},{"url":"javascript:alert(1)"}]}}"""),"https://provider.test/")
        assertEquals(2,tracks.size);assertEquals("https://provider.test/caption.vtt",tracks.first().url)
        assertEquals("application/x-subrip",tracks.last().mime);assertEquals("en",tracks.last().language)
    }
    @Test fun automaticExternalLookupRequiresMatchingYearOrExactEpisode() {
        val film=Channel("film","p","1","EN: A Film","",Kind.MOVIE,year="2025")
        val match=SubtitleMatch(1,"Release","en",20,false,title="A Film",year=2025)
        assertTrue(SubtitleIdentity.matches(film,match));assertFalse(SubtitleIdentity.matches(film,match.copy(year=1999)))
        assertFalse(SubtitleIdentity.matches(film.copy(year=""),match));assertFalse(SubtitleIdentity.matches(film,match.copy(language="fr")))
        val ep=film.copy(kind=Kind.EPISODE,name="S2 · E7 Episode title",group="A Show")
        val episode=match.copy(parentTitle="A Show",season=2,episode=7)
        assertTrue(SubtitleIdentity.matches(ep,episode));assertFalse(SubtitleIdentity.matches(ep,episode.copy(episode=8)))
    }
}
