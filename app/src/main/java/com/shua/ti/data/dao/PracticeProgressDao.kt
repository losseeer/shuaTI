package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shua.ti.data.entity.PracticeProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeProgressDao {

    /**
     * bank_id 是主键，REPLACE 保证一个题库只有一条进度记录。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PracticeProgress)

    @Query("SELECT * FROM practice_progress WHERE bank_id = :bankId")
    fun observe(bankId: Long): Flow<PracticeProgress?>

    @Query("SELECT * FROM practice_progress WHERE bank_id = :bankId")
    suspend fun find(bankId: Long): PracticeProgress?

    @Query("DELETE FROM practice_progress WHERE bank_id = :bankId")
    suspend fun delete(bankId: Long)
}
