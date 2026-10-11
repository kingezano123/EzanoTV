package tv.ezano.app

/** Merge a possibly partial provider refresh with still-valid saved listings. */
fun mergeSavedGuide(
    incoming: List<Program>,
    existing: List<Program>,
    validChannelIds: Set<String>,
    cutoff: Long
): List<Program> {
    val merged = CompactGuide.Builder()
    incoming.forEach { program ->
        if (program.channelId in validChannelIds && program.end > cutoff) merged.add(program)
    }
    existing.forEach { program ->
        if (program.channelId in validChannelIds && program.end > cutoff) merged.add(program)
    }
    return merged.build()
}
