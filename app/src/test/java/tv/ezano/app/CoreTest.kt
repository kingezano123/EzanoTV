package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.*
import java.util.TimeZone

class CoreTest {
    private val provider=Provider(id="p1",name="Test",type="m3u",url="https://example.test/list.m3u")
    private val channel=Channel("p1:1","p1","1","Test channel","https://example.test/live.ts",epgId="one")
    @Test fun m3uPreservesQuotedCommasAndHeaders() {
        val list=Sources.parseM3u("""#EXTM3U
            |#EXTINF:-1 tvg-id="one" tvg-logo="https://example.test/logo.png" group-title="News, World" catchup="append" catchup-days="7" catchup-source="?utc={utc}",Channel, One
            |#EXTVLCOPT:http-user-agent=ExamplePlayer
            |https://example.test/live.ts
            |#EXTINF:-1 group-title="Films",Film
            |https://example.test/movie.mp4?token=x
        """.trimMargin(),provider)
        assertEquals(2,list.size);assertEquals("Channel, One",list[0].name);assertEquals("News, World",list[0].group);assertEquals("ExamplePlayer",list[0].headers["User-Agent"]);assertEquals(7,list[0].catchupDays);assertEquals(Kind.MOVIE,list[1].kind);assertTrue(list[1].headers.isEmpty())
    }
    @Test fun m3uStableIdsAndDeduplication() {val raw="#EXTM3U\n#EXTINF:-1,A\nhttps://example.test/a.ts\n#EXTINF:-1,A\nhttps://example.test/a.ts";val one=Sources.parseM3u(raw,provider);assertEquals(1,one.size);assertEquals(one[0].id,Sources.parseM3u(raw,provider)[0].id)}
    @Test(expected=IllegalArgumentException::class) fun rejectsNonPlaylist() {Sources.parseM3u("<html>Error</html>",provider)}
    @Test fun skipsInvalidSchemes() {assertTrue(Sources.parseM3u("#EXTM3U\n#EXTINF:-1,Unsafe\nfile:///tmp/test",provider).isEmpty())}
    @Test fun xmlTimezoneIsRespected() {assertEquals(Instant.parse("2026-09-21T18:00:00Z").toEpochMilli(),Sources.xmlTime("20260921140000 -0400"));assertEquals(Instant.parse("2026-09-21T14:00:00Z").toEpochMilli(),Sources.xmlTime("20260921140000 +0000"))}
    @Test fun xmlMatchesProviderScopedChannelsAndKeepsDescriptions() {val xml="""<tv><programme channel="one" start="20260921140000 +0000" stop="20260921150000 +0000"><title>Live &amp; more</title><desc>Full description</desc></programme><programme channel="other" start="20260921140000 +0000" stop="20260921150000 +0000"><title>Wrong</title></programme></tv>""";val items=Sources.parseXmltv(xml.reader(),listOf(channel),0);assertEquals(1,items.size);assertEquals("p1:1",items[0].channelId);assertEquals("Live & more",items[0].title);assertEquals("Full description",items[0].description)}
    @Test fun xmlRejectsExpiredEntries() {val xml="""<tv><programme channel="one" start="20200921140000 +0000" stop="20200921150000 +0000"><title>Old</title></programme></tv>""";assertTrue(Sources.parseXmltv(xml.reader(),listOf(channel),System.currentTimeMillis()).isEmpty())}
    @Test fun backupRoundTrip() {val raw=Json.encode(Library(providers=listOf(provider.copy(password="secret")),channels=listOf(channel),favorites=setOf(channel.id)));val encrypted=Crypto.backup(raw,"long passphrase");assertFalse(String(encrypted).contains("secret"));assertEquals(raw,Crypto.restore(encrypted,"long passphrase"))}
    @Test fun wrongBackupPasswordIsRejected() {val encrypted=Crypto.backup("{}","correct passphrase");assertTrue(runCatching{Crypto.restore(encrypted,"incorrect passphrase")}.isFailure)}
    @Test fun tamperedBackupIsRejected() {val encrypted=Crypto.backup("{}","correct passphrase");encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte();assertTrue(runCatching{Crypto.restore(encrypted,"correct passphrase")}.isFailure)}
    @Test fun pinHashIsSaltedAndValidated() {val salt=Crypto.salt();val hash=Crypto.b64(Crypto.derive("1234",salt));assertTrue(Crypto.verify("1234",Crypto.b64(salt),hash));assertFalse(Crypto.verify("4321",Crypto.b64(salt),hash));assertFalse(hash==Crypto.b64(Crypto.derive("1234",Crypto.salt())))}
    @Test fun libraryRoundTripsAllCollections() {val s=Library(providers=listOf(provider),channels=listOf(channel),favorites=setOf(channel.id),hidden=setOf("hidden"),hiddenGroups=setOf("Group"),locked=setOf(channel.id),groups=mapOf("Sports" to listOf(channel.id)),order=listOf(channel.id),positions=mapOf(channel.id to 5000L),recordings=listOf(Recording(channel=channel,title="Rec",start=1000,end=2000,repeat="weekly")),prefs=Prefs(lockedSections=setOf("Movies")));assertEquals(s,Json.decode(Json.encode(s)))}
    @Test fun backupRejectsFutureSchema() {assertTrue(runCatching{Json.decode("{\"version\":999}")}.isFailure)}
    @Test fun dailyRecurrencePreservesLocalTimeAcrossDst() {val old=TimeZone.getDefault();try {TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));val start=ZonedDateTime.of(2026,10,31,20,0,0,0,ZoneId.systemDefault()).toInstant().toEpochMilli();val r=Recording(channel=channel,title="Daily",start=start,end=start+3600000,repeat="daily");val next=nextOccurrence(r,start)!!;assertEquals(20,Instant.ofEpochMilli(next.start).atZone(ZoneId.systemDefault()).hour);assertEquals(25*3600000L,next.start-start);assertEquals(3600000L,next.end-next.start)}finally{TimeZone.setDefault(old)}}
    @Test fun recurringScheduleSkipsMissedOccurrences() {val r=Recording(channel=channel,title="Daily",start=100000,end=200000,repeat="daily");assertTrue(nextOccurrence(r,System.currentTimeMillis())!!.start>System.currentTimeMillis());assertNull(nextOccurrence(r.copy(repeat="once")))}
    @Test fun catchupTemplateReplacesEpochs() {val now=System.currentTimeMillis();val p=Program("p",channel.id,"Earlier",now-3600000,now-1800000);val c=channel.copy(catchupDays=3,catchupMode="append",catchupSource="?start={utc}&duration={duration}");val result=Sources.catchup(provider,c,p);assertEquals(Kind.EPISODE,result.kind);assertTrue(result.url.contains("duration=1800"));assertFalse(result.url.contains("{utc}"))}
    @Test fun catchupRejectsUnavailableArchive() {val p=Program("p",channel.id,"Old",0,3600000);assertTrue(runCatching{Sources.catchup(provider,channel.copy(catchupDays=3),p)}.isFailure)}
    @Test fun xtreamLoadsAllCatalogKindsAndEncodesCredentials() {MockWebServer().use {server->server.enqueue(MockResponse().setBody("{\"user_info\":{\"auth\":1,\"status\":\"Active\"}}"));server.enqueue(MockResponse().setBody("[{\"category_id\":\"1\",\"category_name\":\"News\"}]"));server.enqueue(MockResponse().setBody("[{\"stream_id\":12,\"name\":\"One\",\"category_id\":\"1\",\"tv_archive\":1,\"tv_archive_duration\":7}]"));server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("[{\"stream_id\":13,\"name\":\"Film\",\"container_extension\":\"mkv\"}]"));server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("[{\"series_id\":14,\"name\":\"Show\"}]"));server.start();val p=Provider(id="xt",name="Xtream",url=server.url("/").toString(),user="u +/&",password="p?=&");val items=Sources.catalog(p);assertEquals(3,items.size);assertEquals("News",items[0].group);assertEquals(7,items[0].catchupDays);assertTrue(items[1].url.endsWith("13.mkv"));assertEquals(Kind.SERIES,items[2].kind);val request=server.takeRequest();assertEquals("u +/&",request.requestUrl!!.queryParameter("username"));assertEquals("p?=&",request.requestUrl!!.queryParameter("password"))}}
    @Test fun xtreamRejectsInvalidLogin() {MockWebServer().use {server->server.enqueue(MockResponse().setBody("{\"user_info\":{\"auth\":0}}"));server.start();assertTrue(runCatching{Sources.catalog(Provider(name="Bad",url=server.url("/").toString()))}.isFailure)}}
    @Test fun chunkedNetworkReadWorksWithoutContentLength() {MockWebServer().use {server->server.enqueue(MockResponse().setChunkedBody("#EXTM3U\n#EXTINF:-1,A\nhttps://example.test/a.ts",7));server.start();assertTrue(Sources.read(server.url("/").toString()).startsWith("#EXTM3U"))}}
}
