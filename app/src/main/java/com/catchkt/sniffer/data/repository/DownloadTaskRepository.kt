package com.catchkt.sniffer.data.repository

import com.catchkt.sniffer.data.db.DownloadStatus
import com.catchkt.sniffer.data.db.DownloadTaskDao
import com.catchkt.sniffer.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadTaskRepository @Inject constructor(
    private val downloadTaskDao: DownloadTaskDao
) {
    fun observeTasks(): Flow<List<DownloadTaskEntity>> = downloadTaskDao.observeAll()

    suspend fun insertTask(task: DownloadTaskEntity): Long = downloadTaskDao.upsert(task)

    suspend fun getTask(taskId: Long): DownloadTaskEntity? = downloadTaskDao.getById(taskId)

    suspend fun updateProgress(taskId: Long, downloadedSize: Long, status: DownloadStatus) {
        downloadTaskDao.updateProgress(taskId, downloadedSize, status)
    }

    suspend fun updateStatus(taskId: Long, status: DownloadStatus) {
        downloadTaskDao.updateStatus(taskId, status)
    }

    suspend fun updateTotalSize(taskId: Long, totalSize: Long) {
        downloadTaskDao.updateTotalSize(taskId, totalSize)
    }

    suspend fun deleteTask(taskId: Long) {
        downloadTaskDao.deleteById(taskId)
    }
}
