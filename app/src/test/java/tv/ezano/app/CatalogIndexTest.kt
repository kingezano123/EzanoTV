package tv.ezano.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class CatalogIndexTest {
    @Test fun labelFilteringOrderingAndLazyKeysDoNotDecodeCatalogPages() = runBlocking {
        val target = 75_999
        val builder = CompactCatalog.Builder()
        for (i in 0 until 76_000) {
            val kind = when (i % 3) { 0 -> Kind.MOVIE; 1 -> Kind.SERIES; else -> Kind.LIVE }
            builder.add(Channel(
                id = "channel:$i", provider = "provider:${i % 2}", sourceId = i.toString(),
                name = if (i == target) "Tail needle title" else "Title $i",
                url = "https://example.test/$i?token=full-url", kind = kind,
                group = if (i == target) "Needle Group" else "Group ${i % 10}",
                description = if (i == target) "Full provider description ${"details ".repeat(30)}" else "Description $i"
            ))
        }
        val catalog = builder.build()

        val matching = catalog.selectCatalog { it.kind == Kind.MOVIE || it.kind == Kind.SERIES }
            .selectCatalog { it.group == "Needle Group" || it.group == "Group 3" }
            .searchCatalog { it.name.contains("needle", ignoreCase = true) }
            .orderChannels(mapOf("channel:$target" to 0))

        assertEquals(76_000, catalog.catalogLabels().count())
        assertEquals(listOf("channel:$target"), matching.catalogLabels().map { it.id }.toList())
        assertEquals(0, catalog.decodedPages.get())

        val selected = matching.single()
        assertEquals("https://example.test/$target?token=full-url", selected.url)
        assertTrue(selected.description.startsWith("Full provider description"))
        assertTrue(catalog.decodedPages.get() in 1 until 5)
    }

    @Test fun libraryStreamRebuildsResidentLabelsForRestoredCatalog() {
        val original = CompactCatalog.Builder().apply {
            add(Channel("one", "p", "1", "Family Movie", "https://example.test/one", Kind.MOVIE,
                group = "Family", certification = "PG", language = "en"))
            add(Channel("two", "p", "2", "Live News", "https://example.test/two", Kind.LIVE, group = "News"))
        }.build()
        val output = StringWriter()
        LibraryStreams.write(Library(channels = original), output)
        assertTrue(output.toString().contains("\"compactCatalog\""))
        assertFalse(output.toString().contains("\"channels\""))

        val restored = LibraryStreams.read(StringReader(output.toString())).channels
        val labels = restored.catalogLabels().toList()

        assertEquals(listOf("one", "two"), labels.map { it.id })
        assertTrue(labels.first().familyEligible)
        assertEquals(Kind.LIVE, labels.last().kind)
        assertEquals(0, (restored as CompactCatalog).decodedPages.get())
        assertEquals("https://example.test/one", restored.single { it.id == "one" }.url)
        assertEquals(1, restored.decodedPages.get())
    }

    @Test fun legacyChannelArrayStillLoadsAndBuildsLabels() {
        val channel = Channel("old", "provider", "12", "Legacy Film", "https://example.test/legacy",
            Kind.MOVIE, group = "Family", certification = "PG", language = "en", description = "legacy description")
        val legacy = StringWriter()
        LibraryStreams.write(Library(channels = listOf(channel)), legacy)
        assertTrue(legacy.toString().contains("\"channels\""))
        assertFalse(legacy.toString().contains("\"compactCatalog\""))

        val restored = LibraryStreams.read(StringReader(legacy.toString())).channels
        assertEquals("old", restored.catalogLabel(0).id)
        assertTrue(restored.catalogLabel(0).familyEligible)
        assertEquals(channel, restored.single())
    }

    @Test fun compactStorageRejectsInvalidPageCountsAndDuplicateIds() {
        val label = CatalogLabel("same", "p", "Title", Kind.MOVIE, "Group", false, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            CompactCatalog.fromStorage(CompactCatalog.PAGE_SIZE, 1, emptyList(), listOf(label))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompactCatalog.fromStorage(CompactCatalog.PAGE_SIZE, 2,
                listOf(StoredCatalogPage(2, byteArrayOf(1))), listOf(label, label))
        }
    }

    @Test fun canceledResidentSearchStopsPromptly() = runBlocking {
        val catalog = CompactCatalog.Builder().apply {
            repeat(76_000) { i -> add(Channel("$i", "p", "$i", "Title $i", "", Kind.MOVIE)) }
        }.build()
        var visited = 0
        lateinit var search: kotlinx.coroutines.Deferred<*>
        search = async(Dispatchers.Default, start=kotlinx.coroutines.CoroutineStart.LAZY) {
            catalog.searchCatalog {
                visited++
                if (visited == 512) search.cancel()
                true
            }
        }

        search.start()
        search.join()

        assertTrue(search.isCancelled)
        assertEquals(512, visited)
        assertEquals(0, catalog.decodedPages.get())
    }
}
