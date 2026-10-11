package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test

class GuideMergeTest {
    private val cutoff = 100L
    private fun program(id: String, channelId: String, end: Long, title: String = id) =
        Program(id, channelId, title, end - 10, end)

    @Test fun partialIncomingRetainsSavedFutureEntries() {
        val result = mergeSavedGuide(
            incoming = listOf(program("fresh", "p:1", 200)),
            existing = listOf(program("saved", "p:1", 300)),
            validChannelIds = setOf("p:1"),
            cutoff = cutoff
        )
        assertEquals(listOf("fresh", "saved"), result.map { it.id })
    }

    @Test fun incomingVersionWinsDuplicateId() {
        val result = mergeSavedGuide(
            incoming = listOf(program("same", "p:1", 200, "Updated")),
            existing = listOf(program("same", "p:1", 210, "Old")),
            validChannelIds = setOf("p:1"),
            cutoff = cutoff
        )
        assertEquals(1, result.size)
        assertEquals("Updated", result.single().title)
    }

    @Test fun expiredAndOrphanedProgramsAreDropped() {
        val result = mergeSavedGuide(
            incoming = listOf(program("expired-new", "p:1", cutoff)),
            existing = listOf(program("expired-old", "p:1", cutoff), program("orphan", "deleted", 300)),
            validChannelIds = setOf("p:1"),
            cutoff = cutoff
        )
        assertTrue(result.isEmpty())
    }

    @Test fun refreshForAnotherProviderPreservesOtherProvidersGuide() {
        val result = mergeSavedGuide(
            incoming = listOf(program("provider-b", "b:1", 200)),
            existing = listOf(program("provider-a", "a:1", 300)),
            validChannelIds = setOf("a:1", "b:1"),
            cutoff = cutoff
        )
        assertEquals(setOf("provider-a", "provider-b"), result.map { it.id }.toSet())
    }
}
