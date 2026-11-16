package app.audioflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ExportStatus(val busy: Boolean=false, val message: String="", val percent: Int?=null)
internal object MusicExport { val status=MutableStateFlow(ExportStatus()) }

internal object ExportJobs {
    const val NAME="music-export"
    suspend fun enqueue(context: Context, tracks: List<Track>, tree: String="", document: String="", automatic: Boolean=false) = withContext(Dispatchers.IO) {
        require(tracks.isNotEmpty() && tracks.size<=5000 && tree.length<=2000 && document.length<=2000)
        require((tree.isNotBlank()) xor (document.isNotBlank()))
        require((tree.ifBlank { document }).startsWith("content://"))
        require(document.isBlank() || tracks.size==1)
        require(tracks.all { YoutubeLink.isVideoId(it.id) })
        val app=context.applicationContext as AudioFlowApp
        app.store.dao.insert(tracks.distinctBy { it.id }.map { SavedTrack(it) })
        val batch=UUID.randomUUID().toString()
        val wifiOnly=automatic && ExportFiles(context).autoWifiOnly
        val requests=tracks.chunked(100).mapIndexed { chunk, part ->
            OneTimeWorkRequestBuilder<MusicExportWorker>().setInputData(workDataOf(
                "ids" to part.map { it.id }.toTypedArray(),"batch" to batch,"offset" to chunk*100,"tree" to tree,"document" to document,"automatic" to automatic,"wifi_only" to wifiOnly))
                .setConstraints(exportConstraints(automatic,wifiOnly))
                .addTag(NAME).build()
        }
        MusicExport.status.value=ExportStatus(true,if(wifiOnly && exportWifi(context)==null) "Wi-Fi 연결 후 자동 저장을 시작해요." else "저장 대기열에 ${tracks.size}곡을 추가했어요.")
        WorkManager.getInstance(context).beginUniqueWork(NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,requests.take(1))
            .let { first -> requests.drop(1).fold(first) { chain, request -> chain.then(request) } }.enqueue().result.get()
    }
    private val automaticPending=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun automatic(app: AudioFlowApp, track: Track) {
        if(!ExportFiles(app).automatic || !automaticPending.add(track.id)) return
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val files=ExportFiles(app)
                    if(files.folder.isNotBlank() && files.existing(track.id)==null) enqueue(app,listOf(track),tree=files.folder,automatic=true)
                }
            } catch(error: Exception) { if(error is CancellationException) throw error; app.reportFailure("자동 MP3 저장",error) }
            // Avoid re-enqueuing a failed/repeated track until the next player session.
        }
    }
    fun resetAutomaticSession() { automaticPending.clear() }
}

