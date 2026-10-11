package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompactCatalogTest {
    @Test fun preservesEveryKindBeyondOldSharedLimitAndSupportsTailSearch() {
        val builder=CompactCatalog.Builder()
        for(i in 1..61000) builder.add(Channel("movie:$i","p","$i","Movie $i","https://example.test/movie/$i.mp4",Kind.MOVIE,description="A detailed provider synopsis. ".repeat(30)))
        builder.add(Channel("series:1","p","1","Last Series","",Kind.SERIES))
        builder.add(Channel("series:1","p","1","Duplicate","",Kind.SERIES))
        val catalog=builder.build()
        assertEquals(61001,catalog.size)
        assertEquals("Last Series",catalog.last().name)
        assertEquals("movie:61000",catalog.selectChannels {it.kind==Kind.MOVIE && it.name=="Movie 61000"}.single().id)
        assertEquals("series:1",catalog.selectChannels {it.kind==Kind.SERIES}.single().id)
        assertTrue("Catalog must stay compressed",catalog.compressedBytes<8*1024*1024)
    }
    @Test fun indexedViewsPreserveOrderAndRemainIndependentAfterPaging() {
        val catalog=CompactCatalog.Builder().apply {for(i in 0..1500) add(Channel("$i","p","$i","Item $i","",if(i%2==0)Kind.MOVIE else Kind.SERIES))}.build()
        val filtered=catalog.selectChannels {it.kind==Kind.SERIES}.orderChannels(mapOf("1499" to 0,"1" to 1))
        assertEquals(750,filtered.size)
        assertEquals(listOf("1499","1","3"),filtered.take(3).map {it.id})
        repeat(3) {assertEquals("1497",filtered.last().id);assertEquals("1499",filtered.first().id)}
    }
    @Test fun independentScansRetainTheirCurrentPageWhileOtherReadersTraverseTheCatalog() {
        val catalog = fixture(2049)
        val view = catalog.selectChannels { it.kind == Kind.SERIES }.selectChannels { it.sourceId.toInt() % 3 != 0 }
        for (source in listOf(catalog, view)) {
            val iterator = source.listIterator()
            val first = iterator.next()
            // Fill and evict every shared-cache page, as Home, persistence and UI may do together.
            for (index in catalog.indices step 128) catalog[index]
            catalog.forEach { assertEquals(it.id, it.sourceId) }
            assertSame("A scan must retain its page independently of other readers", first, iterator.previous())
            assertSame(first, iterator.next())
            assertEquals(1, iterator.nextIndex())
        }
    }
    @Test fun concurrentForwardReverseAndFilteredScansPreserveAllRowsAndOrder() {
        val catalog = fixture(2049)
        val view = catalog.selectChannels { it.kind == Kind.SERIES }.orderChannels(mapOf("2047" to 0, "1" to 1))
        val expected = listOf("2047") + (1 until 2047 step 2).map(Int::toString)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { worker -> pool.submit(Callable {
                start.await()
                val reverse = worker % 2 == 1
                val iterator = view.listIterator(if (reverse) view.size else 0)
                val ids = ArrayList<String>()
                while (if (reverse) iterator.hasPrevious() else iterator.hasNext()) {
                    ids += (if (reverse) iterator.previous() else iterator.next()).id
                    if (ids.size % 31 == 0) {
                        // Random visible-cell access must remain correct during background scans.
                        val index = (ids.size * (worker + 1)) % catalog.size
                        assertEquals(index.toString(), catalog[index].id)
                        Thread.yield()
                    }
                }
                if (reverse) ids.reversed() else ids
            }) }
            start.countDown()
            jobs.forEach { assertEquals(expected, it.get(20, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }
    @Test fun listContractAndIteratorBoundariesRemainCompatibleWithOrdinaryLists() {
        val catalog = fixture(259)
        val ordinary = (0 until catalog.size).map { catalog[it] }
        assertEquals(ordinary, catalog)
        assertEquals(catalog, ordinary)
        assertEquals(ordinary.hashCode(), catalog.hashCode())
        val tail = catalog.listIterator(catalog.size)
        assertFalse(tail.hasNext())
        assertEquals(258, tail.previousIndex())
        assertEquals("258", tail.previous().id)
        assertThrows(IndexOutOfBoundsException::class.java) { catalog.listIterator(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { catalog.listIterator(catalog.size + 1) }
        assertThrows(NoSuchElementException::class.java) { catalog.listIterator().previous() }
        assertThrows(NoSuchElementException::class.java) { catalog.listIterator(catalog.size).next() }
    }
    private fun fixture(count: Int) = CompactCatalog.Builder().apply {
        for (i in 0 until count) add(Channel("$i", "p", "$i", "Item $i", "", if (i % 2 == 0) Kind.MOVIE else Kind.SERIES))
    }.build()
}
