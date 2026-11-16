package app.audioflow

import android.content.Context
import android.net.NetworkCapabilities as Caps
import android.provider.DocumentsContract as Docs
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportWifiTest {
    @Test fun defaultsPersistAndWorkRequiresActualWifi() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("music-export-settings",Context.MODE_PRIVATE)
        val previous=prefs.all["auto_wifi_only"] as? Boolean
        try {
            prefs.edit().remove("auto_wifi_only").commit()
            assertTrue(ExportFiles(context).autoWifiOnly)
            ExportFiles(context).autoWifiOnly=false
            assertFalse(ExportFiles(context).autoWifiOnly)
            assertFalse(usableExportWifi(null))
            val required=checkNotNull(exportConstraints(true,true).requiredNetworkRequest)
            assertTrue(required.hasTransport(Caps.TRANSPORT_WIFI)); assertFalse(required.hasTransport(Caps.TRANSPORT_CELLULAR))
            assertFalse(required.hasCapability(Caps.NET_CAPABILITY_NOT_METERED))
            assertEquals(NetworkType.CONNECTED,exportConstraints(true,false).requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED,exportConstraints(false,true).requiredNetworkType)
        } finally { prefs.edit().apply { if(previous==null) remove("auto_wifi_only") else putBoolean("auto_wifi_only",previous) }.commit() }
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() } }
    @Test fun automaticQueueWaitsWithoutWifiAndResumesAfterWifiReturns()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val files=ExportFiles(context)
        val previousWifi=files.autoWifiOnly
        val wifiWasOn=shell("settings get global wifi_on")!="0"
        val tree=Docs.buildTreeDocumentUri("${context.packageName}.test-documents","root").toString()
        val track=Track("wifiTest001","WiFi queue test",seconds=19)
        var document: android.net.Uri?=null
        var requestId: java.util.UUID?=null
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("svc wifi enable")
                withTimeout(30_000) { while(exportWifi(context)==null) delay(100) }
                val wifi=checkNotNull(exportWifi(context))
                val original=PrivateNetwork.stream
                val clients=PrivateNetwork.wifiClients(wifi)
                withContext(Dispatchers.IO+PrivateNetwork.exportClients.asContextElement(clients)) {
                    assertSame(wifi.socketFactory,PrivateNetwork.catalog.socketFactory)
                    assertSame(wifi.socketFactory,PrivateNetwork.stream.socketFactory)
                    yield(); assertSame(clients.second,PrivateNetwork.stream)
                }
                assertSame(original,PrivateNetwork.stream)
                withContext(Dispatchers.IO) {
                    files.children(tree).filter { it.name==batchMusicName(track) }.forEach { files.delete(it) }
                    document=files.create(tree,batchMusicName(track))
                    context.contentResolver.openOutputStream(checkNotNull(document))!!.use { out -> out.write(byteArrayOf(1,2,3,4)) }
                }
                files.autoWifiOnly=true
                shell("svc wifi disable")
                withTimeout(20_000) { while(exportWifi(context)!=null) delay(100) }
                val manager=WorkManager.getInstance(context)
                val before=withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(ExportJobs.NAME).get().map { work -> work.id }.toSet() }
                val reportBefore=files.lastReport()
                ExportJobs.enqueue(context,listOf(track),tree=tree,automatic=true)
                delay(1500)
                val waiting=withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(ExportJobs.NAME).get().filter { work -> work.id !in before } }
                requestId=waiting.singleOrNull()?.id
                assertEquals(1,waiting.size); assertEquals(androidx.work.WorkInfo.State.ENQUEUED,waiting.single().state)
                assertEquals(reportBefore,files.lastReport())
                shell("svc wifi enable")
                withTimeout(60_000) {
                    while(withContext(Dispatchers.IO) { manager.getWorkInfoById(waiting.single().id).get()?.state }!=androidx.work.WorkInfo.State.SUCCEEDED) delay(100)
                }
                assertTrue(files.lastReport().single().result.startsWith("중복 건너뜀"))
            } finally {
                requestId?.let { work -> withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelWorkById(work).result.get() } }
                shell(if(wifiWasOn) "svc wifi enable" else "svc wifi disable")
                if(wifiWasOn) withTimeout(30_000) { while(exportWifi(context)==null) delay(100) }
                document?.let { uri -> runCatching { Docs.deleteDocument(context.contentResolver,uri) } }
                files.autoWifiOnly=previousWifi; MusicExport.status.value=ExportStatus()
            }
        }
    }
}
