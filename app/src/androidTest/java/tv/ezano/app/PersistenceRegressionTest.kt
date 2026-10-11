package tv.ezano.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Regression coverage for imports reaching durable storage and preserving prior data on empty guides. */
class PersistenceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as EzanoApp).store
    private val provider = Provider(
        id = "persistence-regression",
        name = "Persistence fixture",
        url = "http://10.0.2.2:8767",
        user = "test",
        password = "test",
    )

    private fun awaitReady(target: Store = store) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!target.ready.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Store did not become ready", target.ready.value)
    }

    private fun awaitGuideIdle(target: Store = store) {
        val deadline = System.currentTimeMillis() + 120_000
        while (target.guideBusy.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("Guide job did not finish", target.guideBusy.value)
    }

    private fun setMode(value: String) {
        val connection = URL("http://10.0.2.2:8767/mode?value=$value").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            assertEquals("Fixture mode update failed", 200, connection.responseCode)
        } finally { connection.disconnect() }
    }

    @Test fun importedCatalogAndGuidePersistWithoutFlushAndSurviveEmptyOrFailedGuide() = runBlocking {
        awaitReady()
        awaitGuideIdle()
        assertFalse("Wait for any prior import to finish", store.busy.value)
        store.update { Library() }
        assertTrue("Could not reset the saved library", store.flush()) // The import below is deliberately never flushed.
        setMode("full")

        assertTrue("Initial Xtream import failed: ${store.error.value}", store.refresh(provider))
        awaitGuideIdle()
        val imported = store.state.value
        assertEquals(11, imported.channels.size)
        assertEquals(6, imported.channels.count { it.kind == Kind.MOVIE })
        assertEquals(1, imported.channels.count { it.kind == Kind.SERIES })
        assertEquals(32, imported.programs.size)

        // Opening a second Store immediately must observe the committed import without flush().
        val reloaded = Store(context)
        awaitReady(reloaded)
        assertEquals(11, reloaded.state.value.channels.size)
        assertEquals(6, reloaded.state.value.channels.count { it.kind == Kind.MOVIE })
        assertEquals(1, reloaded.state.value.channels.count { it.kind == Kind.SERIES })
        assertEquals(32, reloaded.state.value.programs.size)

        setMode("empty")
        assertTrue("Empty response import failed: ${store.error.value}", store.refresh(provider))
        awaitGuideIdle()
        assertEquals(6, store.state.value.channels.count { it.kind == Kind.MOVIE })
        assertEquals(1, store.state.value.channels.count { it.kind == Kind.SERIES })
        assertEquals(32, store.state.value.programs.size)
        val emptyResponseReload = Store(context)
        awaitReady(emptyResponseReload)
        assertEquals(11, emptyResponseReload.state.value.channels.size)
        assertEquals(32, emptyResponseReload.state.value.programs.size)

        setMode("failed-guide")
        assertTrue("Catalog import should succeed when guide is unavailable", store.refresh(provider))
        awaitGuideIdle()
        assertEquals(6, store.state.value.channels.count { it.kind == Kind.MOVIE })
        assertEquals(1, store.state.value.channels.count { it.kind == Kind.SERIES })
        assertEquals(32, store.state.value.programs.size)
        assertTrue("Expected unavailable guide status: ${store.guideStatus.value}", store.guideStatus.value.contains("guide is unavailable", true))
    }

    @Test fun refreshReportsFailureWhenLibraryCannotBeSaved() = runBlocking {
        awaitReady()
        awaitGuideIdle()
        assertFalse("Wait for any prior import to finish", store.busy.value)
        store.update { Library() }
        assertTrue("Could not reset the saved library", store.flush())
        setMode("full")
        assertTrue("Initial Xtream import failed: ${store.error.value}", store.refresh(provider))
        awaitGuideIdle()
        assertEquals(11, store.state.value.channels.size)

        // Drain the debounced autosave before deliberately blocking AtomicFile's .new path.
        Thread.sleep(600)
        assertTrue("Could not establish the initial saved snapshot", store.flush())
        val blockedTemp = java.io.File(context.filesDir, "library.vault.new")
        assertFalse("AtomicFile temp path unexpectedly exists: $blockedTemp", blockedTemp.exists())
        assertTrue("Could not block AtomicFile temp path", blockedTemp.mkdir())
        try {
            val secondProvider = provider.copy(id = "persistence-regression-save-failure", name = "Persistence save failure")
            assertFalse("Refresh reported success even though saving failed", store.refresh(secondProvider))
            assertTrue("Expected save failure message: ${store.error.value}", store.error.value.contains("could not be saved", true))
        } finally {
            assertTrue("Could not remove the synthetic AtomicFile obstruction", !blockedTemp.exists() || blockedTemp.delete())
            store.update { Library() }
            assertTrue("Could not save the recovered library", store.flush())
        }
    }
}
