package tv.ezano.app

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Guide descriptions stay compressed. Channel lookups use offsets built during import, not a scan. */
class CompactGuide private constructor(
    private val pages: List<ByteArray>,
    override val size: Int,
    private val channelIndices: Map<String, IntArray>
) : AbstractList<Program>(), java.util.RandomAccess {
    private val cache = object : LinkedHashMap<Int, List<Program>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Program>>?) = size > 6
    }

    override fun get(index: Int): Program {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Guide index $index")
        return readRow(index)
    }

    private fun readRow(index: Int): Program {
        val page = index / PAGE_SIZE
        val rows = synchronized(cache) { cache[page] } ?: run {
            // Inflating a cold page must not block readers of the other cached pages.
            val decoded = decodePage(page)
            synchronized(cache) { cache[page] ?: decoded.also { cache[page] = it } }
        }
        return rows[index % PAGE_SIZE]
    }

    private fun decodePage(page: Int): List<Program> = GZIPInputStream(pages[page].inputStream()).reader().use {
        val json = JSONArray(it.readText())
        List(json.length()) { row -> Json.program(json.getJSONObject(row)) }
    }

    private fun scanReader(): (Int) -> Program {
        var currentPage = -1
        var rows = emptyList<Program>()
        return { index ->
            val page = index / PAGE_SIZE
            if (page != currentPage) {
                // Persistence and background scans keep one local page without churning the UI cache.
                rows = synchronized(cache) { cache[page] } ?: decodePage(page)
                currentPage = page
            }
            rows[index % PAGE_SIZE]
        }
    }

    /** A chronological view retaining only this channel's integer offsets; no programs are decoded here. */
    fun forChannel(id: String): List<Program> = channelIndices[id]?.let(::ChannelView) ?: emptyList()

    override fun iterator(): Iterator<Program> = listIterator()
    override fun listIterator(): ListIterator<Program> = listIterator(0)
    override fun listIterator(index: Int): ListIterator<Program> = GuideIterator(size, index, scanReader())

    private inner class ChannelView(private val indices: IntArray) : AbstractList<Program>(), java.util.RandomAccess {
        override val size get() = indices.size
        override fun get(index: Int): Program = readRow(indices[index])
        override fun iterator(): Iterator<Program> = listIterator()
        override fun listIterator(): ListIterator<Program> = listIterator(0)
        override fun listIterator(index: Int): ListIterator<Program> {
            val read = scanReader()
            return GuideIterator(size, index) { read(indices[it]) }
        }
    }

    internal val compressedBytes: Long get() = pages.sumOf { it.size.toLong() }

    /** A single-use streaming builder. Duplicate program IDs keep the first entry. */
    class Builder {
        private val pages = ArrayList<ByteArray>()
        private val pending = ArrayList<Program>(PAGE_SIZE)
        private val ids = HashSet<String>()
        private val channelIndices = HashMap<String, MutableList<Int>>()
        private val starts = ArrayList<Long>()
        private var built = false
        var size = 0; private set

        fun add(program: Program) {
            check(!built) { "Guide builder has already been built" }
            if (!ids.add(program.id)) return
            channelIndices.getOrPut(program.channelId) { ArrayList() }.add(size)
            starts += program.start
            pending += program
            size++
            if (pending.size == PAGE_SIZE) flushPage()
        }

        fun addAll(programs: Iterable<Program>) { programs.forEach(::add) }

        private fun flushPage() {
            if (pending.isEmpty()) return
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).writer().use { output ->
                output.write("[")
                pending.forEachIndexed { index, program ->
                    if (index > 0) output.write(",")
                    output.write(with(Json) { program.json().toString() })
                }
                output.write("]")
            }
            pages += bytes.toByteArray()
            pending.clear()
        }

        fun build(): CompactGuide {
            check(!built) { "Guide builder has already been built" }
            flushPage()
            val byChannel = channelIndices.mapValues { (_, indices) -> indices.sortedBy { starts[it] }.toIntArray() }
            built = true
            return CompactGuide(pages.toList(), size, byChannel).also {
                // Retaining a finished builder must not keep all program IDs and boxed offsets alive.
                ids.clear(); channelIndices.clear(); starts.clear()
            }
        }
    }

    internal fun storedPages()=pages
    internal fun storedIndex()=channelIndices
    companion object {
        internal const val PAGE_SIZE=128
        internal fun fromStorage(size:Int,pages:List<ByteArray>,index:Map<String,IntArray>):CompactGuide {
            require(size>=0 && pages.size==(size+PAGE_SIZE-1)/PAGE_SIZE && pages.all {it.isNotEmpty()}) {"Invalid saved guide pages"}
            val seen=BooleanArray(size);var total=0
            index.forEach {(_,rows)->rows.forEach {row->require(row in 0 until size && !seen[row]) {"Invalid saved guide index"};seen[row]=true;total++}}
            require(total==size) {"Incomplete saved guide index"}
            return CompactGuide(pages,size,index)
        }
    }
}

private class GuideIterator(private val size: Int, index: Int, private val read: (Int) -> Program) : ListIterator<Program> {
    private var cursor = index
    init { if (index !in 0..size) throw IndexOutOfBoundsException("Guide iterator index $index") }
    override fun hasNext() = cursor < size
    override fun hasPrevious() = cursor > 0
    override fun nextIndex() = cursor
    override fun previousIndex() = cursor - 1
    override fun next(): Program {
        if (!hasNext()) throw NoSuchElementException()
        return read(cursor).also { cursor++ }
    }
    override fun previous(): Program {
        if (!hasPrevious()) throw NoSuchElementException()
        return read(cursor - 1).also { cursor-- }
    }
}
