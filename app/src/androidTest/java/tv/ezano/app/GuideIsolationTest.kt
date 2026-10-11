package tv.ezano.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Regression coverage for keeping catalog imports independent from guide downloads. */
class GuideIsolationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as EzanoApp).store
    private val provider = Provider(
        id = "guide-isolation-regression",
        name = "Guide isolation fixture",
        url = "http://10.0.2.2:8768",
        user = "test",
        password = "test",
    )

    private fun awaitReady(target: Store = store) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!target.ready.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Store did not become ready", target.ready.value)
    }

    private fun awaitGuide(target: Store = store) {
        val deadline = System.currentTimeMillis() + 60_000
        while (target.guideBusy.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("Guide refresh did not finish within 60 seconds", target.guideBusy.value)
    }

    private fun fixture(path: String): String {
        val connection = URL("http://10.0.2.2:8768$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            assertEquals("Fixture request failed: $path", 200, connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun setMode(value: String, reset: Boolean = false) {
        fixture("/mode?value=$value${if (reset) "&reset=1" else ""}")
    }

    @Test fun catalogReturnsWhileGuideRunsAndPartialGuidePreservesSavedListings() = runBlocking {
        awaitReady()
        awaitGuide()
        assertFalse("Wait for any prior catalog import to finish", store.busy.value)
        store.update { Library() }
        assertTrue("Could not reset the saved library", store.flush())
        setMode("slow", reset = true)

        assertTrue("Catalog import failed: ${store.error.value}", store.refresh(provider))
        assertEquals(11, store.state.value.channels.size)
        assertTrue("Expected guide fetch to continue after catalog refresh returned", store.guideBusy.value)

        // A second Store must see the catalog even while the slow guide response is pending.
        val reloaded = Store(context)
        awaitReady(reloaded)
        assertEquals(11, reloaded.state.value.channels.size)
        awaitGuide()
        assertEquals(11, store.state.value.channels.size)
        assertFalse("Guide failure should suppress automatic retries", reloaded.shouldRefresh())

        val requestsBeforeAutomaticCheck = fixture("/stats").let { org.json.JSONObject(it).getInt("guideRequests") }
        reloaded.refreshGuideAutomatically()
        val requestsAfterAutomaticCheck = fixture("/stats").let { org.json.JSONObject(it).getInt("guideRequests") }
        assertEquals("Automatic refresh retried a failed guide", requestsBeforeAutomaticCheck, requestsAfterAutomaticCheck)

        // Load a complete guide, then make the provider return one valid entry and malformed XML.
        setMode("full")
        assertTrue("Could not seed a complete guide", store.refreshGuide(provider))
        val priorPrograms = store.state.value.programs.toList()
        assertTrue("Expected seeded guide entries", priorPrograms.size > 1)
        val preserved = priorPrograms.first { it.channelId == store.state.value.channels.first { c -> c.epgId == "isolation.2" }.id }

        setMode("truncated")
        assertTrue("Explicit guide refresh failed", store.refreshGuide(provider))
        val afterPartial = store.state.value.programs
        assertTrue("Complete entry before malformed XML was discarded", afterPartial.any { it.title == "Truncated complete listing" })
        assertTrue("Previously saved guide entry was discarded", afterPartial.any { it.id == preserved.id && it.title == preserved.title })
    }
}
