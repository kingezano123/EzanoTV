package tv.ezano.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SubtitleTest {
    private val srt="1\n00:00:01,000 --> 00:00:04,000\nHello world\n"
    @Test fun acceptsSrtAndWebVttAndUnicodeBom() {
        assertEquals("application/x-subrip",SubtitleFiles.mime(srt))
        assertEquals("text/vtt",SubtitleFiles.mime("WEBVTT\n\n00:01.000 --> 00:02.000\nHi"))
        assertEquals(srt,SubtitleFiles.read(("\uFEFF"+srt).byteInputStream()))
        assertEquals(srt,SubtitleFiles.read(("\uFEFF"+srt).toByteArray(Charsets.UTF_16LE).inputStream()))
    }
    @Test fun rejectsHtmlZipAndOversizeDownloads() {
        assertTrue(runCatching {SubtitleFiles.mime("<html>Download page</html>")}.isFailure)
        assertTrue(runCatching {SubtitleFiles.read("PK\u0000\u0000".byteInputStream())}.isFailure)
        assertTrue(runCatching {SubtitleFiles.read(ByteArray(SubtitleFiles.MAX_BYTES+1).inputStream())}.isFailure)
    }
    @Test fun subtitleSearchUsesSeparateKeyAndEncodesTitle() {MockWebServer().use {server->
        server.enqueue(MockResponse().setBody("""{"data":[{"attributes":{"release":"Film release","language":"en","download_count":100,"hearing_impaired":true,"files":[{"file_id":10}]}}]}"""));server.start()
        val results=OpenSubtitles(server.url("/api/v1").toString().trimEnd('/')).search("A & B","en","test-key",2,7)
        assertEquals(10,results.single().fileId);assertTrue(results.single().captions)
        val req=server.takeRequest();assertEquals("A & B",req.requestUrl!!.queryParameter("query"));assertEquals("2",req.requestUrl!!.queryParameter("season_number"));assertEquals("7",req.requestUrl!!.queryParameter("episode_number"));assertEquals("test-key",req.getHeader("Api-Key"));assertNull(req.getHeader("Authorization"))
    }}
    @Test fun subtitleQuotaIsExplainedAndInsecureDownloadsRejected() {MockWebServer().use {server->
        server.enqueue(MockResponse().setResponseCode(406));server.enqueue(MockResponse().setBody("{\"link\":\"http://example.test/sub.srt\"}"));server.start()
        val api=OpenSubtitles(server.url("/api/v1").toString().trimEnd('/'));val match=SubtitleMatch(1,"Film","en",1,false)
        assertTrue(runCatching {api.downloadUrl(match,"key")}.exceptionOrNull()!!.message!!.contains("limit"))
        assertTrue(runCatching {api.downloadUrl(match,"key")}.isFailure)
    }}
    @Test fun subtitleDownloadContainsNoApiOrProviderCredentials() {MockWebServer().use {server->
        server.enqueue(MockResponse().setBody(srt));server.start();assertEquals(srt,SubtitleFiles.download(server.url("/captions.srt").toString()));val req=server.takeRequest();assertNull(req.getHeader("Api-Key"));assertNull(req.getHeader("Authorization"))
    }}
}
