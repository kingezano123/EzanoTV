package tv.ezano.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ImportRegressionTest {
    @Test fun pastedApiAndPlaylistAddressesNormalizeWithoutLosingPortOrSubpath() {
        assertEquals("http://example.test:8080",Sources.serverUrl(" http://example.test:8080/player_api.php?username=a&password=b "))
        assertEquals("https://example.test/panel",Sources.serverUrl("https://example.test/panel/get.php?type=m3u_plus"))
    }
    @Test fun duplicateAndNullIdsCannotReachLazyLayoutKeys() {MockWebServer().use {server->
        server.enqueue(MockResponse().setBody("{\"user_info\":{\"auth\":\"1\",\"status\":\"Active\"}}"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setChunkedBody("[{\"stream_id\":1,\"name\":\"One\"},{\"stream_id\":1,\"name\":\"Duplicate\"},{\"stream_id\":null},null,17,{\"stream_id\":2,\"epg_channel_id\":null}]",13))
        repeat(4){server.enqueue(MockResponse().setBody("[]"))};server.start()
        val result=Sources.importCatalog(Provider(name="Test",url=server.url("/").toString()))
        assertEquals(2,result.channels.size);assertEquals("One",result.channels.first().name);assertEquals("",result.channels.last().epgId);assertTrue(result.warnings.isNotEmpty())
    }}
    @Test fun unavailableVodDoesNotBlockLiveAndSeries() {MockWebServer().use {server->
        server.enqueue(MockResponse().setBody("{\"user_info\":{\"auth\":1}}"))
        server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("[{\"stream_id\":1}]"))
        server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("[{\"series_id\":2}]"));server.start()
        val result=Sources.importCatalog(Provider(name="Test",url=server.url("/").toString()))
        assertEquals(listOf(Kind.LIVE,Kind.SERIES),result.channels.map {it.kind});assertEquals(setOf(Kind.MOVIE),result.failedKinds)
    }}
    @Test fun invalidLoginMakesNoCatalogRequests() {MockWebServer().use {server->
        server.enqueue(MockResponse().setBody("{\"user_info\":{\"auth\":0,\"status\":\"Expired\"}}"));server.start()
        val failure=runCatching{Sources.catalog(Provider(name="Test",url=server.url("/").toString()))}.exceptionOrNull()
        assertNotNull(failure);assertEquals(1,server.requestCount);assertTrue(Sources.friendlyError(failure as Exception).contains("login"))
    }}
    @Test fun duplicateGuideEntriesAndMalformedDatesAreSkipped() {
        val channel=Channel("c","p","1","One","http://example.test/1",epgId="one ")
        val entry="<programme channel=\"one\" start=\"20260921100000 +0000\" stop=\"20260921110000 +0000\"><title>Program</title><desc>Full guide details</desc></programme>"
        val xml="<tv>$entry$entry<programme channel=\"one\" start=\"bad\" stop=\"bad\"><title>Invalid</title></programme></tv>"
        val result=Sources.parseXmltv(xml.reader(),listOf(channel),0)
        assertEquals(1,result.size);assertEquals("Full guide details",result.single().description)
        assertTrue(Sources.parseXmltv(xml.reader(),listOf(channel),0,1).isEmpty())
    }
    @Test fun largeLibraryStreamsRoundTripWithoutJsonArrayGraph() {
        val channels=(1..12000).map {Channel("p:$it","p","$it","Channel $it","http://example.test/$it",description="Detail ".repeat(40))}
        val programs=(1..12000).map {Program("epg:$it","p:$it","Show $it",1000,2000,"Synopsis")}
        val library=Library(providers=listOf(Provider(id="p",name="My provider",url="https://example.test",password="secret")),channels=channels,programs=programs,favorites=setOf("p:1"),prefs=Prefs(panelOpacity=.8f))
        val writer=StringWriter();LibraryStreams.write(library,writer)
        assertEquals(library,LibraryStreams.read(writer.toString().reader()))
    }
}
