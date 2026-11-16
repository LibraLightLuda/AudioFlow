package app.audioflow

import android.content.Context
import android.provider.DocumentsContract as Docs
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BatchStorageTest {
    @Test fun realDocumentsSupportHashCleanupAndDuplicateOnlyBatchWithoutNetwork()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val files=ExportFiles(context)
        val tree=Docs.buildTreeDocumentUri("${context.packageName}.test-documents","root").toString()
        val track=Track("batchTest01","batch storage test","test",seconds=19)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                withContext(Dispatchers.IO) {
                    files.children(tree).forEach { files.delete(it) }
                    val a=files.create(tree,batchMusicName(track)); val b=files.create(tree,"same-content.mp3"); val c=files.create(tree,"different-content.mp3")
                    listOf(a,b).forEach { uri -> context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1,2,3,4)) } }
                    context.contentResolver.openOutputStream(c)!!.use { it.write(byteArrayOf(4,3,2,1)) }
                    val groups=files.duplicates(tree)
                    assertEquals(1,groups.size); assertEquals(2,groups.single().size)
                    files.remember(track.id,b)
                    files.replaceDuplicate(groups.single().first { file -> file.uri==b.toString() },checkNotNull(files.stat(a.toString())))
                    assertEquals(a.toString(),files.existing(track.id)?.uri)
                    assertNotNull(files.stat(a.toString())); assertNull(runCatching { files.stat(b.toString()) }.getOrNull())
                    files.remember(track.id,a); assertNotNull(files.existing(track.id))
                    ExportJobs.enqueue(context,List(102) { track },tree)
                    withTimeout(60_000) { while(true) {
                        val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork(ExportJobs.NAME).get()
                        if(work.isNotEmpty() && work.all { it.state.isFinished }) break
                        delay(100)
                    } }
                    val report=files.lastReport()
                    assertEquals(102,report.size)
                    assertTrue(report.all { row -> row.result.startsWith("중복 건너뜀") && row.title==track.title })
                    assertEquals(2,files.children(tree).size)
                    files.delete(checkNotNull(files.stat(a.toString()))); assertNull(files.existing(track.id))
                }
            } finally { withContext(Dispatchers.IO) { files.children(tree).forEach { files.delete(it) } }; MusicExport.status.value=ExportStatus() }
        }
    }
    @Test fun updateReminderPersistsOnceForEveryNewerVersion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("update-reminder",Context.MODE_PRIVATE)
        val old=prefs.getString("highest_notified",null)
        try {
            prefs.edit().clear().commit()
            assertTrue(UpdateReminder(context).claim(checkNotNull(ReleaseVersion.parse("1.12.0"))))
            assertFalse(UpdateReminder(context).claim(checkNotNull(ReleaseVersion.parse("1.12.0"))))
            assertFalse(UpdateReminder(context).claim(checkNotNull(ReleaseVersion.parse("1.11.0"))))
            assertTrue(UpdateReminder(context).claim(checkNotNull(ReleaseVersion.parse("1.13.0"))))
            assertFalse(UpdateReminder(context).claim(checkNotNull(ReleaseVersion.parse("1.13.0"))))
        } finally { prefs.edit().putString("highest_notified",old).commit() }
    }
}
