package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shua.ti.data.entity.Question
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    @Insert
    suspend fun insertAll(questions: List<Question>): List<Long>

    @Query("SELECT * FROM question WHERE bank_id = :bankId ORDER BY position ASC")
    fun observeByBank(bankId: Long): Flow<List<Question>>

    @Query(
        "SELECT * FROM question WHERE bank_id = :bankId " +
            "ORDER BY position ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun page(bankId: Long, limit: Int, offset: Int): List<Question>

    @Query("SELECT COUNT(*) FROM question WHERE bank_id = :bankId")
    suspend fun countByBank(bankId: Long): Int

    @Query("SELECT question_id FROM question WHERE bank_id = :bankId ORDER BY position ASC")
    suspend fun idsByBank(bankId: Long): List<Long>

    @Query("SELECT * FROM question WHERE question_id = :questionId")
    suspend fun findById(questionId: Long): Question?

    @Query("SELECT * FROM question WHERE question_id IN (:questionIds)")
    suspend fun findByIds(questionIds: List<Long>): List<Question>

    @Query("DELETE FROM question WHERE bank_id = :bankId AND question_id = :questionId")
    suspend fun deleteById(bankId: Long, questionId: Long)
}
