package app.audioflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class ExportStatus(val busy: Boolean = false, val message: String = "", val percent: Int? = null)
internal object MusicExport { val status = MutableStateFlow(ExportStatus()) }

internal class MusicExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var lastNotice = 0L
    private val notifications get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel("music-export","음악 파일 저장",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notice(message: String, percent: Int? = null) = NotificationCompat.Builder(this,"music-export")
        .setSmallIcon(R.drawable.ic_audioflow).setContentTitle("MP3로 저장 중")
        .setContentText(message).setOnlyAlertOnce(true).setOngoing(true)
        .setProgress(100,percent ?: 0,percent == null)
        .setContentIntent(PendingIntent.getActivity(this,904,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0,"취소",PendingIntent.getService(this,905,Intent(this,MusicExportService::class.java).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
    private fun progress(label: String, done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotice < 300) return
        lastNotice = now
        val percent = if (total > 0) (done * 100 / total).coerceIn(0,100).toInt() else null
        MusicExport.status.value = ExportStatus(true,label,percent)
        if (android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifications.notify(904,notice(label,percent))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { task?.cancel(); if (task == null) stopSelf(); return START_NOT_STICKY }
        startForeground(904,notice("음원을 확인하고 있어요."))
        if (task?.isActive == true) return START_NOT_STICKY
        val target = intent?.getStringExtra("destination")?.let(Uri::parse)
        val id = intent?.getStringExtra("id").orEmpty()
        if (target?.scheme != "content" || !YoutubeLink.isVideoId(id)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
        MusicExport.status.value = ExportStatus(true,"음원을 확인하고 있어요.")
        task = scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AudioFlow:MusicExport").apply { setReferenceCounted(false) }
            val directory = File(cacheDir,"music-export").apply { mkdirs() }
            val source = File(directory,"source.media")
            val mp3 = File(directory,"encoded.mp3")
            // shortcut: process death does not resume; staging files are reused on the next export.
            var completed = false
            var result = "음악 파일을 저장하지 못했어요."
            try {
                wake.acquire(31 * 60_000L)
                withTimeout(30 * 60_000L) {
                    withContext(Dispatchers.IO) {
                        val resolved = YoutubeCatalog.resolve(id)
                        downloadAudio(resolved,source) { done,total -> progress("음원 다운로드",done,total) }
                        progress("MP3 변환",0,0)
                        mp3.outputStream().buffered().use { out ->
                            encodeAudioFile(source,ExportOutput(out)) { done,total -> progress("MP3 변환",done,total) }
                        }
                        currentCoroutineContext().ensureActive()
                        contentResolver.openOutputStream(target,"wt")?.buffered()?.use { out ->
                            mp3.inputStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer,0,count); copied += count
                                    progress("선택한 위치에 저장",copied,mp3.length())
                                }
                            }
                        } ?: throw CatalogException("선택한 위치에 파일을 쓸 수 없어요.")
                    }
                    completed = true
                    result = "MP3 파일을 선택한 위치에 저장했어요."
                }
            } catch (_: CancellationException) {
                result = "저장을 취소했거나 제한 시간이 지나 종료했어요."
            } catch (error: Exception) {
                result = when {
                    error is CatalogException || error is CatalogHttpException -> catalogError(error)
                    cacheDir.usableSpace < 8 * 1024 * 1024 -> "기기의 저장 공간이 부족해요. 여유 공간을 확보한 뒤 다시 시도해 주세요."
                    error is android.media.MediaCodec.CodecException -> "이 기기에서 해당 음원의 형식을 변환하지 못했어요."
                    else -> "음악 파일을 저장하지 못했어요. 연결 상태와 저장 위치의 여유 공간을 확인해 주세요."
                }
                (application as AudioFlowApp).reportFailure("MP3 저장",error)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    source.delete(); mp3.delete()
                    if (!completed) runCatching { DocumentsContract.deleteDocument(contentResolver,target) }
                }
                if (wake.isHeld) wake.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                task = null
                MusicExport.status.value = ExportStatus(message=result)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopSelf(startId) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

private class ExportOutput(output: OutputStream) : FilterOutputStream(output) {
    private var bytes = 0L
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (bytes + length > 256L * 1024 * 1024) throw CatalogException("저장할 MP3 파일이 너무 커서 종료했어요.")
        out.write(buffer,offset,length); bytes += length
    }
    override fun write(value: Int) { write(byteArrayOf(value.toByte()),0,1) }
}
