package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompactGuideTest {
    @Test fun xmlImportKeepsItsStreamOrderAndDeduplicatesWithoutExpandingTheGuide() {
        val channel = Channel("p:1", "p", "1", "Live", "", epgId = "one")
        val xml = """<tv>
            <programme channel="one" start="20260921120000 +0000" stop="20260921130000 +0000"><title>Later</title></programme>
            <programme channel="one" start="20260921100000 +0000" stop="20260921110000 +0000"><title>Earlier</title></programme>
            <programme channel="one" start="20260921120000 +0000" stop="20260921130000 +0000"><title>Duplicate</title></programme>
        </tv>"""
        val guide = Sources.parseXmltv(xml.reader(), listOf(channel), 0)
        assertTrue(guide is CompactGuide)
        assertEquals(listOf("Later", "Earlier"), guide.map { it.title })
        assertEquals(listOf("Earlier", "Later"), (guide as CompactGuide).forChannel(channel.id).map { it.title })
    }

    @Test fun descriptionsStayCompressedAndChannelLookupsAreChronological() {
        val builder = CompactGuide.Builder()
        for (i in 4096 downTo 0) builder.add(program(i, "Detailed provider synopsis. ".repeat(75)))
        builder.add(program(4096).copy(title = "Duplicate must not replace the original"))
        val guide = builder.build()
        assertEquals(4097, guide.size)
        assertEquals("Program 4096", guide.first().title)
        assertEquals(1024, guide.forChannel("channel:3").size)
        assertEquals((3..4096 step 4).map { "program:$it" }, guide.forChannel("channel:3").map { it.id })
        assertEquals("Detailed provider synopsis. ".repeat(75), guide.last().description)
        assertTrue(guide.forChannel("missing").isEmpty())
        assertTrue("Descriptions must remain compressed", guide.compressedBytes < 1024 * 1024)
    }

    @Test fun scansKeepTheirOwnPageDuringConcurrentUiAccess() {
        val guide = CompactGuide.Builder().apply { for (i in 0..2048) add(program(i)) }.build()
        for (source in listOf(guide, guide.forChannel("channel:1"))) {
            val iterator = source.listIterator()
            val first = iterator.next()
            for (index in guide.indices step 128) guide[index]
            guide.forEach { assertEquals(it.start, it.end - 1000) }
            assertSame(first, iterator.previous())
            assertSame(first, iterator.next())
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = (0..2).map { worker -> pool.submit(Callable {
                start.await()
                val channel = guide.forChannel("channel:$worker")
                val iterator = channel.listIterator(channel.size)
                val ids = ArrayList<String>()
                while (iterator.hasPrevious()) {
                    ids += iterator.previous().id
                    val index = (ids.size * 129) % guide.size
                    assertEquals("program:$index", guide[index].id)
                }
                ids.reversed()
            }) }
            start.countDown()
            jobs.forEachIndexed { worker, job ->
                assertEquals((worker..2048 step 4).map { "program:$it" }, job.get(20, TimeUnit.SECONDS))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun listContractAndBackupRoundTripPreserveInsertionOrder() {
        val ordinary = (258 downTo 0).map { program(it) }
        val guide = CompactGuide.Builder().apply { addAll(ordinary) }.build()
        assertEquals(ordinary, guide)
        assertEquals(guide, ordinary)
        assertEquals(ordinary.hashCode(), guide.hashCode())
        assertThrows(IndexOutOfBoundsException::class.java) { guide[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { guide[guide.size] }
        assertThrows(IndexOutOfBoundsException::class.java) { guide.listIterator(guide.size + 1) }
        assertThrows(NoSuchElementException::class.java) { guide.listIterator().previous() }
        assertThrows(NoSuchElementException::class.java) { guide.listIterator(guide.size).next() }
        val writer = StringWriter()
        LibraryStreams.write(Library(programs = guide), writer)
        val restored = LibraryStreams.read(writer.toString().reader()).programs
        assertTrue(restored is CompactGuide)
        assertEquals(ordinary, restored)
        assertEquals(guide.forChannel("channel:2"), (restored as CompactGuide).forChannel("channel:2"))
    }

    private fun program(index: Int, description: String = "Synopsis") =
        Program("program:$index", "channel:${index % 4}", "Program $index", index * 1000L, index * 1000L + 1000, description)
}
