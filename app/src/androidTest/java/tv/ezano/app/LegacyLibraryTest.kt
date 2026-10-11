package tv.ezano.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

class LegacyLibraryTest {
    @Test fun oldEncryptedLibraryMigratesAndRemovesDuplicateKeys() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val isolated=object:ContextWrapper(target){override fun getFilesDir()=File(target.cacheDir,"migration-${System.nanoTime()}").apply {mkdirs()}}
        // Use a fixed directory for this test context, without touching the app's main library.
        val directory=isolated.filesDir
        val context=object:ContextWrapper(target){override fun getFilesDir()=directory}
        val empty=Store(context);while(!empty.ready.value)Thread.sleep(20);runBlocking {empty.flush()}
        val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("ezano-library",null) as SecretKey
        val channel=Channel("old:live:1","old","1","Old channel","https://example.test/1.ts")
        val provider=Provider(id="old",name="Existing provider",url="https://example.test",user="demo",password="fixture-secret")
        val old=Library(providers=listOf(provider),channels=listOf(channel,channel),favorites=setOf(channel.id))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
        File(directory,"library.vault").writeBytes(cipher.iv+cipher.doFinal(Json.encode(old).toByteArray()))
        val migrated=Store(context);while(!migrated.ready.value)Thread.sleep(20)
        assertEquals("",migrated.error.value);assertEquals(1,migrated.state.value.channels.size);assertEquals(provider,migrated.state.value.providers.single());assertTrue(channel.id in migrated.state.value.favorites)
        runBlocking {migrated.flush()}
        assertEquals("EZV3",File(directory,"library.vault").inputStream().use {input->val header=ByteArray(4);assertEquals(4,input.read(header));String(header,Charsets.US_ASCII)})
        val reopened=Store(context);while(!reopened.ready.value)Thread.sleep(20)
        assertEquals(migrated.state.value,reopened.state.value)
    }
}
