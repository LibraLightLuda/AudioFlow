package app.audioflow

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import android.provider.DocumentsProvider
import java.io.File

// Debug-only provider exercises the real document contract against isolated cache files.
class TestMusicDocuments : DocumentsProvider() {
    private val root get()=File(checkNotNull(context).cacheDir,"test-music-documents").apply { mkdirs() }
    private fun file(id: String): File {
        if(id=="root") return root
        return File(root,id).canonicalFile.also { require(it.parentFile==root.canonicalFile) }
    }
    override fun onCreate()=true
    override fun isChildDocument(parentDocumentId: String, documentId: String)=parentDocumentId=="root" && documentId!="root" && file(documentId).exists()
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(Docs.Root.COLUMN_ROOT_ID,Docs.Root.COLUMN_DOCUMENT_ID,Docs.Root.COLUMN_TITLE)).apply {
        val values=mapOf(Docs.Root.COLUMN_ROOT_ID to "test",Docs.Root.COLUMN_DOCUMENT_ID to "root",Docs.Root.COLUMN_TITLE to "AudioFlow test files")
        addRow(columnNames.map { values[it] })
    }
    private fun cursor(projection: Array<out String>?)=MatrixCursor(projection ?: arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_SIZE,Docs.Document.COLUMN_MIME_TYPE,Docs.Document.COLUMN_FLAGS))
    private fun MatrixCursor.add(id: String) {
        val f=file(id)
        check(f.exists())
        val values=mapOf(Docs.Document.COLUMN_DOCUMENT_ID to id,Docs.Document.COLUMN_DISPLAY_NAME to f.name,
            Docs.Document.COLUMN_SIZE to f.length(),Docs.Document.COLUMN_MIME_TYPE to if(f.isDirectory) Docs.Document.MIME_TYPE_DIR else "audio/mpeg",
            Docs.Document.COLUMN_FLAGS to if(f.isDirectory) Docs.Document.FLAG_DIR_SUPPORTS_CREATE else Docs.Document.FLAG_SUPPORTS_WRITE or Docs.Document.FLAG_SUPPORTS_DELETE or Docs.Document.FLAG_SUPPORTS_RENAME)
        addRow(columnNames.map { values[it] })
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?)=cursor(projection).apply { add(documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?)=cursor(projection).apply { file(parentDocumentId).listFiles()?.forEach { add(it.name) } }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId=="root")
        val f=file(displayName)
        check(f.createNewFile())
        return f.name
    }
    override fun renameDocument(documentId: String, displayName: String): String { check(file(documentId).renameTo(file(displayName))); return displayName }
    override fun deleteDocument(documentId: String) { require(documentId!="root"); check(file(documentId).delete()) }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?)=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
}
