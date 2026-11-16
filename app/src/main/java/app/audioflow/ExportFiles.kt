package app.audioflow

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class MusicFile(val uri: String, val name: String, val size: Long)
internal data class ExportRow(val index: Int, val title: String, val result: String)
internal class ExportFiles(private val context: Context) {
    private val prefs=context.getSharedPreferences("music-export-settings",Context.MODE_PRIVATE)
    var folder: String
        get()=prefs.getString("folder","").orEmpty()
        set(value) { prefs.edit().putString("folder",value).apply { if(folder!=value) putBoolean("auto",false) }.apply() }
    var automatic: Boolean
        get()=prefs.getBoolean("auto",false)
        set(value) { prefs.edit().putBoolean("auto",value).apply() }
    fun blocked(batch: String): String? = if(prefs.getLong("cooldown",0)>System.currentTimeMillis()) "요청 제한으로 잠시 중단했어요. 30분 뒤 다시 시도해 주세요." else prefs.getString("blocked_$batch",null)
    fun block(batch: String, reason: String, limited: Boolean=false) { prefs.edit().putString("blocked_$batch",reason).apply { if(limited) putLong("cooldown",System.currentTimeMillis()+30*60_000) }.apply() }
    private val index=context.getSharedPreferences("music-export-index",Context.MODE_PRIVATE)
    fun existing(id: String): MusicFile? = index.getString(id,null)?.let { raw ->
        runCatching { val json=JSONObject(raw); stat(json.getString("uri")) }.getOrNull()
    }
    fun remember(id: String, uri: Uri) { index.edit().putString(id,JSONObject().put("uri",uri.toString()).toString()).commit() }
    fun stat(raw: String): MusicFile? = context.contentResolver.query(Uri.parse(raw),arrayOf(Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_SIZE),null,null,null)?.use {
        if (it.moveToFirst() && !it.isNull(1) && it.getLong(1)>0) MusicFile(raw,it.getString(0),it.getLong(1)) else null
    }
    fun children(tree: String): List<MusicFile> {
        val root=Uri.parse(tree)
        val children=Docs.buildChildDocumentsUriUsingTree(root,Docs.getTreeDocumentId(root))
        return context.contentResolver.query(children,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_SIZE,Docs.Document.COLUMN_MIME_TYPE),null,null,null)?.use { cursor ->
            buildList { while(cursor.moveToNext()) {
                if (cursor.position>=10_000) throw CatalogException("항목이 10,000개를 넘는 폴더는 지원하지 않아요. 전용 폴더를 선택해 주세요.")
                if (cursor.getString(3)!=Docs.Document.MIME_TYPE_DIR) add(MusicFile(Docs.buildDocumentUriUsingTree(root,cursor.getString(0)).toString(),cursor.getString(1),if(cursor.isNull(2)) -1 else cursor.getLong(2)))
            } }
        } ?: throw CatalogException("선택한 폴더를 읽을 수 없어요. 폴더를 다시 선택해 주세요.")
    }
    fun create(tree: String, name: String): Uri {
        val root=Uri.parse(tree)
        return Docs.createDocument(context.contentResolver,Docs.buildDocumentUriUsingTree(root,Docs.getTreeDocumentId(root)),"audio/mpeg",name)
            ?: throw CatalogException("선택한 폴더에 파일을 만들 수 없어요.")
    }
    fun delete(file: MusicFile) { check(Docs.deleteDocument(context.contentResolver,Uri.parse(file.uri))) }
    suspend fun replaceDuplicate(file: MusicFile, retained: MusicFile) {
        require(file.uri!=retained.uri)
        check(stat(retained.uri)!=null && digest(retained)==digest(file)) { "Files changed during review" }
        delete(file)
        val edit=index.edit()
        index.all.forEach { (video,raw) -> if(runCatching { JSONObject(raw as String).getString("uri")==file.uri }.getOrDefault(false))
            edit.putString(video,JSONObject().put("uri",retained.uri).toString()) }
        check(edit.commit())
    }
    suspend fun digest(file: MusicFile): String {
        val hash=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
            val buffer=ByteArray(32768)
            while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) }
        } ?: throw CatalogException("중복 검사 파일을 읽을 수 없어요.")
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun duplicates(tree: String): List<List<MusicFile>> {
        val possible=children(tree).filter { it.size>0 && it.name.endsWith(".mp3",true) && !it.name.startsWith(".audioflow-") }.groupBy { it.size }.values.filter { it.size>1 }
        return possible.flatMap { sameSize -> sameSize.map { it to digest(it) }.groupBy({it.second},{it.first}).values.filter { it.size>1 } }
    }
    fun report(batch: String, rows: List<ExportRow> = emptyList()): List<ExportRow> = synchronized(reportLock) {
        require(batch.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val directory=File(context.filesDir,"export-reports").apply { mkdirs() }
        val file=AtomicFile(File(directory,"$batch.json"))
        val previous=if(file.baseFile.isFile) {
            require(file.baseFile.length()<=4*1024*1024)
            val array=JSONArray(file.openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { array.getJSONObject(it).let { obj -> ExportRow(obj.getInt("index"),obj.getString("title"),obj.getString("result")) } }
        } else emptyList()
        val merged=(previous+rows.map { it.copy(title=it.title.take(160),result=it.result.take(300)) }).associateBy { it.index }.values.sortedBy { it.index }.take(5000)
        if(rows.isNotEmpty()) {
            val bytes=JSONArray(merged.map { JSONObject().put("index",it.index).put("title",it.title).put("result",it.result) }).toString().toByteArray()
            require(bytes.size<=4*1024*1024)
            val out=file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch(error: Exception) { file.failWrite(out); throw error }
            prefs.edit().putString("last_report",batch).apply()
            directory.listFiles()?.filter { it.extension=="json" && it.name!="$batch.json" }?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete(); prefs.edit().remove("blocked_${it.nameWithoutExtension}").apply() }
        }
        merged
    }
    fun lastReport(): List<ExportRow> = prefs.getString("last_report",null)?.let { report(it) }.orEmpty()
    companion object { private val reportLock=Any() }
}

internal fun batchMusicName(track: Track) = musicFileName(track).removeSuffix(".mp3")+" [${track.id}].mp3"
internal fun duplicateFile(track: Track, files: List<MusicFile>): MusicFile? = files.firstOrNull {
    it.size>0 && (it.name.endsWith("[${track.id}].mp3",true) || it.name.equals(musicFileName(track),true))
}
