package tv.ezano.app

import android.content.Intent
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import java.util.zip.GZIPOutputStream

/** UI-only release checks: no calls into names that R8 may rename. Seed, upgrade and reopen separately. */
class ReleaseSmokeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @org.junit.Before fun dedicatedReleaseRun(){org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("release_checks")=="true")}
    @Test fun seedLegacyFixture(){
        // Seed after installing 0.3.0; its application has already generated the existing key.
        val key=(KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("ezano-library",null) as? SecretKey)?:javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore").apply {init(android.security.keystore.KeyGenParameterSpec.Builder("ezano-library",android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())}.generateKey()
        val movie=JSONObject().put("id","release-speech").put("provider","release-fixture").put("sourceId","1").put("name","English speech check").put("url","http://10.0.2.2:8767/speech.wav").put("kind","MOVIE").put("group","EN Movies").put("language","en").put("certification","PG").put("genre","Family")
        val provider=JSONObject().put("id","release-fixture").put("name","Release fixture").put("type","m3u").put("url","http://10.0.2.2:8767/speech.wav").put("enabled",true)
        val data=JSONObject().put("version",1).put("providers",JSONArray().put(provider)).put("channels",JSONArray().put(movie)).put("programs",JSONArray()).put("favorites",JSONArray().put("release-speech")).put("positions",JSONObject().put("release-speech",5000)).put("recent",JSONArray().put(movie)).put("prefs",JSONObject().put("autoSubtitles",true).put("panelSeconds",20))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key)}
        File(context.filesDir,"library.vault").outputStream().use {out->out.write("EZV2".toByteArray());out.write(cipher.iv);GZIPOutputStream(CipherOutputStream(out,cipher)).use {it.write(data.toString().toByteArray())}}
        context.getSharedPreferences("speech-captions",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit()
        File(context.externalMediaDirs.first(),"model-prepared").copyRecursively(File(context.filesDir,"speech-model/vosk-model-small-en-us-0.15"),overwrite=true)
    }
    private fun launch(){context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue(device.wait(Until.hasObject(By.text("Continue Watching")),30000))}
    @Test fun upgradedReleasePlaysSpeechAndBackReturnsToBrowsing(){
        launch();assertTrue(device.wait(Until.hasObject(By.text("Resume")),10000));device.findObject(By.text("Resume")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Audio & subtitles")),20000))
        val spoken=By.text(java.util.regex.Pattern.compile("(?i).*\\b(one|zero|nine|two|three)\\b.*" )).clazz("android.widget.TextView")
        assertTrue("Release speech caption did not appear",device.wait(Until.hasObject(spoken),45000))
        device.takeScreenshot(File(context.externalMediaDirs.first(),"release-player-031.png"));device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Continue Watching")),10000));assertFalse(device.hasObject(By.text("Expand")));assertFalse(device.hasObject(By.text("PiP")))
        assertFalse(device.hasObject(By.text("Audio & subtitles")))
        val permissions=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.RECORD_AUDIO"))
    }
    @Test fun releaseReopensSavedLibrary(){launch();assertTrue(device.wait(Until.hasObject(By.text("Resume")),10000));assertTrue(device.wait(Until.hasObject(By.text("Favorite channels")),10000));device.takeScreenshot(File(context.externalMediaDirs.first(),"release-home-031.png"))}
}
