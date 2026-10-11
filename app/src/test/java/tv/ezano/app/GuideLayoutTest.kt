package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test

class GuideLayoutTest {
    private fun program(id: String, start: Long, end: Long) = Program(id, "channel", id, start, end)

    @Test fun clipsProgramsToSharedWindowAndPreservesGaps() {
        val cells = guideSegments(listOf(program("before", -30, 20), program("middle", 40, 60), program("after", 80, 130)), 0, 100)
        assertEquals(listOf(0L, 20L, 40L, 60L, 80L), cells.map { it.start })
        assertEquals(listOf(20L, 40L, 60L, 80L, 100L), cells.map { it.end })
        assertEquals(listOf("before", null, "middle", null, "after"), cells.map { it.program?.id })
    }

    @Test fun emptyAndMalformedGuideStillHasWatchableChannelCell() {
        assertEquals(listOf(GuideSegment(null, 0, 100)), guideSegments(listOf(program("bad", 80, 60), program("old", -30, -10)), 0, 100))
        assertTrue(guideSegments(emptyList(), 100, 100).isEmpty())
    }

    @Test fun overlappingListingsDoNotCoverNeighboringCells() {
        val cells = guideSegments(listOf(program("late", 50, 90), program("first", 0, 60), program("duplicate", 10, 30)), 0, 100)
        assertEquals(listOf("first", "late", null), cells.map { it.program?.id })
        assertEquals(60L, cells[1].start)
        assertEquals(100L, cells.sumOf { it.end - it.start })
    }
}
