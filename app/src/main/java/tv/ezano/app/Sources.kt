package tv.ezano.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.io.InputStreamReader
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

object Sources {
    data class Catalog(val channels: List<Channel>,val warnings: List<String> = emptyList(),val failedKinds: Set<Kind> = emptySet())
    data class GuideResult(val programs: List<Program>, val complete: Boolean, val warning: String = "")
    val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(true).build()
    private val guideHttp=http.newBuilder().callTimeout(180,TimeUnit.SECONDS).build()
    fun url(s: String): String { val u=s.trim().toHttpUrl(); require(u.scheme in listOf("http","https")) { "Use an HTTP or HTTPS address" }; return u.toString().trimEnd('/') }
    // Bounded reads also support chunked responses, where content length is unknown.
    fun read(url: String, headers: Map<String,String> = emptyMap()): String {
        val b=Request.Builder().url(url).header("User-Agent","EzanoTV/0.1"); headers.forEach { (k,v)->b.header(k,v) }
        http.newCall(b.build()).execute().use { r->require(r.isSuccessful) { "HTTP ${r.code}" }; val input=r.body?.byteStream()?:error("Empty response"); val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(32768); while(true) { val n=input.read(buf); if(n<0) break; require(out.size()+n<=16*1024*1024) { "Response is too large" }; out.write(buf,0,n) }; return out.toString("UTF-8") }
    }
    fun friendlyError(e: Exception): String = when { e.message?.contains("401")==true || e.message?.contains("403")==true -> "The provider rejected the login. Check the server, username and password."; e is java.net.UnknownHostException -> "The provider could not be found. Check its server address and your connection."; e is java.net.SocketTimeoutException -> "The provider took too long to respond. Try again."; e is javax.net.ssl.SSLException -> "The provider's secure connection could not be verified."; else -> "Could not load this source. Check the address, login, and provider availability." }
    fun serverUrl(raw: String): String {
        val parsed=url(raw).toHttpUrl(); val b=parsed.newBuilder().query(null).fragment(null)
        if(parsed.pathSegments.lastOrNull()?.lowercase() in listOf("player_api.php","get.php","xmltv.php")) b.removePathSegment(parsed.pathSegments.lastIndex)
        return b.build().toString().trimEnd('/')
    }
    private fun api(p: Provider, action: String = "", extras: Map<String,String> = emptyMap()): String { val b=(serverUrl(p.url)+"/player_api.php").toHttpUrl().newBuilder().addQueryParameter("username",p.user).addQueryParameter("password",p.password); if(action.isNotBlank()) b.addQueryParameter("action",action); extras.forEach { (k,v)->b.addQueryParameter(k,v) }; return b.build().toString() }
    fun catalog(p: Provider): List<Channel> = importCatalog(p).channels
    fun importCatalog(p: Provider,progress: (String)->Unit = {}): Catalog = when(p.type) {
        "m3u" -> http.newCall(Request.Builder().url(url(p.url)).header("User-Agent","EzanoTV/0.2").build()).execute().use { response ->
            require(response.isSuccessful) {"HTTP ${response.code}"}
            Catalog(parseM3u(response.body?.charStream()?:error("Empty playlist"),p,progress))
        }
        "stalker" -> Catalog(stalkerCatalog(p).distinctBy {it.id})
        else -> xtream(p,progress)
    }
    /** Provider arrays can be hundreds of MB. Decode a single entry at a time. */
    private fun arrayRequest(address: String,consume: (JSONObject)->Boolean) {
        http.newCall(Request.Builder().url(address).header("User-Agent","EzanoTV/0.1.1").build()).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            JsonReader(response.body?.charStream()?:error("Empty response")).use { reader ->
                if(reader.peek()==JsonToken.NULL) {reader.nextNull();return}
                require(reader.peek()==JsonToken.BEGIN_ARRAY) { "Invalid provider catalog" }
                reader.beginArray()
                while(reader.hasNext()) {
                    if(reader.peek()!=JsonToken.BEGIN_OBJECT) {reader.skipValue();continue}
                    val item=JSONObject(JsonParser.parseReader(reader).toString())
                    if(!consume(item)) return
                }
                reader.endArray()
            }
        }
    }
    private fun xtream(p: Provider,progress: (String)->Unit): Catalog {
        progress("Checking your login…")
        val auth=JSONObject(read(api(p))).optJSONObject("user_info")?:error("Invalid provider")
        require(auth.optInt("auth")==1 && auth.optString("status","Active").equals("Active",true)) { "HTTP 401" }
        val result=CompactCatalog.Builder(); val warnings=mutableListOf<String>();val failed=mutableSetOf<Kind>();var completed=0
        listOf(Triple("live",Kind.LIVE,"ts"),Triple("vod",Kind.MOVIE,"mp4"),Triple("series",Kind.SERIES,"")).forEach { (type,kind,defaultExt)->
            val label=when(kind) {Kind.LIVE->"live channels";Kind.MOVIE->"movies";else->"series"}
            progress("Loading $label…")
            val names=HashMap<String,String>()
            try {arrayRequest(api(p,"get_${type}_categories")) {c->names[c.optString("category_id")]=c.optString("category_name","General").take(160);true}}
            catch(_:Exception) {warnings+="Some $label categories were unavailable."}
            try { arrayRequest(api(p,if(type=="series") "get_series" else "get_${type}_streams")) {c->
                val id=c.optString(if(kind==Kind.SERIES) "series_id" else "stream_id").clean()
                if(id.isBlank() || id.length>100) return@arrayRequest true
                val ext=c.optString("container_extension",defaultExt).clean().ifBlank {defaultExt}.take(12)
                val stream=if(kind==Kind.SERIES) "" else streamUrl(p,if(kind==Kind.MOVIE) "movie" else "live",id,ext)
                val key="${p.id}:$type:$id"
                result.add(Channel(key,p.id,id,c.optString("name","Untitled").clean().take(256).ifBlank {"Untitled"},stream,kind,names[c.optString("category_id")]?:"General",c.optString(if(kind==Kind.SERIES) "cover" else "stream_icon").clean().take(2048),c.optString("epg_channel_id").clean().trim().take(256),c.optString("plot").clean().take(2048),c.optJSONArray("backdrop_path")?.optString(0).orEmpty().clean().take(2048),c.optString("year",c.optString("releaseDate").take(4)).clean().take(4),c.optString("rating").clean().take(12),if(c.optInt("tv_archive")==1) c.optInt("tv_archive_duration").coerceIn(0,365) else 0,
                    language=metadata(c,"original_language","language"),country=metadata(c,"origin_country","country"),certification=metadata(c,"certification","content_rating","age_rating","mpaa"),genre=metadata(c,"genre"),popularity=c.optDouble("popularity",0.0).takeIf {it.isFinite()}?:0.0))
                if(result.size%500==0) progress("Loading $label · ${result.size} items")
                true
            };completed++ } catch(_:Exception) {
                failed+=kind;warnings+="The provider's $label list is unavailable. Refresh to retry."
            }
        }
        require(completed>0 || result.size>0) { "No catalogs available" }
        return Catalog(result.build(),warnings.distinct(),failed)
    }
    private fun metadata(c: JSONObject,vararg keys: String): String = keys.asSequence().map { key ->
        val value=c.opt(key)
        when(value) { is JSONArray -> (0 until value.length()).joinToString(",") {value.optString(it)}; is String -> value.clean(); else -> "" }
    }.firstOrNull {it.isNotBlank()}.orEmpty().take(160)
    private fun streamUrl(p: Provider,type: String,id: String,ext: String)=(serverUrl(p.url)+"/").toHttpUrl().newBuilder().addPathSegment(type).addPathSegment(p.user).addPathSegment(p.password).addPathSegment("$id.$ext").build().toString()
    fun details(p: Provider,c: Channel): Channel {
        if(p.type!="xtream" || c.kind!=Kind.MOVIE) return c
        val root=JSONObject(read(api(p,"get_vod_info",mapOf("vod_id" to c.sourceId))));val info=root.optJSONObject("info")?:JSONObject()
        return c.copy(description=info.optString("plot",c.description),backdrop=info.optJSONArray("backdrop_path")?.optString(0).orEmpty().ifBlank { c.backdrop },year=info.optString("releasedate",c.year).take(4),rating=info.optString("rating",c.rating),language=metadata(info,"original_language","language").ifBlank {c.language},country=metadata(info,"origin_country","country").ifBlank {c.country},certification=metadata(info,"certification","content_rating","age_rating","mpaa").ifBlank {c.certification},genre=metadata(info,"genre").ifBlank {c.genre},subtitleTracks=(c.subtitleTracks+ProviderSubtitles.parse(root,p.url)).distinctBy {it.url})
    }
    fun episodes(p: Provider,c: Channel): List<Channel> {
        require(p.type=="xtream") { "Series episodes require Xtream Codes" }
        val root=JSONObject(read(api(p,"get_series_info",mapOf("series_id" to c.sourceId)))); val seasons=root.optJSONObject("episodes")?:return emptyList()
        return seasons.keys().asSequence().toList().sortedBy { it.toIntOrNull()?:0 }.flatMap { season-> val a=seasons.getJSONArray(season); (0 until a.length()).map { i->val e=a.getJSONObject(i); val id=e.getString("id"); val info=e.optJSONObject("info")?:JSONObject(); Channel("${p.id}:episode:$id",p.id,id,"S$season · E${e.optInt("episode_num",i+1)}  ${e.optString("title",c.name)}",streamUrl(p,"series",id,e.optString("container_extension","mp4")),Kind.EPISODE,c.name,info.optString("movie_image").takeIf {it.isNotBlank() && it!="null"}?:c.logo,description=info.optString("plot",c.description),backdrop=c.backdrop,year=c.year,language=c.language,country=c.country,certification=c.certification,genre=c.genre,seriesId=c.id,subtitleTracks=ProviderSubtitles.parse(e,p.url)) } }.sortedWith(compareBy<Channel> {SubtitleIdentity.episode(it)?.first?:0}.thenBy {SubtitleIdentity.episode(it)?.second?:0})
    }
    private fun String.clean()=if(this=="null") "" else this
    fun parseM3u(raw: String,p: Provider): List<Channel> {
        return parseM3u(raw.reader(),p)
    }
    /** One line and one compact page at a time, including chunked/gzipped large playlists. */
    fun parseM3u(reader: Reader,p: Provider,progress: (String)->Unit = {}): List<Channel> {
        val out=CompactCatalog.Builder()
        val attributes=Regex("([\\w-]+)=[\"']([^\"']*)[\"']")
        val movieFile=Regex(".*\\.(mp4|mkv|avi|mov|webm)$",RegexOption.IGNORE_CASE)
        var attrs=emptyMap<String,String>();var title="";var group="General"
        var headers=mutableMapOf<String,String>();var index=0;var seenHeader=false
        reader.buffered().lineSequence().forEach { line0 ->
            val line=line0.trim().trimStart('\uFEFF')
            if(!seenHeader) {
                if(line.isNotBlank()) {require(line.startsWith("#EXTM3U")) {"Not an M3U playlist"};seenHeader=true}
            } else when {
                line.startsWith("#EXTINF:") -> {
                    attrs=attributes.findAll(line).associate {it.groupValues[1].lowercase() to it.groupValues[2]}
                    var quoted: Char?=null
                    val comma=line.indexOfFirst {ch->if(ch=='\"' || ch=='\'') quoted=if(quoted==ch) null else if(quoted==null) ch else quoted;ch==',' && quoted==null}
                    title=if(comma>=0) line.substring(comma+1).trim().take(256) else "Channel ${index+1}"
                    group=attrs["group-title"]?.take(160)?:"General";headers=mutableMapOf()
                }
                line.startsWith("#EXTGRP:") -> group=line.substringAfter(':').take(160)
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> headers["User-Agent"]=line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=") -> headers["Referer"]=line.substringAfter('=')
                line.isNotBlank() && !line.startsWith('#') -> {
                    val stream=line.substringBefore('|')
                    if(runCatching {stream.toHttpUrl()}.isSuccess) {
                        if('|' in line) line.substringAfter('|').split('&').forEach {part->
                            val key=part.substringBefore('=');val value=runCatching {java.net.URLDecoder.decode(part.substringAfter('=',""),"UTF-8")}.getOrDefault("")
                            when(key.lowercase()) {"user-agent"->headers["User-Agent"]=value;"referer","referrer"->headers["Referer"]=value;"origin"->headers["Origin"]=value;"cookie"->headers["Cookie"]=value}
                        }
                        val kind=if(attrs["tvg-type"] in listOf("movie","vod") || attrs["type"] in listOf("movie","vod") || movieFile.matches(stream.substringBefore('?')) || "/movie/" in stream) Kind.MOVIE else Kind.LIVE
                        val id=java.util.UUID.nameUUIDFromBytes((stream+"|"+title).toByteArray()).toString()
                        out.add(Channel("${p.id}:m3u:$id",p.id,(++index).toString(),title.ifBlank {"Channel $index"},stream,kind,group,attrs["tvg-logo"].orEmpty().take(2048),attrs["tvg-id"].orEmpty().take(256),
                            catchupDays=attrs["catchup-days"]?.toIntOrNull()?:0,catchupSource=attrs["catchup-source"].orEmpty(),catchupMode=attrs["catchup"].orEmpty(),headers=headers.toMap(),
                            language=attrs["tvg-language"].orEmpty(),country=attrs["tvg-country"].orEmpty(),certification=attrs["content-rating"].orEmpty(),genre=attrs["genre"].orEmpty(),year=attrs["year"].orEmpty(),rating=attrs["rating"].orEmpty()))
                        if(out.size%1000==0) progress("Loading playlist · ${out.size} items")
                    }
                    attrs=emptyMap();title="";group="General";headers=mutableMapOf()
                }
            }
        }
        require(seenHeader) {"Not an M3U playlist"}
        return out.build()
    }
    private fun playlistGuide(p: Provider): String {
        http.newCall(Request.Builder().url(p.url).header("User-Agent","EzanoTV/0.2").build()).execute().use {response->
            require(response.isSuccessful) {"HTTP ${response.code}"}
            val header=response.body?.charStream()?.buffered()?.readLine().orEmpty()
            return Regex("(?:url-tvg|x-tvg-url)=[\"']([^\"']+)[\"']").find(header)?.groupValues?.get(1)?.substringBefore(',').orEmpty()
        }
    }
    fun guide(p: Provider,channels: List<Channel>,days: Int): List<Program> {
        var epg=p.epg
        if(epg.isBlank() && p.type=="xtream") epg=(serverUrl(p.url)+"/xmltv.php").toHttpUrl().newBuilder().addQueryParameter("username",p.user).addQueryParameter("password",p.password).build().toString()
        if(epg.isBlank() && p.type=="m3u") epg=playlistGuide(p)
        if(epg.isBlank()) return emptyList()
        http.newCall(Request.Builder().url(epg).header("User-Agent","EzanoTV/0.1.1").build()).execute().use { response->require(response.isSuccessful); val stream=response.body!!.byteStream().buffered(); stream.mark(2); val a=stream.read(); val b=stream.read(); stream.reset(); val source=if(a==0x1f && b==0x8b) GZIPInputStream(stream) else stream; return parseXmltv(source.reader(),channels,System.currentTimeMillis()-days*86400000L,System.currentTimeMillis()+7*86400000L) }
    }
    /** A partial guide is useful when a provider closes a truncated XML response after valid entries. */
    fun guideResult(p: Provider,channels: List<Channel>,days: Int): GuideResult {
        val cutoff=System.currentTimeMillis()-days*86400000L
        val ceiling=System.currentTimeMillis()+7*86400000L
        return try {
            var epg=p.epg
            if(epg.isBlank() && p.type=="xtream") epg=(serverUrl(p.url)+"/xmltv.php").toHttpUrl().newBuilder().addQueryParameter("username",p.user).addQueryParameter("password",p.password).build().toString()
            if(epg.isBlank() && p.type=="m3u") epg=playlistGuide(p)
            if(epg.isBlank()) return GuideResult(emptyList(),true)
            guideHttp.newCall(Request.Builder().url(epg).header("User-Agent","EzanoTV/0.1.1").build()).execute().use { response ->
                if(!response.isSuccessful) return GuideResult(emptyList(),false,"The guide could not be fully loaded. Some listings may be missing.")
                val input=response.body?.byteStream() ?: return GuideResult(emptyList(),false,"The guide could not be fully loaded. Some listings may be missing.")
                val stream=input.buffered(); stream.mark(2); val a=stream.read(); val b=stream.read(); stream.reset()
                val source=if(a==0x1f && b==0x8b) GZIPInputStream(stream) else stream
                parseXmltvResult(InputStreamReader(source, Charsets.UTF_8),channels,cutoff,ceiling)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { GuideResult(emptyList(),false,"The guide could not be fully loaded. Some listings may be missing.") }
    }
    fun xmlTime(s: String): Long { val parts=s.trim().split(Regex("\\s+")); val digits=parts[0].padEnd(14,'0').take(14); val date=LocalDateTime.parse(digits,DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); val offset=if(parts.size>1) ZoneOffset.of(parts[1]) else ZoneOffset.UTC; return date.toInstant(offset).toEpochMilli() }
    fun parseXmltv(reader: Reader,channels: List<Channel>,cutoff: Long,ceiling: Long=Long.MAX_VALUE): List<Program> {
        val map=channels.selectCatalog {it.kind==Kind.LIVE}.filter {it.epgId.isNotBlank()}.groupBy {it.epgId.trim()}; val out=CompactGuide.Builder(); val factory=XmlPullParserFactory.newInstance(); factory.isNamespaceAware=false; val x=factory.newPullParser(); runCatching { x.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl",false) }; x.setInput(reader)
        var event=x.eventType; var ids=emptyList<Channel>(); var start=0L; var end=0L; var title=""; var desc=""
        while(event!=XmlPullParser.END_DOCUMENT) {
            if(event==XmlPullParser.DOCDECL) error("DTD declarations are not supported")
            if(event==XmlPullParser.START_TAG) when(x.name) { "programme"->{ids=map[x.getAttributeValue(null,"channel")?.trim()]?:emptyList(); start=runCatching { xmlTime(x.getAttributeValue(null,"start")) }.getOrDefault(0); end=runCatching { xmlTime(x.getAttributeValue(null,"stop")) }.getOrDefault(0);if(end<=cutoff || start>=ceiling) ids=emptyList();title="";desc=""}; "title"->if(ids.isNotEmpty() && title.isBlank()) title=x.nextText().take(512); "desc"->if(ids.isNotEmpty() && desc.isBlank()) desc=x.nextText().take(2048) }
            if(event==XmlPullParser.END_TAG && x.name=="programme" && end>cutoff && end>start && start<ceiling) ids.forEach {val key="${it.id}:$start";out.add(Program(key,it.id,title.ifBlank { "Untitled program" },start,end,desc))}
            event=x.next()
        }
        return out.build()
    }
    fun parseXmltvResult(reader: Reader,channels: List<Channel>,cutoff: Long,ceiling: Long=Long.MAX_VALUE): GuideResult {
        val map=channels.selectCatalog {it.kind==Kind.LIVE}.filter {it.epgId.isNotBlank()}.groupBy {it.epgId.trim()}
        val out=CompactGuide.Builder(); val factory=XmlPullParserFactory.newInstance(); factory.isNamespaceAware=false; val x=factory.newPullParser()
        runCatching { x.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl",false) }; x.setInput(reader)
        var event=x.eventType; var ids=emptyList<Channel>(); var start=0L; var end=0L; var title=""; var desc=""; var complete=false
        try {
            while(event!=XmlPullParser.END_DOCUMENT) {
                if(event==XmlPullParser.DOCDECL) error("DTD declarations are not supported")
                if(event==XmlPullParser.START_TAG) when(x.name) {
                    "programme"->{ids=map[x.getAttributeValue(null,"channel")?.trim()]?:emptyList(); start=runCatching { xmlTime(x.getAttributeValue(null,"start")) }.getOrDefault(0); end=runCatching { xmlTime(x.getAttributeValue(null,"stop")) }.getOrDefault(0);if(end<=cutoff || start>=ceiling) ids=emptyList();title="";desc=""}
                    "title"->if(ids.isNotEmpty() && title.isBlank()) title=x.nextText().take(512)
                    "desc"->if(ids.isNotEmpty() && desc.isBlank()) desc=x.nextText().take(2048)
                }
                if(event==XmlPullParser.END_TAG && x.name=="programme" && end>cutoff && end>start && start<ceiling) ids.forEach { val key="${it.id}:$start"; out.add(Program(key,it.id,title.ifBlank { "Untitled program" },start,end,desc)) }
                event=x.next()
            }
            if(event==XmlPullParser.END_DOCUMENT) complete=true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { complete=false }
        return GuideResult(out.build(),complete,if(complete) "" else "The guide could not be fully loaded. Some listings may be missing.")
    }
    fun catchup(p: Provider,c: Channel,program: Program): Channel {
        require(c.catchupDays>0 && program.start<System.currentTimeMillis() && program.start>=System.currentTimeMillis()-c.catchupDays*86400000L) { "Outside the provider's catch-up window" }
        val u=if(p.type=="xtream") { val date=Instant.ofEpochMilli(program.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")); val minutes=((program.end-program.start)/60000).coerceAtLeast(1); (url(p.url)+"/").toHttpUrl().newBuilder().addPathSegment("timeshift").addPathSegment(p.user).addPathSegment(p.password).addPathSegment(minutes.toString()).addPathSegment(date).addPathSegment("${c.sourceId}.ts").build().toString() } else { require(c.catchupSource.isNotBlank()) { "No catch-up URL supplied" }; val template=c.catchupSource.replace("{utc}",(program.start/1000).toString()).replace("{utcend}",(program.end/1000).toString()).replace("{duration}",((program.end-program.start)/1000).toString()).replace("{lutc}",(System.currentTimeMillis()/1000).toString()); if(c.catchupMode=="append") c.url+template else c.url.toHttpUrl().resolve(template)?.toString()?:error("Invalid catch-up URL") }
        return c.copy(id=c.id+":catchup:"+program.start,name=program.title,url=u,kind=Kind.EPISODE)
    }
    private fun portal(p: Provider,token: String,type: String,action: String,extra: Map<String,String> = emptyMap()): JSONObject {
        val endpoint=if(p.url.endsWith(".php")) url(p.url) else url(p.url).removeSuffix("/c")+"/server/load.php"
        val b=endpoint.toHttpUrl().newBuilder().addQueryParameter("type",type).addQueryParameter("action",action).addQueryParameter("JsHttpRequest","1-xml"); extra.forEach { (k,v)->b.addQueryParameter(k,v) }
        return JSONObject(read(b.build().toString(),mapOf("Cookie" to "mac=${p.mac}; stb_lang=en; timezone=UTC", "Authorization" to "Bearer $token", "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 4 rev: 1812 Mobile Safari/533.3", "X-User-Agent" to "Model: MAG250; Link: WiFi")))
    }
    private fun token(p: Provider)=portal(p,"","stb","handshake").getJSONObject("js").getString("token")
    private fun stalkerCatalog(p: Provider): List<Channel> { val token=token(p); portal(p,token,"stb","get_profile"); val root=portal(p,token,"itv","get_all_channels").getJSONObject("js"); val data=root.getJSONArray("data"); return (0 until data.length()).map { i->val c=data.getJSONObject(i); val id=c.getString("id"); Channel("${p.id}:stalker:$id",p.id,id,c.getString("name"),c.optString("cmd"),Kind.LIVE,c.optString("tv_genre_id","Live TV"),c.optString("logo"),c.optString("xmltv_id")) } }
    fun resolve(p: Provider?,c: Channel): Channel { if(p?.type!="stalker") return c; val cmd=portal(p,token(p),"itv","create_link",mapOf("cmd" to c.url,"series" to "0","forced_storage" to "0","disable_ad" to "0","download" to "0")).getJSONObject("js").getString("cmd"); val u=Regex("https?://\\S+").find(cmd)?.value?:error("Portal returned no stream"); return c.copy(url=u) }
}
