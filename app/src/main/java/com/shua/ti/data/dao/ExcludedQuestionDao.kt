package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shua.ti.data.entity.ExcludedQuestion
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcludedQuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(excluded: ExcludedQuestion)

    @Query("DELETE FROM excluded_question WHERE bank_id = :bankId AND question_id = :questionId")
    suspend fun delete(bankId: Long, questionId: Long)

    @Query("SELECT * FROM excluded_question")
    suspend fun findAll(): List<ExcludedQuestion>

    @Query("SELECT * FROM excluded_question")
    fun observeAll(): Flow<List<ExcludedQuestion>>
}
