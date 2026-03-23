package com.catchkt.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.catchkt.data.model.DownloadStatus
import com.catchkt.data.model.DownloadTask
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Insert
    suspend fun insert(task: DownloadTask): Long

    @Update
    suspend fun update(task: DownloadTask)

    @Query("SELECT * FROM download_tasks ORDER BY id DESC")
    fun getAllTasks(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): DownloadTask?

    @Query("UPDATE download_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("UPDATE download_tasks SET downloaded_size = :downloadedSize WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedSize: Long)

    @Query("UPDATE download_tasks SET downloaded_size = :downloadedSize, status = :status WHERE id = :id")
    suspend fun updateProgressAndStatus(id: Long, downloadedSize: Long, status: DownloadStatus)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
