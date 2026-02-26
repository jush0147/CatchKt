package com.catchkt.sniffer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY id DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET downloaded_size = :downloadedSize, status = :status WHERE id = :taskId")
    suspend fun updateProgress(taskId: Long, downloadedSize: Long, status: DownloadStatus)

    @Query("UPDATE download_tasks SET status = :status WHERE id = :taskId")
    suspend fun updateStatus(taskId: Long, status: DownloadStatus)

    @Query("UPDATE download_tasks SET total_size = :totalSize WHERE id = :taskId")
    suspend fun updateTotalSize(taskId: Long, totalSize: Long)

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)
}
