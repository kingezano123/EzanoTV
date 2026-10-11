package tv.ezano.app

import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ChunkedVaultTest {
    private val key=SecretKeySpec(ByteArray(32){it.toByte()},"AES")
    private val data=ByteArray(150000){(it*31).toByte()}
    private fun encoded():ByteArray=ByteArrayOutputStream().also {out->ChunkedVault.output(out,key).use {it.write(data)}}.toByteArray()
    private fun rejected(bytes:ByteArray,earlyClose:Boolean=false){
        try {ChunkedVault.input(bytes.inputStream(),key).use {if(earlyClose)it.read() else it.readBytes()};fail("Unauthenticated archive was accepted")}
        catch(e:Exception){assertFalse(e is AssertionError)}
    }
    @Test fun emptyAndMultiFrameArchivesRoundTrip(){
        assertArrayEquals(data,ChunkedVault.input(encoded().inputStream(),key).use {it.readBytes()})
        val empty=ByteArrayOutputStream().also {out->ChunkedVault.output(out,key).close()}.toByteArray()
        assertEquals(-1,ChunkedVault.input(empty.inputStream(),key).use {it.read()})
    }
    @Test fun changesToFileIdentityAndCiphertextAreRejected(){for(index in listOf(4,40,65580)){val b=encoded();b[index]=(b[index].toInt() xor 1).toByte();rejected(b)}}
    @Test fun truncationAndAppendAreRejected(){val b=encoded();rejected(b.copyOf(b.size-1));rejected(b+byteArrayOf(0));rejected(b.copyOf(20+65568))}
    @Test fun framesCannotBeReorderedOrSpliced(){
        val b=encoded();val width=65568;val swapped=b.copyOf();b.copyInto(swapped,20,20+width,20+width*2);b.copyInto(swapped,20+width,20,20+width);rejected(swapped)
        val other=encoded();other.copyInto(b,20,20,20+width);rejected(b)
    }
    @Test fun closingEarlyStillAuthenticatesTheEnd(){val b=encoded();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();rejected(b,true)}
}
