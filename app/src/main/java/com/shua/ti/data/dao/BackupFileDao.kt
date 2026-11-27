package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shua.ti.data.entity.BackupFile
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupFileDao {

    @Insert
    suspend fun insert(file: BackupFile): Long

    @Query("SELECT * FROM backup_file ORDER BY exported_at DESC")
    fun observeAll(): Flow<List<BackupFile>>

    @Query("SELECT * FROM backup_file WHERE backup_id = :backupId")
    suspend fun findById(backupId: Long): BackupFile?

    @Query("DELETE FROM backup_file WHERE backup_id = :backupId")
    suspend fun deleteById(backupId: Long)
}
