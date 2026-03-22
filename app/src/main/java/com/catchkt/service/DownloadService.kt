package com.catchkt.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.catchkt.CatchKtApp
import com.catchkt.R
import com.catchkt.data.model.DownloadStatus
import com.catchkt.data.model.SniffedResource
import com.catchkt.data.model.DownloadTask
import com.catchkt.data.repository.DownloadRepository
import com.catchkt.engine.download.DownloadEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadEngine: DownloadEngine
    @Inject lateinit var repository: DownloadRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.catchkt.action.START_DOWNLOAD"
        private const val ACTION_PAUSE = "com.catchkt.action.PAUSE_DOWNLOAD"
        private const val ACTION_RESUME = "com.catchkt.action.RESUME_DOWNLOAD"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val EXTRA_TOTAL_SIZE = "total_size"

        fun startDownload(context: Context, resource: SniffedResource): Intent {
            return Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, resource.url)
                putExtra(EXTRA_FILE_NAME, resource.fileName)
                putExtra(EXTRA_MIME_TYPE, resource.contentType)
                putExtra(EXTRA_TOTAL_SIZE, resource.contentLength)
            }
        }

        fun pauseDownload(context: Context, taskId: Long): Intent {
            return Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, taskId)
            }
        }

        fun resumeDownload(context: Context, taskId: Long): Intent {
            return Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_TASK_ID, taskId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("準備下載中..."))
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: return START_NOT_STICKY
                val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, -1)

                serviceScope.launch {
                    val task = DownloadTask(
                        url = url,
                        fileName = fileName,
                        mimeType = mimeType,
                        totalSize = totalSize,
                        status = DownloadStatus.PENDING
                    )
                    val taskId = repository.insertTask(task)
                    downloadEngine.startDownload(taskId, serviceScope)
                }
            }

            ACTION_PAUSE -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                if (taskId != -1L) {
                    downloadEngine.pauseDownload(taskId)
                }
            }

            ACTION_RESUME -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                if (taskId != -1L) {
                    downloadEngine.startDownload(taskId, serviceScope)
                }
            }
        }
        return START_STICKY
    }

    private fun observeProgress() {
        serviceScope.launch {
            downloadEngine.progressUpdates.collect { progressMap ->
                if (progressMap.isEmpty()) return@collect

                val activeDownloads = progressMap.values
                    .filter { it.status == DownloadStatus.DOWNLOADING }

                if (activeDownloads.isEmpty()) {
                    val allCompleted = progressMap.values.all {
                        it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.ERROR
                    }
                    if (allCompleted && progressMap.isNotEmpty()) {
                        updateNotification("所有下載已完成")
                        stopSelf()
                    }
                    return@collect
                }

                val totalProgress = activeDownloads.sumOf { it.downloadedSize }
                val totalSize = activeDownloads.sumOf { it.totalSize }
                val percent = if (totalSize > 0) (totalProgress * 100 / totalSize).toInt() else 0

                updateNotification(
                    "下載中 ${activeDownloads.size} 個檔案 ($percent%)",
                    percent
                )
            }
        }
    }

    private fun buildNotification(text: String, progress: Int = -1): Notification {
        val builder = NotificationCompat.Builder(this, CatchKtApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CatchKt")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int = -1) {
        val notification = buildNotification(text, progress)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