internal class MusicExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    private val context=applicationContext
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val files=ExportFiles(context)
    private var lastNotice=0L
    private fun notification(text: String, percent: Int?=null) = NotificationCompat.Builder(context,"music-export")
        .setSmallIcon(R.drawable.ic_audioflow).setContentTitle("음악 파일 저장")
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,percent?:0,percent==null)
        .setContentIntent(PendingIntent.getActivity(context,904,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0,"현재 작업 취소",WorkManager.getInstance(context).createCancelPendingIntent(id)).build()
    private fun progress(label: String, done: Long=0, total: Long=0) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastNotice<300) return
        lastNotice=now
        val percent=if(total>0) (done*100/total).coerceIn(0,100).toInt() else null
        MusicExport.status.value=ExportStatus(true,label,percent)
        if(android.os.Build.VERSION.SDK_INT<33 || androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            manager.notify(904,notification(label,percent))
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val wifiOnly=inputData.getBoolean("automatic",false) && inputData.getBoolean("wifi_only",true)
        val wifi=if(wifiOnly) exportWifi(context) ?: run {
            MusicExport.status.value=ExportStatus(true,"Wi-Fi 연결을 기다리고 있어요.")
            return@withContext Result.retry()
        } else null
        withContext(PrivateNetwork.exportClients.asContextElement(wifi?.let(PrivateNetwork::wifiClients))) { export(wifi) }
    }
    private suspend fun export(wifi: android.net.Network?): Result = withContext(Dispatchers.IO) {
        val ids=inputData.getStringArray("ids").orEmpty()
        val batch=inputData.getString("batch").orEmpty()
        val offset=inputData.getInt("offset",0)
        val tree=inputData.getString("tree").orEmpty()
        val document=inputData.getString("document").orEmpty()
        if(ids.isEmpty() || ids.size>100 || ids.any { !YoutubeLink.isVideoId(it) } || !batch.matches(Regex("[a-f0-9-]{36}")) || offset !in 0..4900) return@withContext Result.failure()
        if((tree.isBlank()==document.isBlank()) || tree.length>2000 || document.length>2000 || (document.isNotBlank() && ids.size!=1) || Uri.parse(tree.ifBlank { document }).scheme!="content") return@withContext Result.failure()
        val rows=mutableListOf<ExportRow>()
        val dir=File(context.cacheDir,"music-export-$id").apply { mkdirs() }
        val source=File(dir,"source.media"); val mp3=File(dir,"encoded.mp3")
        var target: Uri?=null
        var committed=false
        var waitingForWifi=false
        try {
            manager.createNotificationChannel(NotificationChannel("music-export","음악 파일 저장",NotificationManager.IMPORTANCE_LOW))
            setForeground(ForegroundInfo(904,notification("저장을 준비하고 있어요."),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            withTimeout(45*60_000L) {
                val children=if(tree.isNotBlank()) files.children(tree).toMutableList() else mutableListOf()
                ids.forEachIndexed { index, video ->
                    target=null; committed=false
                    val saved=(context.applicationContext as AudioFlowApp).store.dao.track(video)?.track ?: Track(video,video)
                    val blocked=files.blocked(batch)
                    if(blocked!=null) { rows+=ExportRow(offset+index,saved.title,"중단: $blocked"); return@forEachIndexed }
                    val duplicate=files.existing(video) ?: duplicateFile(saved,children)
                    if(duplicate!=null) {
                        if(document.isNotBlank()) runCatching { Docs.deleteDocument(context.contentResolver,Uri.parse(document)) }
                        rows+=ExportRow(offset+index,saved.title,"중복 건너뜀: ${duplicate.name}")
                        return@forEachIndexed
                    }
                    try {
                        withTimeout(30*60_000L) {
                            progress("${offset+index+1}번째 · ${saved.title}")
                            val resolved=YoutubeCatalog.resolve(video)
                            downloadAudio(resolved,source) { done,total -> progress("다운로드 · ${saved.title}",done,total) }
                            mp3.outputStream().buffered().use { out -> encodeAudioFile(source,ExportOutput(out)) { done,total -> progress("MP3 변환 · ${saved.title}",done,total) } }
                            currentCoroutineContext().ensureActive()
                            target=if(document.isNotBlank()) Uri.parse(document) else {
                                val pending=".audioflow-$video-$id.mp3"
                                children.filter { it.name==pending }.forEach { files.delete(it) }
                                files.create(tree,pending)
                            }
                            context.contentResolver.openOutputStream(checkNotNull(target),"wt")?.buffered()?.use { out ->
                                mp3.inputStream().use { input ->
                                    val buffer=ByteArray(32768)
                                    while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }
                                }
                            } ?: throw CatalogException("선택한 위치에 쓸 수 없어요.")
                            if(document.isBlank()) target=Docs.renameDocument(context.contentResolver,checkNotNull(target),batchMusicName(resolved.track))
                                ?: throw CatalogException("음악 파일 이름을 확정하지 못했어요.")
                            files.remember(video,checkNotNull(target)); committed=true
                            files.stat(checkNotNull(target).toString())?.let { children.add(it) }
                            rows+=ExportRow(offset+index,resolved.track.title,"저장 완료")
                        }
                    } catch(timeout: TimeoutCancellationException) {
                        rows+=ExportRow(offset+index,saved.title,"실패: 변환 제한 시간 초과")
                        files.block(batch,"제한 시간 초과로 남은 저장을 중단했어요.")
                    } catch(cancelled: CancellationException) { throw cancelled
                    } catch(error: Exception) {
                        if(wifi!=null && !exportWifiAvailable(context,wifi)) throw AwaitExportWifi()
                        val message=if(error is CatalogException || error is CatalogHttpException) catalogError(error) else "연결·저장 공간·파일 권한을 확인해 주세요."
                        rows+=ExportRow(offset+index,saved.title,"실패: $message")
                        if(radioAccessLimited(error)) files.block(batch,message,true)
                        else if(error is java.io.IOException && error !is CatalogException && !(error is CatalogHttpException && error.status in setOf(404,410))) files.block(batch,message)
                        (context.applicationContext as AudioFlowApp).reportFailure("MP3 저장",error)
                    } finally {
                        withContext(NonCancellable) {
                            source.delete(); mp3.delete()
                            if(!committed) target?.let { runCatching { Docs.deleteDocument(context.contentResolver,it) } }
                        }
                    }
                }
            }
            Result.success()
        } catch(_: AwaitExportWifi) {
            waitingForWifi=true
            Result.retry()
        } catch(timeout: TimeoutCancellationException) {
            files.block(batch,"작업 제한 시간에 도달했어요. 남은 곡은 다시 저장해 주세요.")
            Result.success()
        } catch(cancelled: CancellationException) {
            if(wifi!=null) {
                val unavailable=!exportWifiAvailable(context,wifi)
                waitingForWifi=if(android.os.Build.VERSION.SDK_INT>=31)
                    stopReason!=WorkInfo.STOP_REASON_CANCELLED_BY_APP && (stopReason==WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY || unavailable)
                else unavailable && withContext(NonCancellable) {
                    WorkManager.getInstance(context).getWorkInfoById(id).get()?.state!=WorkInfo.State.CANCELLED
                }
            }
            throw cancelled
        } catch(error: Exception) {
            files.block(batch,"저장 작업을 진행하지 못했어요. 폴더 권한과 연결을 확인해 주세요.")
            Result.success()
        } finally {
            withContext(NonCancellable) {
                source.delete(); mp3.delete(); dir.delete()
                if(!committed) target?.let { runCatching { Docs.deleteDocument(context.contentResolver,it) } }
                if(document.isNotBlank() && rows.none { it.result=="저장 완료" }) runCatching { Docs.deleteDocument(context.contentResolver,Uri.parse(document)) }
                if(waitingForWifi) {
                    MusicExport.status.value=ExportStatus(true,"Wi-Fi 연결을 기다리고 있어요. 다시 연결되면 재시도해요.")
                } else {
                val finished=rows.mapTo(hashSetOf()) { it.index }
                ids.forEachIndexed { index, video -> if(offset+index !in finished) rows+=ExportRow(offset+index,video,"중단: 완료하지 못했어요.") }
                val report=runCatching { files.report(batch,rows) }.getOrDefault(rows)
                MusicExport.status.value=ExportStatus(message="저장 ${report.count { it.result=="저장 완료" }} · 중복 ${report.count { it.result.startsWith("중복") }} · 실패/중단 ${report.count { it.result.startsWith("실패") || it.result.startsWith("중단") }}")
                }
            }
        }
    }
}

private class AwaitExportWifi : CancellationException()

private class ExportOutput(output: OutputStream) : FilterOutputStream(output) {
    private var bytes=0L
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if(bytes+length>256L*1024*1024) throw CatalogException("저장할 MP3 파일이 너무 커서 종료했어요.")
        out.write(buffer,offset,length); bytes+=length
    }
    override fun write(value: Int) { write(byteArrayOf(value.toByte()),0,1) }
}
