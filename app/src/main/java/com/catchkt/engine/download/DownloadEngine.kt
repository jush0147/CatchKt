package com.catchkt.engine.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.catchkt.data.model.DownloadStatus
import com.catchkt.data.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val taskId: Long,
    val downloadedSize: Long,
    val totalSize: Long,
    val status: DownloadStatus
)

@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val okHttpClient: OkHttpClient
) {
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val _progressUpdates = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val progressUpdates: StateFlow<Map<Long, DownloadProgress>> = _progressUpdates.asStateFlow()

    private val progressMutex = Mutex()
    private val fileMutex = Mutex()

    // OkHttp client with longer timeouts for actual downloads
    private val downloadClient = okHttpClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CHUNK_COUNT = 4
        private const val PROGRESS_UPDATE_INTERVAL = 500L // ms
        private const val BUFFER_SIZE = 8192
    }

    fun startDownload(taskId: Long, scope: CoroutineScope) {
        if (activeJobs.containsKey(taskId)) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                executeDownload(taskId)
            } catch (_: CancellationException) {
                repository.updateStatus(taskId, DownloadStatus.PAUSED)
                throw CancellationException()
            } catch (e: Exception) {
                repository.updateStatus(taskId, DownloadStatus.ERROR)
                updateProgress(taskId, -1, -1, DownloadStatus.ERROR)
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    fun pauseDownload(taskId: Long) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
    }

    fun cancelDownload(taskId: Long, scope: CoroutineScope) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        scope.launch(Dispatchers.IO) {
            repository.updateStatus(taskId, DownloadStatus.ERROR)
            // Clean up temp file
            val task = repository.getTaskById(taskId) ?: return@launch
            getTempFile(task.fileName).delete()
        }
    }

    private suspend fun executeDownload(taskId: Long) {
        val task = repository.getTaskById(taskId) ?: return

        repository.updateStatus(taskId, DownloadStatus.DOWNLOADING)
        updateProgress(taskId, task.downloadedSize, task.totalSize, DownloadStatus.DOWNLOADING)

        val tempFile = getTempFile(task.fileName)

        // Check if server supports range requests
        val supportsRange = checkRangeSupport(task.url)
        val totalSize = task.totalSize

        if (supportsRange && totalSize > 0) {
            downloadMultiThread(taskId, task.url, tempFile, totalSize, task.fileName, task.mimeType)
        } else {
            downloadSingleThread(taskId, task.url, tempFile, totalSize, task.fileName, task.mimeType)
        }
    }

    private suspend fun checkRangeSupport(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                val response = downloadClient.newCall(request).execute()
                response.use {
                    val acceptRanges = it.header("Accept-Ranges")
                    acceptRanges?.lowercase() == "bytes"
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun downloadMultiThread(
        taskId: Long,
        url: String,
        tempFile: File,
        totalSize: Long,
        fileName: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        // Pre-allocate file
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(totalSize)
        }

        val chunkSize = totalSize / CHUNK_COUNT
        val downloadedPerChunk = LongArray(CHUNK_COUNT)
        val progressTracker = Mutex()
        var lastProgressUpdate = 0L

        val jobs = (0 until CHUNK_COUNT).map { index ->
            val start = index * chunkSize
            val end = if (index == CHUNK_COUNT - 1) totalSize - 1 else (start + chunkSize - 1)

            async(Dispatchers.IO) {
                downloadChunk(taskId, url, tempFile, start, end, index, downloadedPerChunk, progressTracker) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > PROGRESS_UPDATE_INTERVAL) {
                        lastProgressUpdate = now
                        val totalDownloaded = downloadedPerChunk.sum()
                        repository.updateProgress(taskId, totalDownloaded)
                        updateProgress(taskId, totalDownloaded, totalSize, DownloadStatus.DOWNLOADING)
                    }
                }
            }
        }

        // Wait for all chunks to complete
        jobs.forEach { it.await() }

        // Final progress update
        val totalDownloaded = downloadedPerChunk.sum()
        repository.updateProgressAndStatus(taskId, totalDownloaded, DownloadStatus.COMPLETED)
        updateProgress(taskId, totalDownloaded, totalSize, DownloadStatus.COMPLETED)

        // Move to MediaStore
        publishToMediaStore(tempFile, fileName, mimeType)
        tempFile.delete()
    }

    private suspend fun downloadChunk(
        taskId: Long,
        url: String,
        tempFile: File,
        start: Long,
        end: Long,
        chunkIndex: Int,
        downloadedPerChunk: LongArray,
        mutex: Mutex,
        onProgress: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw RuntimeException("Download chunk failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        var offset = start

        val inputStream = body.byteStream()
        val raf = RandomAccessFile(tempFile, "rw")

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isActive) break

                raf.seek(offset)
                raf.write(buffer, 0, bytesRead)
                offset += bytesRead

                mutex.withLock {
                    downloadedPerChunk[chunkIndex] = downloadedPerChunk[chunkIndex] + bytesRead.toLong()
                }
                onProgress()
            }
        } finally {
            raf.close()
            inputStream.close()
            response.close()
        }
    }

    private suspend fun downloadSingleThread(
        taskId: Long,
        url: String,
        tempFile: File,
        totalSize: Long,
        fileName: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        // Pre-allocate if size is known
        if (totalSize > 0) {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(totalSize)
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        var downloadedBytes = 0L
        var lastProgressUpdate = 0L

        val inputStream = body.byteStream()
        val raf = RandomAccessFile(tempFile, "rw")

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isActive) break

                raf.seek(downloadedBytes)
                raf.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > PROGRESS_UPDATE_INTERVAL) {
                    lastProgressUpdate = now
                    val reportedTotal = if (totalSize > 0) totalSize else downloadedBytes
                    repository.updateProgress(taskId, downloadedBytes)
                    updateProgress(taskId, downloadedBytes, reportedTotal, DownloadStatus.DOWNLOADING)
                }
            }
        } finally {
            raf.close()
            inputStream.close()
            response.close()
        }

        val finalTotal = if (totalSize > 0) totalSize else downloadedBytes
        repository.updateProgressAndStatus(taskId, downloadedBytes, DownloadStatus.COMPLETED)
        updateProgress(taskId, downloadedBytes, finalTotal, DownloadStatus.COMPLETED)

        publishToMediaStore(tempFile, fileName, mimeType)
        tempFile.delete()
    }

    private fun publishToMediaStore(tempFile: File, fileName: String, mimeType: String) {
        val resolver = context.contentResolver
        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (mimeType.startsWith("audio/")) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val relativePath = when {
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES + "/CatchKt"
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC + "/CatchKt"
            else -> Environment.DIRECTORY_DOWNLOADS + "/CatchKt"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return

        resolver.openOutputStream(uri)?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream, BUFFER_SIZE)
            }
        }

        // Mark as not pending - publish the file
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun getTempFile(fileName: String): File {
        val dir = File(context.cacheDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$fileName.tmp")
    }

    private suspend fun updateProgress(taskId: Long, downloaded: Long, total: Long, status: DownloadStatus) {
        progressMutex.withLock {
            val current = _progressUpdates.value.toMutableMap()
            current[taskId] = DownloadProgress(taskId, downloaded, total, status)
            _progressUpdates.value = current
        }
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
