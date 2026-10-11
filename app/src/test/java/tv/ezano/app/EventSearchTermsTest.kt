package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class EventSearchTermsTest {
    @Test fun userQueriesAreLiteralPrefixTerms() {
        assertEquals("\"chiefs*\" \"bills*\"",SearchTerms.fts("Chiefs / Bills"))
        assertEquals("\"cafe*\" \"concert*\"",SearchTerms.fts("Café concert café"))
        assertEquals("",SearchTerms.fts(" \" * () --- "))
        assertFalse(SearchTerms.fts("x' OR 1=1; DROP TABLE events").contains(";"))
        assertTrue(SearchTerms.tokens("word ".repeat(300)).size<=12)
    }
    @Test fun todayUsesLocalMidnightIncludingDst() {
        val zone=ZoneId.of("America/New_York")
        val now=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        assertEquals(now,SearchTerms.ceiling(EventWindow.NOW,now,zone))
        assertEquals(Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(),SearchTerms.ceiling(EventWindow.TODAY,now,zone))
        assertEquals(now+7*86_400_000L,SearchTerms.ceiling(EventWindow.WEEK,now,zone))
    }
}
