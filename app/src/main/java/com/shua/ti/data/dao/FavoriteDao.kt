package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shua.ti.data.entity.Favorite
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: Favorite)

    @Query("DELETE FROM favorite WHERE question_id = :questionId")
    suspend fun deleteByQuestion(questionId: Long)

    @Query("SELECT question_id FROM favorite ORDER BY created_at ASC, question_id ASC")
    suspend fun findAllIds(): List<Long>

    @Query("SELECT question_id FROM favorite ORDER BY created_at ASC, question_id ASC")
    fun observeAllIds(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM favorite")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM favorite WHERE question_id IN (:questionIds)")
    suspend fun countIn(questionIds: List<Long>): Int
}
