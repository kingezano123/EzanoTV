package tv.ezano.app

import org.junit.Assert.*
import org.junit.Test
import java.io.Reader

class StreamingPlaylistTest {
    private val provider=Provider(id="streaming",name="Streaming test",type="m3u",url="https://example.test/playlist")
    @Test fun largeReaderProducesCompactCatalogWithoutDecodingArtwork() {
        val count=24_000
        val longLogo="https://example.test/"+"a".repeat(900)
        val reader=chunksReader(sequence {
            yield("#EXTM3U\n")
            repeat(count) {i->yield("#EXTINF:-1 tvg-id=\"$i\" tvg-logo=\"$longLogo\" group-title=\"US News\",Channel $i\nhttps://example.test/live/$i.ts\n")}
        })
        val channels=Sources.parseM3u(reader,provider) as CompactCatalog
        assertEquals(count,channels.size)
        assertEquals(0,channels.decodedPages.get())
        assertEquals("Channel 23999",channels.catalogLabel(count-1).name)
        assertEquals(longLogo,channels.last().logo)
    }
    @Test fun pipeHeadersAndSuitabilityMetadataSurviveImport() {
        val list=Sources.parseM3u("""#EXTM3U
            |#EXTINF:-1 group-title='US Family' tvg-language='en' tvg-country='US' content-rating='PG' tvg-type='movie',A friendly movie
            |https://example.test/play?id=1|User-Agent=Ezano%20Test&Referer=https%3A%2F%2Fexample.test
        """.trimMargin(),provider)
        assertEquals(Kind.MOVIE,list.single().kind)
        assertEquals("Ezano Test",list.single().headers["User-Agent"])
        assertEquals("https://example.test",list.single().headers["Referer"])
        assertTrue(HomeRecommendations.isEligible(list.single()))
    }
    @Test fun xmltvKeepsProgramsBeyondTheFormerEightyThousandLimit() {
        val channel=Channel("p:one","p","1","Channel","",epgId="one")
        val reader=chunksReader(sequence {
            yield("<tv>")
            repeat(80_002) {i->
                val start=java.time.Instant.ofEpochMilli(1_700_000_000_000L+i*60_000L).atOffset(java.time.ZoneOffset.UTC)
                val format=java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
                yield("<programme channel=\"one\" start=\"${start.format(format)}\" stop=\"${start.plusMinutes(1).format(format)}\"><title>Event $i</title></programme>")
            }
            yield("</tv>")
        })
        val guide=Sources.parseXmltvResult(reader,listOf(channel),0)
        assertTrue(guide.warning,guide.complete)
        assertEquals(80_002,guide.programs.size)
        assertEquals("Event 80001",guide.programs.last().title)
    }
    private fun chunksReader(chunks: Sequence<String>): Reader = object: Reader() {
        val iterator=chunks.iterator();var current="";var offset=0
        override fun read(buffer: CharArray,start: Int,length: Int): Int {
            if(length==0) return 0
            while(offset==current.length) {if(!iterator.hasNext()) return -1;current=iterator.next();offset=0}
            val count=minOf(length,current.length-offset)
            current.toCharArray(buffer,start,offset,offset+count);offset+=count;return count
        }
        override fun close() {}
    }
}
