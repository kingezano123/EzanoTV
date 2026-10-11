package tv.ezano.app

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONObject
import java.io.Reader
import java.io.Writer
import java.util.Base64

/** Keep only one JSON record in memory, rather than a second copy of the entire library. */
object LibraryStreams {
    fun write(s: Library, output: Writer) {
        val compact = s.channels as? CompactCatalog
        val guide=s.programs as? CompactGuide
        val metadata=JSONObject(Json.encode(s.copy(channels=emptyList(),programs=emptyList())))
        val writer=JsonWriter(output)
        writer.beginObject()
        metadata.keys().forEach { key ->
            if ((key == "channels" && compact != null) || (key=="programs" && guide!=null)) return@forEach
            writer.name(key)
            when(key) {
                "channels" -> { writer.beginArray(); s.channels.forEach { writer.jsonValue(with(Json) { it.json().toString() }) }; writer.endArray() }
                "programs" -> { writer.beginArray(); s.programs.forEach { writer.jsonValue(with(Json) { it.json().toString() }) }; writer.endArray() }
                else -> writer.jsonValue(metadata.get(key).let { if(it is String) JSONObject.quote(it) else it.toString() })
            }
        }
        if (compact != null) {
            writer.name("compactCatalog")
            writer.beginObject()
            writer.name("version").value(1)
            writer.name("pageSize").value(CompactCatalog.PAGE_SIZE)
            writer.name("size").value(compact.size)
            writer.name("pages").beginArray()
            compact.storedPages().forEach { page ->
                writer.beginObject()
                writer.name("rows").value(page.rows)
                writer.name("data").value(Base64.getEncoder().encodeToString(page.bytes))
                writer.endObject()
            }
            writer.endArray()
            writer.name("labels").beginArray()
            compact.storedLabels().forEach { label ->
                writer.beginObject()
                writer.name("id").value(label.id)
                writer.name("provider").value(label.provider)
                writer.name("name").value(label.name)
                writer.name("kind").value(label.kind.name)
                writer.name("group").value(label.group)
                writer.name("familyEligible").value(label.familyEligible)
                writer.name("familyScore").value(label.familyScore)
                writer.name("englishRank").value(label.englishRank)
                writer.name("genre").value(label.genre)
                writer.name("year").value(label.year)
                writer.name("rating").value(label.rating)
                writer.name("popularity").value(label.popularity)
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        if(guide!=null){
            writer.name("compactGuide").beginObject();writer.name("version").value(1);writer.name("size").value(guide.size)
            writer.name("pages").beginArray();guide.storedPages().forEach {writer.value(Base64.getEncoder().encodeToString(it))};writer.endArray()
            writer.name("index").beginObject();guide.storedIndex().forEach {(id,rows)->writer.name(id).beginArray();rows.forEach {writer.value(it.toLong())};writer.endArray()};writer.endObject();writer.endObject()
        }
        writer.endObject(); writer.flush()
    }
    fun read(input: Reader): Library {
        val reader=JsonReader(input); val metadata=JSONObject(); val channels=CompactCatalog.Builder(); val programs=CompactGuide.Builder()
        var compactCatalog: CompactCatalog? = null
        var legacyChannelsSeen = false
        var compactGuide:CompactGuide?=null;var legacyGuideSeen=false
        reader.beginObject()
        while(reader.hasNext()) {
            val key=reader.nextName()
            if (key == "compactCatalog") {
                require(compactCatalog == null) { "Duplicate compact catalog" }
                require(!legacyChannelsSeen) { "Both compact and legacy channels were provided" }
                compactCatalog = readCompactCatalog(reader)
            } else if(key=="compactGuide"){require(compactGuide==null && !legacyGuideSeen){"Duplicate guide"};compactGuide=readCompactGuide(reader)
            } else if(key=="channels" || key=="programs") {
                if(key=="programs"){require(compactGuide==null){"Duplicate guide"};legacyGuideSeen=true}
                if (key == "channels") {
                    require(compactCatalog == null) { "Both compact and legacy channels were provided" }
                    legacyChannelsSeen = true
                }
                reader.beginArray()
                while(reader.hasNext()) {
                    val item=JSONObject(JsonParser.parseReader(reader).toString())
                    if(key=="channels") channels.add(Json.channel(item))
                    else programs.add(Json.program(item))
                }
                reader.endArray()
            } else metadata.put(key,org.json.JSONTokener(JsonParser.parseReader(reader).toString()).nextValue())
        }
        reader.endObject()
        require(reader.peek()==JsonToken.END_DOCUMENT) { "Unexpected library content" }
        return Json.decode(metadata.toString()).copy(channels=compactCatalog ?: channels.build(),programs=compactGuide ?: programs.build())
    }

    private fun readCompactGuide(reader:JsonReader):CompactGuide {
        var version=0;var size=-1;var pages:List<ByteArray>?=null;var index:Map<String,IntArray>?=null
        reader.beginObject()
        while(reader.hasNext())when(reader.nextName()){
            "version"->version=reader.nextInt()
            "size"->size=reader.nextInt()
            "pages"->{val list=ArrayList<ByteArray>();reader.beginArray();while(reader.hasNext())list+=Base64.getDecoder().decode(reader.nextString());reader.endArray();pages=list}
            "index"->{val map=HashMap<String,IntArray>();reader.beginObject();while(reader.hasNext()){val id=reader.nextName();require(!map.containsKey(id)){"Duplicate guide channel"};val rows=ArrayList<Int>();reader.beginArray();while(reader.hasNext())rows+=reader.nextInt();reader.endArray();map[id]=rows.toIntArray()};reader.endObject();index=map}
            else->reader.skipValue()
        }
        reader.endObject();require(version==1){"Unsupported guide version"}
        return CompactGuide.fromStorage(size,requireNotNull(pages),requireNotNull(index))
    }
    private fun readCompactCatalog(reader: JsonReader): CompactCatalog {
        var version: Int? = null
        var pageSize: Int? = null
        var size: Int? = null
        var pages: List<StoredCatalogPage>? = null
        var labels: List<CatalogLabel>? = null
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "version" -> version = reader.nextInt()
            "pageSize" -> pageSize = reader.nextInt()
            "size" -> size = reader.nextInt()
            "pages" -> {
                val result = ArrayList<StoredCatalogPage>()
                reader.beginArray()
                while (reader.hasNext()) {
                    var rows: Int? = null
                    var bytes: ByteArray? = null
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "rows" -> rows = reader.nextInt()
                        "data" -> bytes = try { Base64.getDecoder().decode(reader.nextString()) }
                            catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid compact catalog page encoding", e) }
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    result += StoredCatalogPage(requireNotNull(rows) { "Missing compact catalog page row count" },
                        requireNotNull(bytes) { "Missing compact catalog page data" })
                }
                reader.endArray()
                pages = result
            }
            "labels" -> {
                val result = ArrayList<CatalogLabel>()
                reader.beginArray()
                while (reader.hasNext()) {
                    var id: String? = null
                    var provider: String? = null
                    var name: String? = null
                    var kind: Kind? = null
                    var group: String? = null
                    var familyEligible: Boolean? = null
                    var familyScore: Double? = null
                    var englishRank: Int? = null
                    var genre = ""
                    var year = ""
                    var rating = 0.0
                    var popularity = 0.0
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "id" -> id = reader.nextString()
                        "provider" -> provider = reader.nextString()
                        "name" -> name = reader.nextString()
                        "kind" -> kind = try { Kind.valueOf(reader.nextString()) }
                            catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid compact catalog channel kind", e) }
                        "group" -> group = reader.nextString()
                        "familyEligible" -> familyEligible = reader.nextBoolean()
                        "familyScore" -> familyScore = reader.nextDouble()
                        "englishRank" -> englishRank = reader.nextInt().coerceIn(0,3)
                        "genre" -> genre = reader.nextString()
                        "year" -> year = reader.nextString()
                        "rating" -> rating = reader.nextDouble()
                        "popularity" -> popularity = reader.nextDouble()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    result += CatalogLabel(
                        requireNotNull(id) { "Missing compact catalog channel id" },
                        requireNotNull(provider) { "Missing compact catalog provider" },
                        requireNotNull(name) { "Missing compact catalog channel name" },
                        requireNotNull(kind) { "Missing compact catalog channel kind" },
                        requireNotNull(group) { "Missing compact catalog channel group" },
                        requireNotNull(familyEligible) { "Missing compact catalog family flag" },
                        requireNotNull(familyScore) { "Missing compact catalog family score" },
                        englishRank ?: if(familyEligible==true) 0 else EnglishContent.rank(name.orEmpty(),group.orEmpty()), genre, year, rating, popularity
                    )
                }
                reader.endArray()
                labels = result
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        require(version == 1) { "Unsupported compact catalog version" }
        return CompactCatalog.fromStorage(requireNotNull(pageSize) { "Missing compact catalog page size" },
            requireNotNull(size) { "Missing compact catalog size" },
            requireNotNull(pages) { "Missing compact catalog pages" },
            requireNotNull(labels) { "Missing compact catalog labels" })
    }
}
