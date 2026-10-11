package tv.ezano.app

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class VaultFormatTest {
    private val key=SecretKeySpec(ByteArray(32){(it*7).toByte()},"AES")

    private fun sealed(write: (DataOutputStream)->Unit): ByteArray = ByteArrayOutputStream().also {out->
        DataOutputStream(ChunkedVault.output(out,key,ChunkedVault.DATA_KEY_FRAMES)).use {write(it)}
    }.toByteArray()
    private fun <T> opened(bytes: ByteArray,read: (DataInputStream)->T): T =
        ChunkedVault.input(bytes.inputStream(),key,ChunkedVault.DATA_KEY_FRAMES).use {read(DataInputStream(it.buffered()))}

    @Test fun catalogRoundTripKeepsLabelsPagesAndOrder() {
        val catalog=CompactCatalog.Builder().apply {
            for(i in 0 until 5000) add(Channel("m:$i","provider-${i%3}","$i","Movie $i ✓","https://example.test/$i.mp4",if(i%5==0)Kind.SERIES else Kind.MOVIE,
                group=if(i%2==0)"EN | Kids" else "UK | Family",logo="https://img.test/$i.jpg",year="20${10+i%16}",rating="${i%10}",genre="Animation",certification="PG",popularity=i.toDouble()))
        }.build()
        val restored=opened(sealed {VaultFormat.writeCatalog(it,catalog)}) {VaultFormat.readCatalog(it)}
        assertEquals(catalog.size,restored.size)
        for(i in listOf(0,1,31,32,2500,4999)) {
            assertEquals(catalog.catalogLabel(i),restored.catalogLabel(i))
            assertEquals(catalog[i],restored[i])
        }
        assertEquals(catalog.map {it.id},restored.map {it.id})
    }

    @Test fun guideRoundTripKeepsChannelIndex() {
        val guide=CompactGuide.Builder().apply {
            for(i in 0 until 900) add(Program("p:$i","c:${i%7}","Show $i",1_000_000L*(900-i),1_000_000L*(900-i)+600_000,"Description $i"))
        }.build()
        val restored=opened(sealed {VaultFormat.writeGuide(it,guide)}) {VaultFormat.readGuide(it)}
        assertEquals(guide.size,restored.size)
        assertEquals(guide.toList(),restored.toList())
        assertEquals(guide.forChannel("c:3").map {it.id},restored.forChannel("c:3").map {it.id})
    }

    @Test fun homeSnapshotRoundTrip() {
        val home=HomeSnapshot(listOf(Channel("m:1","p","1","Toy Story 5","u",Kind.MOVIE,logo="https://img.test/ts5.jpg",year="2026")),listOf(Channel("s:1","p","1","Bluey","",Kind.SERIES)),more=listOf(Channel("m:2","p","2","Up","",Kind.MOVIE)))
        assertEquals(home,opened(sealed {VaultFormat.writeHome(it,home)}) {VaultFormat.readHome(it)})
    }

    @Test fun keystoreFramesAndDataKeyFramesAreNotInterchangeable() {
        val bytes=sealed {it.writeInt(42)}
        try {ChunkedVault.input(bytes.inputStream(),key).use {it.readBytes()};fail("A data-key vault opened as a 0.3.1 vault")}
        catch(e:Exception){assertFalse(e is AssertionError)}
    }

    @Test fun emptyCatalogAndGuideRoundTrip() {
        assertEquals(0,opened(sealed {VaultFormat.writeCatalog(it,CompactCatalog.Builder().build())}) {VaultFormat.readCatalog(it)}.size)
        assertEquals(0,opened(sealed {VaultFormat.writeGuide(it,CompactGuide.Builder().build())}) {VaultFormat.readGuide(it)}.size)
    }
}
