package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test

class PartialGuideTest {
    private val channel = Channel("p:live", "p", "1", "Live", "", epgId = "one")
    private val cutoff = 0L

    @Test fun completeEntriesSurviveTruncatedXml() {
        val xml = """<tv><programme channel="one" start="20260921120000 +0000" stop="20260921130000 +0000"><title>First</title></programme><programme channel="one" start="20260921130000 +0000" stop="20260921140000 +0000"><title>Broken"""
        val result = Sources.parseXmltvResult(xml.reader(), listOf(channel), cutoff)
        assertFalse(result.complete)
        assertEquals("First", result.programs.single().title)
        assertTrue(result.warning.isNotBlank())
    }

    @Test fun fullXmlReportsSuccess() {
        val xml = """<tv><programme channel="one" start="20260921120000 +0000" stop="20260921130000 +0000"><title>First</title></programme></tv>"""
        val result = Sources.parseXmltvResult(xml.reader(), listOf(channel), cutoff)
        assertTrue(result.complete)
        assertEquals("", result.warning)
        assertEquals("First", result.programs.single().title)
    }

    @Test fun failureBeforeAnyCompleteEntryReturnsSafeIncompleteResult() {
        val xml = """<tv><programme channel="one" start="20260921120000 +0000" stop="20260921130000 +0000"><title>Never completed"""
        val result = Sources.parseXmltvResult(xml.reader(), listOf(channel), cutoff)
        assertFalse(result.complete)
        assertTrue(result.programs.isEmpty())
        assertEquals("The guide could not be fully loaded. Some listings may be missing.", result.warning)
    }
}
