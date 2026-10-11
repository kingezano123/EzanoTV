package tv.ezano.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Explicit one-time model setup. No audio or IPTV credentials leave this device. */
object SpeechModel {
    private const val NAME="vosk-model-small-en-us-0.15"
    private const val URL="https://alphacephei.com/vosk/models/$NAME.zip"
    private const val SHA="30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private val required=listOf("am/final.mdl","conf/model.conf","conf/mfcc.conf","graph/HCLr.fst","graph/Gr.fst","ivector/final.ie")
    fun directory(context:Context)=File(context.filesDir,"speech-model/$NAME")
    fun installed(context:Context)=directory(context).let {dir->File(dir,".verified").isFile && required.all {File(dir,it).length()>0}}
    @Synchronized fun install(context:Context,progress:(String)->Unit) {
        if(installed(context))return
        val root=File(context.filesDir,"speech-model").apply {mkdirs()}
        require(root.usableSpace>200L*1024*1024){"Free at least 200 MB of device storage first"}
        val archive=File(root,"download.partial");val staging=File(root,"extracting")
        try {
            progress("Downloading English model…")
            val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).followRedirects(false).build()
            client.newCall(Request.Builder().url(URL).build()).execute().use {response->
                require(response.isSuccessful){"Model download unavailable. Try again later."}
                response.body!!.byteStream().use {input->archive.outputStream().use {out->
                    val buffer=ByteArray(65536);var total=0L;var shown=-1
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=50L*1024*1024){"Unexpected model size"};out.write(buffer,0,n);val mb=(total/1048576).toInt();if(mb!=shown){shown=mb;progress("Downloading English model · $mb MB")}}
                }}
            }
            installArchive(archive,staging,directory(context)){progress("Preparing English model…")}
            progress("English speech model ready")
        } finally {archive.delete();staging.deleteRecursively()}
    }
    internal fun installArchive(archive:File,staging:File,target:File,progress:()->Unit={}) {
        val digest=MessageDigest.getInstance("SHA-256")
        archive.inputStream().use {input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==SHA){"Model verification failed. Retry the download."}
        staging.deleteRecursively();staging.mkdirs();progress();var expanded=0L;var entries=0
        ZipInputStream(archive.inputStream().buffered()).use {zip->
            while(true){val entry=zip.nextEntry?:break;require(++entries<=256){"Unexpected model archive"}
                require(entry.name.startsWith("$NAME/") && !entry.name.contains('\\')){"Unexpected model path"}
                val destination=File(staging,entry.name).canonicalFile
                require(destination.toPath().startsWith(staging.canonicalFile.toPath())){"Invalid model path"}
                if(entry.isDirectory)destination.mkdirs() else {
                    destination.parentFile!!.mkdirs();destination.outputStream().use {out->val buf=ByteArray(65536);while(true){val n=zip.read(buf);if(n<0)break;expanded+=n;require(expanded<=150L*1024*1024){"Unexpected expanded model size"};out.write(buf,0,n)}}
                }
                zip.closeEntry()
            }
        }
        val candidate=File(staging,NAME);require(required.all {File(candidate,it).length()>0}){"Incomplete English model"}
        File(candidate,".verified").writeText(SHA)
        target.parentFile!!.mkdirs();target.deleteRecursively();require(candidate.renameTo(target)){"Could not save the model. Check free storage."}
    }
}
