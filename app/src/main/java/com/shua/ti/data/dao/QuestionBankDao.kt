package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shua.ti.data.entity.QuestionBank
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionBankDao {

    @Insert
    suspend fun insert(bank: QuestionBank): Long

    @Query("SELECT * FROM question_bank ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<QuestionBank>>

    @Query("SELECT * FROM question_bank ORDER BY imported_at DESC")
    suspend fun findAll(): List<QuestionBank>

    @Query("SELECT * FROM question_bank WHERE bank_id = :bankId")
    suspend fun findById(bankId: Long): QuestionBank?

    @Query("SELECT * FROM question_bank WHERE source_file_name = :fileName LIMIT 1")
    suspend fun findBySourceFile(fileName: String): QuestionBank?

    @Query("UPDATE question_bank SET source_file_name = :name WHERE bank_id = :bankId")
    suspend fun rename(bankId: Long, name: String)

    @Query("DELETE FROM question_bank WHERE bank_id = :bankId")
    suspend fun deleteById(bankId: Long)
}
