package tv.ezano.app

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Binary sections of the split library. Pages are already compressed, so they are copied as-is;
 * only the resident title labels are compressed together. Parsing never builds JSON for the catalog.
 */
internal object VaultFormat {
    private const val CATALOG=0x455A4331 // EZC1
    private const val GUIDE=0x455A4731   // EZG1
    private const val HOME=0x455A4831    // EZH1
    private const val MAX_STRING=1 shl 20
    private const val MAX_BLOB=64 shl 20

    private fun DataOutputStream.text(value: String) {
        val bytes=value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size);write(bytes)
    }
    private fun DataInputStream.text(): String {
        val length=readInt();require(length in 0..MAX_STRING) {"Invalid saved text"}
        val bytes=ByteArray(length);readFully(bytes);return String(bytes,Charsets.UTF_8)
    }
    private fun DataOutputStream.blob(bytes: ByteArray) {writeInt(bytes.size);write(bytes)}
    private fun DataInputStream.blob(): ByteArray {
        val length=readInt();require(length in 0..MAX_BLOB) {"Invalid saved block"}
        return ByteArray(length).also {readFully(it)}
    }

    fun asCatalog(channels: List<Channel>): CompactCatalog = channels as? CompactCatalog ?: CompactCatalog.Builder().apply {addAll(channels)}.build()
    fun asGuide(programs: List<Program>): CompactGuide = programs as? CompactGuide ?: CompactGuide.Builder().apply {addAll(programs)}.build()

    fun writeCatalog(output: DataOutputStream, catalog: CompactCatalog) {
        output.writeInt(CATALOG);output.writeInt(CompactCatalog.PAGE_SIZE);output.writeInt(catalog.size)
        val labels=ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(labels,65536)).use {out->
            val strings=HashMap<String,Int>()
            // Provider and group names repeat across tens of thousands of titles; store each once.
            fun shared(value: String){val known=strings[value];if(known!=null) out.writeInt(known) else {out.writeInt(-1);out.text(value);strings[value]=strings.size}}
            catalog.storedLabels().forEach {label->
                out.text(label.id);shared(label.provider);out.text(label.name);out.writeByte(label.kind.ordinal);shared(label.group)
                out.writeBoolean(label.familyEligible);out.writeDouble(label.familyScore);out.writeByte(label.englishRank)
                shared(label.genre);out.text(label.year);out.writeDouble(label.rating);out.writeDouble(label.popularity)
            }
        }
        output.blob(labels.toByteArray())
        val pages=catalog.storedPages()
        output.writeInt(pages.size)
        pages.forEach {page->output.writeInt(page.rows);output.blob(page.bytes)}
    }

    fun readCatalog(input: DataInputStream): CompactCatalog {
        require(input.readInt()==CATALOG) {"Unsupported saved catalog"}
        val pageSize=input.readInt();val size=input.readInt()
        require(size>=0) {"Invalid saved catalog size"}
        val kinds=Kind.values()
        val labels=ArrayList<CatalogLabel>(size)
        DataInputStream(GZIPInputStream(input.blob().inputStream(),65536).buffered(65536)).use {data->
            val strings=ArrayList<String>()
            fun shared(): String {val index=data.readInt();return if(index<0) data.text().also {strings+=it} else strings.getOrNull(index)?:error("Invalid shared text")}
            repeat(size) {
                val id=data.text();val provider=shared();val name=data.text();val kind=kinds.getOrNull(data.readUnsignedByte())?:error("Invalid kind")
                val group=shared();val eligible=data.readBoolean();val score=data.readDouble();val english=data.readUnsignedByte().coerceIn(0,3)
                labels+=CatalogLabel(id,provider,name,kind,group,eligible,score,english,shared(),data.text(),data.readDouble(),data.readDouble())
            }
        }
        val pageCount=input.readInt();require(pageCount in 0..size) {"Invalid saved catalog pages"}
        val pages=ArrayList<StoredCatalogPage>(pageCount)
        repeat(pageCount) {val rows=input.readInt();pages+=StoredCatalogPage(rows,input.blob())}
        return CompactCatalog.fromStorage(pageSize,size,pages,labels)
    }

    fun writeGuide(output: DataOutputStream, guide: CompactGuide) {
        output.writeInt(GUIDE);output.writeInt(guide.size)
        val pages=guide.storedPages();output.writeInt(pages.size);pages.forEach {output.blob(it)}
        val index=guide.storedIndex();output.writeInt(index.size)
        index.forEach {(id,rows)->output.text(id);output.writeInt(rows.size);rows.forEach {output.writeInt(it)}}
    }

    fun readGuide(input: DataInputStream): CompactGuide {
        require(input.readInt()==GUIDE) {"Unsupported saved guide"}
        val size=input.readInt();require(size>=0) {"Invalid saved guide size"}
        val pageCount=input.readInt();require(pageCount in 0..size) {"Invalid saved guide pages"}
        val pages=ArrayList<ByteArray>(pageCount);repeat(pageCount) {pages+=input.blob()}
        val channels=input.readInt();require(channels in 0..size) {"Invalid saved guide index"}
        val index=HashMap<String,IntArray>(channels*2)
        repeat(channels) {
            val id=input.text();val count=input.readInt();require(count in 0..size) {"Invalid saved guide rows"}
            index[id]=IntArray(count) {input.readInt()}
        }
        return CompactGuide.fromStorage(size,pages,index)
    }

    /** Last computed Home shelves, shown immediately on the next launch while the catalog opens. */
    fun writeHome(output: DataOutputStream, home: HomeSnapshot) {
        output.writeInt(HOME)
        fun list(items: List<Channel>)=JSONArray(items.map {with(Json) {it.json()}})
        val json=JSONObject().put("popular",list(home.popular)).put("shows",list(home.shows)).put("classics",list(home.classics)).put("more",list(home.more))
        output.text(json.toString())
    }

    fun readHome(input: DataInputStream): HomeSnapshot {
        require(input.readInt()==HOME) {"Unsupported Home snapshot"}
        val json=JSONObject(input.text())
        fun list(key: String)=Json.array(json.optJSONArray(key)).mapNotNull {it as? JSONObject}.map(Json::channel)
        return HomeSnapshot(list("popular"),list("shows"),list("classics"),list("more"))
    }
}

/** Home shelves; also saved so the next launch shows them instantly while the catalog opens. */
data class HomeSnapshot(val popular: List<Channel> = emptyList(), val shows: List<Channel> = emptyList(),
    val classics: List<Channel> = emptyList(), val more: List<Channel> = emptyList()) {
    val isEmpty get() = popular.isEmpty() && shows.isEmpty() && classics.isEmpty() && more.isEmpty()
    val hero get() = popular.firstOrNull() ?: classics.firstOrNull() ?: shows.firstOrNull() ?: more.firstOrNull()
    fun filter(keep: (Channel) -> Boolean) = HomeSnapshot(popular.filter(keep), shows.filter(keep), classics.filter(keep), more.filter(keep))
}
