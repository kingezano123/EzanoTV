package tv.ezano.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream

data class SubtitleSource(val url: String,val label: String="English",val language: String="en",val mime: String="text/vtt")

/** Only direct provider caption files; never a webpage or another site's player. */
object ProviderSubtitles {
    fun parse(root: JSONObject,base: String): List<SubtitleSource> {
        val result=mutableListOf<SubtitleSource>()
        val objects=listOfNotNull(root,root.optJSONObject("info"),root.optJSONObject("movie_data"))
        for(o in objects) for(key in listOf("subtitles","subtitle_tracks","tracks")) {
            val values=o.optJSONArray(key)?:continue
            for(i in 0 until values.length()) {
                val item=values.optJSONObject(i)?:continue
                val path=listOf("url","src","file").firstNotNullOfOrNull {item.optString(it).takeIf(String::isNotBlank)}?:continue
                val address=runCatching {base.toHttpUrl().resolve(path)?.toString()}.getOrNull()?:continue
                val ext=address.substringBefore('?').substringAfterLast('.').lowercase()
                val mime=when(ext){"vtt"->"text/vtt";"srt"->"application/x-subrip";else->continue}
                val lang=item.optString("language",item.optString("lang",item.optString("srclang","und"))).lowercase().let {if(it in listOf("english","eng")) "en" else it}
                result+=SubtitleSource(address,item.optString("label",lang.uppercase()),lang,mime)
            }
        }
        return result.distinctBy {it.url}.take(24)
    }
}

object SubtitleIdentity {
    fun title(c: Channel)=HomeRecommendations.title(c).replace(Regex("(?i)\\b(4k|uhd|fhd|hd|hevc|x26[45])\\b"),"").trim()
    fun normalize(s: String)=s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun episode(c: Channel): Pair<Int,Int>? = Regex("(?i)S(\\d+)\\s*[·. -]*E(\\d+)").find(c.name)?.let {it.groupValues[1].toInt() to it.groupValues[2].toInt()}
    fun query(c: Channel)=if(c.kind==Kind.EPISODE) HomeRecommendations.title(c.copy(name=c.group)) else title(c)
    fun matches(c: Channel,m: SubtitleMatch): Boolean {
        if(m.language !in listOf("en","eng"))return false
        val ep=episode(c)
        if(c.kind==Kind.EPISODE) return ep!=null && ep.first==m.season && ep.second==m.episode && normalize(query(c))==normalize(m.parentTitle)
        return normalize(title(c))==normalize(m.title) && c.year.take(4).toIntOrNull()!=null && c.year.take(4).toIntOrNull()==m.year
    }
}

object SubtitleFiles {
    const val MAX_BYTES=3*1024*1024
    fun read(input: InputStream): String {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=MAX_BYTES){"Subtitle files must be under 3 MB"};out.write(buffer,0,count)}
        val bytes=out.toByteArray()
        val text=when {
            bytes.size>=2 && bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte() -> String(bytes,Charsets.UTF_16LE)
            bytes.size>=2 && bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte() -> String(bytes,Charsets.UTF_16BE)
            else -> String(bytes,Charsets.UTF_8)
        }.trimStart('\uFEFF')
        mime(text);return text
    }
    fun mime(text: String): String = when {
        text.trimStart().startsWith("WEBVTT") -> "text/vtt"
        Regex("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(text) -> "application/x-subrip"
        else -> error("Choose a valid SRT or WebVTT subtitle file")
    }
    fun download(address: String): String {
        val url=Sources.url(address)
        Sources.http.newCall(Request.Builder().url(url).build()).execute().use {response->
            require(response.isSuccessful){"Subtitle download failed. Check the link."}
            return response.body?.byteStream()?.use {read(it)}?:error("Empty subtitle file")
        }
    }
}

data class SubtitleMatch(val fileId: Int,val label: String,val language: String,val downloads: Int,val captions: Boolean,val title: String="",val year: Int=0,val parentTitle: String="",val season: Int=0,val episode: Int=0)
/** Optional user-owned API key. Never reuse IPTV credentials or attach the key to file downloads. */
class OpenSubtitles(private val endpoint: String="https://api.opensubtitles.com/api/v1",private val client: OkHttpClient=Sources.http.newBuilder().followRedirects(false).followSslRedirects(false).build()) {
    private fun request(path: String,key: String,body: JSONObject?=null): JSONObject {
        require(key.isNotBlank()) {"Enter your OpenSubtitles API key to search"}
        val b=Request.Builder().url(endpoint+path).header("Api-Key",key.trim()).header("User-Agent","EzanoTV v0.3.0").header("Accept","application/json")
        if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(b.build()).execute().use {response->
            when(response.code){401,403->error("OpenSubtitles rejected this API key. Check it in your account.");406,429->error("OpenSubtitles download or request limit reached. Try again later.")}
            require(response.isSuccessful){"OpenSubtitles is unavailable. Try again later."}
            val source=response.body?.source()?:error("Empty subtitle response")
            require(!source.request(2L*1024*1024+1)){"Subtitle response was too large"}
            return JSONObject(source.readUtf8())
        }
    }
    fun search(query: String,language: String,key: String,season: Int?=null,episode: Int?=null): List<SubtitleMatch> {
        require(query.isNotBlank()) {"Enter a movie or episode title"}
        val builder="https://example.test/".toHttpUrl().newBuilder().addQueryParameter("languages",language.trim().ifBlank {"en"}).addQueryParameter("order_by","download_count").addQueryParameter("query",query.trim())
        season?.let {builder.addQueryParameter("season_number",it.toString())};episode?.let {builder.addQueryParameter("episode_number",it.toString())}
        val params=builder.build().encodedQuery
        val data=request("/subtitles?$params",key).optJSONArray("data")?:return emptyList()
        return (0 until data.length()).flatMap {i->val a=data.optJSONObject(i)?.optJSONObject("attributes")?:return@flatMap emptyList();val files=a.optJSONArray("files")?:return@flatMap emptyList();(0 until files.length()).mapNotNull {j->val f=files.optJSONObject(j)?:return@mapNotNull null;val id=f.optInt("file_id");if(id<=0)null else SubtitleMatch(id,a.optString("release",f.optString("file_name","Subtitle")),a.optString("language"),a.optInt("download_count"),a.optBoolean("hearing_impaired"),a.optJSONObject("feature_details")?.optString("title").orEmpty(),a.optJSONObject("feature_details")?.optInt("year")?:0,a.optJSONObject("feature_details")?.optString("parent_title").orEmpty(),a.optJSONObject("feature_details")?.optInt("season_number")?:0,a.optJSONObject("feature_details")?.optInt("episode_number")?:0)}}.take(30)
    }
    fun downloadUrl(match: SubtitleMatch,key: String): String {
        val link=request("/download",key,JSONObject().put("file_id",match.fileId).put("sub_format","srt")).getString("link")
        require(link.toHttpUrl().isHttps) {"Subtitle service returned an insecure link"};return link
    }
}
