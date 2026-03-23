package com.catchkt.data.repository

import com.catchkt.data.db.DownloadTaskDao
import com.catchkt.data.model.DownloadStatus
import com.catchkt.data.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadTaskDao
) {
    fun getAllTasks(): Flow<List<DownloadTask>> = dao.getAllTasks()

    suspend fun insertTask(task: DownloadTask): Long = dao.insert(task)

    suspend fun getTaskById(id: Long): DownloadTask? = dao.getTaskById(id)

    suspend fun updateStatus(id: Long, status: DownloadStatus) = dao.updateStatus(id, status)

    suspend fun updateProgress(id: Long, downloadedSize: Long) = dao.updateProgress(id, downloadedSize)

    suspend fun updateProgressAndStatus(id: Long, downloadedSize: Long, status: DownloadStatus) =
        dao.updateProgressAndStatus(id, downloadedSize, status)

    suspend fun deleteTask(id: Long) = dao.deleteById(id)
}
