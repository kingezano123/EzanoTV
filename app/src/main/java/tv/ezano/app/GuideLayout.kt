package tv.ezano.app

internal const val GUIDE_WINDOW_MS = 90 * 60_000L

internal data class GuideSegment(val program: Program?, val start: Long, val end: Long)

/** Time slices share one axis across every channel, including gaps and clipped events. */
internal fun guideSegments(programs: List<Program>, start: Long, end: Long): List<GuideSegment> {
    if (end <= start) return emptyList()
    val segments = mutableListOf<GuideSegment>()
    var cursor = start
    for (program in programs.asSequence().filter { it.end > start && it.start < end && it.end > it.start }.sortedBy { it.start }) {
        val left = maxOf(program.start, cursor, start)
        val right = minOf(program.end, end)
        if (right <= left) continue
        if (left > cursor) segments += GuideSegment(null, cursor, left)
        segments += GuideSegment(program, left, right)
        cursor = right
        if (cursor == end) break
    }
    if (cursor < end) segments += GuideSegment(null, cursor, end)
    return segments
}
