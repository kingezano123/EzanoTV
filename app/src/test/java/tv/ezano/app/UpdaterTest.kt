package tv.ezano.app

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdaterTest {
    @get:Rule val temp = TemporaryFolder()

    private val sha = "a".repeat(64)
    private fun feed(change: JSONObject.() -> Unit = {}) = JSONObject()
        .put("versionCode", 15).put("versionName", "0.5.2")
        .put("apk", "https://github.com/kingezano123/EzanoTV/releases/download/v0.5.2/Ezano-TV-0.5.2.apk")
        .put("sha256", sha).put("size", 31_000_000).put("notes", "Fixes and improvements.").apply(change).toString()
    private fun info(code: Int = 15) = UpdateInfo(code, "0.5.2", "https://example.com/Ezano-TV.apk", sha, 1000, "")
    private fun rejects(json: String, allowHttp: Boolean = false) {
        try { Updater.parse(json, allowHttp); fail("accepted $json") } catch (_: IllegalArgumentException) {}
    }

    @Test fun parsesValidFeed() {
        val info = Updater.parse(feed { put("sha256", "AB".repeat(32)) }, allowHttp = false)
        assertEquals(15, info.versionCode)
        assertEquals("0.5.2", info.versionName)
        assertEquals("https://github.com/kingezano123/EzanoTV/releases/download/v0.5.2/Ezano-TV-0.5.2.apk", info.apk)
        assertEquals("ab".repeat(32), info.sha256)
        assertEquals(31_000_000L, info.size)
        assertEquals("Fixes and improvements.", info.notes)
    }

    @Test fun notesAreOptional() {
        assertEquals("", Updater.parse(feed { remove("notes") }, false).notes)
        assertEquals("", Updater.parse(feed { put("notes", JSONObject.NULL) }, false).notes)
        assertEquals(2000, Updater.parse(feed { put("notes", "x".repeat(5000)) }, false).notes.length)
    }

    @Test fun rejectsMalformedJsonAndMissingFields() {
        rejects("")
        rejects("not json")
        rejects("[]")
        rejects("{}")
        listOf("versionCode", "versionName", "apk", "sha256", "size").forEach { rejects(feed { remove(it) }) }
    }

    @Test fun rejectsBadVersionFields() {
        rejects(feed { put("versionCode", "15") })
        rejects(feed { put("versionCode", 0) })
        rejects(feed { put("versionCode", -3) })
        rejects(feed { put("versionCode", 15.5) })
        rejects(feed { put("versionCode", 3_000_000_000L) })
        rejects(feed { put("versionName", "") })
        // The name becomes part of a cache file name: no path separators.
        rejects(feed { put("versionName", "../../evil") })
        rejects(feed { put("versionName", "0.5/2") })
    }

    @Test fun rejectsNonHttpsApk() {
        rejects(feed { put("apk", "http://github.com/Ezano-TV.apk") })
        rejects(feed { put("apk", "ftp://github.com/Ezano-TV.apk") })
        rejects(feed { put("apk", "file:///sdcard/Ezano-TV.apk") })
        rejects(feed { put("apk", "https:///no-host.apk") })
        rejects(feed { put("apk", "https://user:pw@github.com/Ezano-TV.apk") })
        rejects(feed { put("apk", "not a url") })
        // Debug builds accept http for local test fixtures.
        assertEquals("http://10.0.2.2:8000/Ezano-TV.apk", Updater.parse(feed { put("apk", "http://10.0.2.2:8000/Ezano-TV.apk") }, allowHttp = true).apk)
        rejects(feed { put("apk", "ftp://github.com/Ezano-TV.apk") }, allowHttp = true)
    }

    @Test fun rejectsBadShaAndSize() {
        rejects(feed { put("sha256", "a".repeat(63)) })
        rejects(feed { put("sha256", "a".repeat(65)) })
        rejects(feed { put("sha256", "g".repeat(64)) })
        rejects(feed { put("sha256", 12345) })
        rejects(feed { put("size", 0) })
        rejects(feed { put("size", Updater.MAX_APK + 1) })
        rejects(feed { put("size", "100") })
    }

    @Test fun jsonRoundTrip() {
        val info = Updater.parse(feed(), false)
        assertEquals(info, Updater.parse(Updater.toJson(info), false))
    }

    @Test fun comparesVersionCodes() {
        assertTrue(Updater.isNewer(info(15), 14))
        assertFalse(Updater.isNewer(info(14), 14))
        assertFalse(Updater.isNewer(info(13), 14))
    }

    @Test fun offersOnlyNewerVersions() {
        val now = 1_000_000_000L
        assertTrue(Updater.shouldOffer(info(15), 14, now, 0, 0))
        assertFalse(Updater.shouldOffer(info(14), 14, now, 0, 0))
        assertFalse(Updater.shouldOffer(info(13), 14, now, 0, 0))
        assertFalse(Updater.shouldOffer(info(13), 14, now, 0, 0, force = true))
    }

    @Test fun snoozeHidesForADay() {
        val now = 1_000_000_000L
        val until = now + Updater.SNOOZE
        assertFalse(Updater.shouldOffer(info(15), 14, now, until, 0, snoozedCode = 15))
        assertFalse(Updater.shouldOffer(info(15), 14, until - 1, until, 0, snoozedCode = 15))
        assertTrue(Updater.shouldOffer(info(15), 14, until, until, 0, snoozedCode = 15))
        // A release newer than the snoozed one is offered straight away.
        assertTrue(Updater.shouldOffer(info(16), 14, now, until, 0, snoozedCode = 15))
        // Check now ignores the snooze.
        assertTrue(Updater.shouldOffer(info(15), 14, now, until, 0, snoozedCode = 15, force = true))
        // A clock that jumped backwards can't extend the snooze beyond a day.
        assertTrue(Updater.shouldOffer(info(15), 14, now - 10 * Updater.SNOOZE, until, 0, snoozedCode = 15))
    }

    @Test fun skipHidesThatVersionOnly() {
        val now = 1_000_000_000L
        assertFalse(Updater.shouldOffer(info(15), 14, now, 0, skippedCode = 15))
        assertTrue(Updater.shouldOffer(info(16), 14, now, 0, skippedCode = 15))
        assertTrue(Updater.shouldOffer(info(15), 14, now, 0, skippedCode = 15, force = true))
    }

    @Test fun checksAtMostEverySixHours() {
        val now = 10 * Updater.CHECK_INTERVAL
        assertTrue(Updater.isDue(now, 0, force = false))
        assertFalse(Updater.isDue(now, now - 1000, force = false))
        assertTrue(Updater.isDue(now, now - 1000, force = true))
        assertTrue(Updater.isDue(now, now - 1000, force = false, feedChanged = true))
        assertTrue(Updater.isDue(now, now - Updater.CHECK_INTERVAL, force = false))
        assertTrue(Updater.isDue(now, now + 5000, force = false))
    }

    @Test fun comparesVersionNames() {
        assertTrue(Updater.compareVersionNames("0.10.0", "0.9.3") > 0)
        assertTrue(Updater.compareVersionNames("0.5.1", "0.5.2") < 0)
        assertEquals(0, Updater.compareVersionNames("0.5.1", "0.5.1"))
        assertEquals(0, Updater.compareVersionNames("0.5", "0.5.0"))
        assertTrue(Updater.compareVersionNames("1.0.0", "0.99.99") > 0)
        assertEquals("Ezano-TV-0.5.2.apk", Updater.apkName(info()))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }

    @Test fun downloadVerifiesAndReportsProgress() = withServer { server ->
        val apk = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/objects/apk").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val update = UpdateInfo(15, "0.5.2", server.url("/download/Ezano-TV-0.5.2.apk").toString(), sha256(apk), apk.size.toLong(), "")
        val dir = File(temp.root, "updates")
        val seen = mutableListOf<Float>()
        val file = runBlocking { Updater.downloadTo(OkHttpClient(), update, dir, seen::add) }
        assertEquals(File(dir, "Ezano-TV-0.5.2.apk"), file)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(listOf("Ezano-TV-0.5.2.apk"), dir.list()!!.toList())
        // A verified copy is reused without downloading again.
        val again = runBlocking { Updater.downloadTo(OkHttpClient(), update, dir) {} }
        assertEquals(file, again)
        assertEquals(2, server.requestCount)
    }

    @Test fun downloadRejectsChecksumMismatch() = withServer { server ->
        val apk = ByteArray(50_000) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val update = UpdateInfo(15, "0.5.2", server.url("/apk").toString(), "0".repeat(64), apk.size.toLong(), "")
        val dir = File(temp.root, "updates")
        try { runBlocking { Updater.downloadTo(OkHttpClient(), update, dir) {} }; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("verification")) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun downloadRejectsWrongSize() = withServer { server ->
        val apk = ByteArray(50_000) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val update = UpdateInfo(15, "0.5.2", server.url("/apk").toString(), sha256(apk), 40_000, "")
        val dir = File(temp.root, "updates")
        try { runBlocking { Updater.downloadTo(OkHttpClient(), update, dir) {} }; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("size")) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun downloadReportsHttpErrors() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        val update = UpdateInfo(15, "0.5.2", server.url("/apk").toString(), sha, 1000, "")
        val dir = File(temp.root, "updates")
        try { runBlocking { Updater.downloadTo(OkHttpClient(), update, dir) {} }; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("404")) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun cancellingStopsDownloadAndRemovesPartialFile() = withServer { server ->
        val apk = ByteArray(400_000) { 1 }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)).throttleBody(4096, 100, TimeUnit.MILLISECONDS))
        val update = UpdateInfo(15, "0.5.2", server.url("/apk").toString(), sha256(apk), apk.size.toLong(), "")
        val dir = File(temp.root, "updates")
        val started = CompletableDeferred<Unit>()
        val begin = System.nanoTime()
        runBlocking {
            val job = launch(Dispatchers.IO) { Updater.downloadTo(OkHttpClient(), update, dir) { if (it > 0f) started.complete(Unit) } }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        // The throttled body would take ~10 s; cancellation must not wait for it.
        assertTrue(System.nanoTime() - begin < 5_000_000_000L)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun downloadReplacesCorruptCachedCopy() = withServer { server ->
        val apk = ByteArray(10_000) { 3 }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val update = UpdateInfo(15, "0.5.2", server.url("/apk").toString(), sha256(apk), apk.size.toLong(), "")
        val dir = File(temp.root, "updates").apply { mkdirs() }
        File(dir, "Ezano-TV-0.5.2.apk").writeBytes(ByteArray(10_000) { 9 })
        val file = runBlocking { Updater.downloadTo(OkHttpClient(), update, dir) {} }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(1, server.requestCount)
    }
}
