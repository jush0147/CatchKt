package com.catchkt.data.db

import androidx.room.TypeConverter
import com.catchkt.data.model.DownloadStatus

class Converters {
    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): Int = status.ordinal

    @TypeConverter
    fun toDownloadStatus(value: Int): DownloadStatus = DownloadStatus.entries[value]
}
