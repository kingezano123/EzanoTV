package tv.ezano.app

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticate 64 KB frames instead of buffering a whole library in Android's GCM provider. */
object ChunkedVault {
    private const val CHUNK=65536
    /** Library frames sealed directly by the Android Keystore key (0.3.1). */
    val KEYSTORE_FRAMES="EZV3".toByteArray(Charsets.US_ASCII)
    /** Frames sealed in software by a data key that the Keystore wraps; one Keystore call per launch. */
    val DATA_KEY_FRAMES="EZV4".toByteArray(Charsets.US_ASCII)
    private fun aad(magic: ByteArray,identity: ByteArray,index: Long,length: Int)=ByteBuffer.allocate(32).put(magic).put(identity).putLong(index).putInt(length).array()
    fun output(output: OutputStream,key: SecretKey,magic: ByteArray=KEYSTORE_FRAMES): OutputStream = object:OutputStream() {
        private val data=DataOutputStream(output)
        private val identity=ByteArray(16).also {SecureRandom().nextBytes(it)}
        private val buffer=ByteArray(CHUNK)
        private var count=0;private var index=0L;private var closed=false
        init {data.write(magic);data.write(identity)}
        private fun frame(length: Int) {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
            require(cipher.iv.size==12)
            cipher.updateAAD(aad(magic,identity,index++,length))
            val encrypted=cipher.doFinal(buffer,0,length)
            data.writeInt(encrypted.size);data.write(cipher.iv);data.write(encrypted)
        }
        override fun write(value: Int) {check(!closed);buffer[count++]=value.toByte();if(count==CHUNK){frame(count);count=0}}
        override fun write(bytes: ByteArray,offset: Int,length: Int) {
            check(!closed);require(offset>=0 && length>=0 && offset<=bytes.size-length)
            var pos=offset;var left=length
            while(left>0) {val n=minOf(left,CHUNK-count);bytes.copyInto(buffer,count,pos,pos+n);count+=n;pos+=n;left-=n;if(count==CHUNK){frame(count);count=0}}
        }
        override fun flush(){data.flush()}
        // Caller owns the AtomicFile stream and commits only after the terminal tag is written.
        override fun close(){if(!closed){if(count>0)frame(count);frame(0);closed=true;data.flush()}}
    }
    fun input(input: InputStream,key: SecretKey,magic: ByteArray=KEYSTORE_FRAMES): InputStream = object:InputStream() {
        private val data=DataInputStream(input);private val identity=ByteArray(16)
        private var buffer=ByteArray(0);private var pos=0;private var index=0L;private var ended=false
        init {val h=ByteArray(4);data.readFully(h);require(h.contentEquals(magic)){"Unsupported vault"};data.readFully(identity)}
        private fun next(): Boolean {
            if(ended)return false
            val length=data.readInt();require(length in 16..CHUNK+16){"Invalid vault frame"}
            val iv=ByteArray(12);data.readFully(iv);val encrypted=ByteArray(length);data.readFully(encrypted)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));cipher.updateAAD(aad(magic,identity,index++,length-16))
            buffer=cipher.doFinal(encrypted);pos=0
            if(buffer.isEmpty()){require(data.read()==-1){"Trailing vault data"};ended=true;return false}
            return true
        }
        override fun read(): Int {if(pos==buffer.size && !next())return -1;return buffer[pos++].toInt() and 255}
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {require(offset>=0 && length>=0 && offset<=bytes.size-length);if(length==0)return 0;if(pos==buffer.size && !next())return -1;val n=minOf(length,buffer.size-pos);buffer.copyInto(bytes,offset,pos,pos+n);pos+=n;return n}
        override fun close(){try{val discard=ByteArray(CHUNK);while(read(discard)!=-1){} }finally{data.close()}}
    }
}
