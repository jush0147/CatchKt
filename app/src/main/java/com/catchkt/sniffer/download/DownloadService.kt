package com.catchkt.sniffer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.catchkt.sniffer.data.db.DownloadStatus
import com.catchkt.sniffer.data.db.DownloadTaskEntity
import com.catchkt.sniffer.data.repository.DownloadTaskRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {
    @Inject
    lateinit var repository: DownloadTaskRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val httpClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待下載任務", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_RESUME -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId > 0L) {
                    startOrResume(taskId)
                }
            }
            ACTION_PAUSE -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                pauseTask(taskId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJobs.values.forEach { it.cancel() }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResume(taskId: Long) {
        if (activeJobs.containsKey(taskId)) return

        val job = serviceScope.launch {
            val task = repository.getTask(taskId) ?: return@launch
            try {
                repository.updateStatus(taskId, DownloadStatus.DOWNLOADING)
                runDownload(task)
            } finally {
                activeJobs.remove(taskId)
                if (activeJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }

        val existing = activeJobs.putIfAbsent(taskId, job)
        if (existing != null) {
            job.cancel()
        }
    }

    private fun pauseTask(taskId: Long) {
        activeJobs.remove(taskId)?.cancel()
        if (taskId > 0L) {
            serviceScope.launch {
                repository.updateStatus(taskId, DownloadStatus.PAUSED)
            }
        }
    }

    private suspend fun runDownload(task: DownloadTaskEntity) {
        val taskId = task.id
        val tempFile = File(cacheDir, "task_${taskId}.part")
        val currentTask = repository.getTask(taskId) ?: task

        val probe = probeServer(currentTask.url)
        if (probe.fatalErrorCode != null) {
            repository.updateStatus(taskId, DownloadStatus.ERROR)
            updateNotification("下載失敗 (${probe.fatalErrorCode})", 0)
            return
        }

        val totalSize = if (probe.contentLength > 0L) probe.contentLength else currentTask.totalSize
        if (probe.contentLength > 0L && probe.contentLength != currentTask.totalSize) {
            repository.updateTotalSize(taskId, probe.contentLength)
        }
        if (totalSize <= 0L) {
            repository.updateStatus(taskId, DownloadStatus.ERROR)
            updateNotification("無法取得檔案大小", 0)
            return
        }

        val downloadedCounter = AtomicLong(currentTask.downloadedSize.coerceAtLeast(0L))

        val raf = java.io.RandomAccessFile(tempFile, "rw")
        try {
            if (tempFile.length() != totalSize) {
                raf.setLength(totalSize)
            }

            val shouldUseParallel = probe.acceptRanges && currentTask.downloadedSize <= 0L
            if (shouldUseParallel) {
                downloadParallel(
                    url = currentTask.url,
                    totalSize = totalSize,
                    raf = raf,
                    downloadedCounter = downloadedCounter,
                    taskId = taskId
                )
            } else {
                downloadSingle(
                    url = currentTask.url,
                    startOffset = currentTask.downloadedSize.coerceAtLeast(0L),
                    totalSize = totalSize,
                    useRange = probe.acceptRanges,
                    raf = raf,
                    downloadedCounter = downloadedCounter,
                    taskId = taskId
                )
            }

            repository.updateProgress(taskId, totalSize, DownloadStatus.COMPLETED)
            updateNotification("下載完成", 100)
            publishToMediaStore(currentTask, tempFile)
        } catch (e: RecoverableDownloadException) {
            repository.updateStatus(taskId, DownloadStatus.PAUSED)
            updateNotification("已暫停，等待重試", progressPercent(downloadedCounter.get(), totalSize))
        } catch (e: FatalDownloadException) {
            repository.updateStatus(taskId, DownloadStatus.ERROR)
            updateNotification("下載失敗 (${e.code})", progressPercent(downloadedCounter.get(), totalSize))
        } catch (_: IOException) {
            repository.updateStatus(taskId, DownloadStatus.PAUSED)
            updateNotification("已暫停，等待重試", progressPercent(downloadedCounter.get(), totalSize))
        } finally {
            raf.close()
        }
    }

    private suspend fun downloadParallel(
        url: String,
        totalSize: Long,
        raf: java.io.RandomAccessFile,
        downloadedCounter: AtomicLong,
        taskId: Long
    ) {
        val chunkCount = PARALLEL_CHUNKS
        val chunkSize = totalSize / chunkCount
        val workers = (0 until chunkCount).map { index ->
            val start = index * chunkSize
            val end = if (index == chunkCount - 1) totalSize - 1 else ((index + 1) * chunkSize) - 1

            serviceScope.launch {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$start-$end")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code in listOf(403, 404)) throw FatalDownloadException(response.code)
                    if (!response.isSuccessful && response.code != 206) throw RecoverableDownloadException()

                    val body = response.body ?: throw RecoverableDownloadException()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var pointer = start
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            synchronized(raf) {
                                raf.seek(pointer)
                                raf.write(buffer, 0, read)
                            }
                            pointer += read
                            val done = downloadedCounter.addAndGet(read.toLong())
                            onProgress(taskId, done, totalSize)
                        }
                    }
                }
            }
        }
        workers.joinAll()
    }

    private suspend fun downloadSingle(
        url: String,
        startOffset: Long,
        totalSize: Long,
        useRange: Boolean,
        raf: java.io.RandomAccessFile,
        downloadedCounter: AtomicLong,
        taskId: Long
    ) {
        val requestBuilder = Request.Builder().url(url)
        if (useRange && startOffset > 0L) {
            requestBuilder.addHeader("Range", "bytes=$startOffset-")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code in listOf(403, 404)) throw FatalDownloadException(response.code)
            if (!response.isSuccessful) throw RecoverableDownloadException()

            val body = response.body ?: throw RecoverableDownloadException()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var pointer = startOffset
                while (isActive) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.seek(pointer)
                    raf.write(buffer, 0, read)
                    pointer += read
                    val done = downloadedCounter.addAndGet(read.toLong())
                    onProgress(taskId, done, totalSize)
                }
            }
        }
    }

    private suspend fun onProgress(taskId: Long, downloaded: Long, total: Long) {
        repository.updateProgress(taskId, downloaded, DownloadStatus.DOWNLOADING)
        updateNotification("下載中", progressPercent(downloaded, total))
    }

    private fun probeServer(url: String): ProbeResult {
        return try {
            val request = Request.Builder().url(url).head().build()
            httpClient.newCall(request).execute().use { response ->
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                val fatal = if (response.code in listOf(403, 404)) response.code else null
                ProbeResult(contentLength = contentLength, acceptRanges = acceptRanges, fatalErrorCode = fatal)
            }
        } catch (_: IOException) {
            ProbeResult(contentLength = -1L, acceptRanges = false, fatalErrorCode = null)
        }
    }

    private fun publishToMediaStore(task: DownloadTaskEntity, sourceFile: File) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFileName(task.fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, task.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SnifferDownloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Cannot create MediaStore record")

        resolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw IOException("Cannot open MediaStore output stream")

        val completeValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, completeValues, null, null)

        sourceFile.delete()
    }

    private fun progressPercent(downloaded: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((downloaded * 100) / total).toInt().coerceIn(0, 100)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sniffer Downloader",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(contentText: String, progress: Int) {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(contentText, progress))
    }

    private fun buildNotification(contentText: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sniffer Downloader")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PARALLEL_CHUNKS = 4

        const val ACTION_START_OR_RESUME = "com.catchkt.sniffer.action.START_OR_RESUME"
        const val ACTION_PAUSE = "com.catchkt.sniffer.action.PAUSE"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun startOrResume(context: Context, taskId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_OR_RESUME
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context, taskId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}

private data class ProbeResult(
    val contentLength: Long,
    val acceptRanges: Boolean,
    val fatalErrorCode: Int?
)

private class RecoverableDownloadException : IOException()

private class FatalDownloadException(val code: Int) : IOException()
