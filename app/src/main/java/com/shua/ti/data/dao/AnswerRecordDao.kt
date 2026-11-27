package com.shua.ti.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shua.ti.data.entity.AnswerRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface AnswerRecordDao {

    /**
     * 同一题、同一轮只保留一条记录：
     * 由 (bank_id, round_no, question_id) 的唯一索引配合 REPLACE 保证，
     * 对应 REQ-003 AC-004 的幂等要求。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<AnswerRecord>)

    @Query(
        "SELECT * FROM answer_record WHERE bank_id = :bankId AND round_no = :roundNo " +
            "ORDER BY question_id ASC"
    )
    fun observeRound(bankId: Long, roundNo: Int): Flow<List<AnswerRecord>>

    @Query(
        "SELECT * FROM answer_record WHERE bank_id = :bankId " +
            "ORDER BY round_no DESC, question_id ASC"
    )
    fun observeByBank(bankId: Long): Flow<List<AnswerRecord>>

    @Query(
        "SELECT * FROM answer_record WHERE bank_id = :bankId " +
            "ORDER BY round_no DESC, question_id ASC"
    )
    suspend fun findAllByBank(bankId: Long): List<AnswerRecord>

    @Query("DELETE FROM answer_record WHERE bank_id = :bankId AND round_no = :roundNo")
    suspend fun deleteRound(bankId: Long, roundNo: Int)

    @Query("DELETE FROM answer_record WHERE bank_id = :bankId")
    suspend fun deleteByBank(bankId: Long)

    @Query(
        "SELECT COUNT(*) FROM answer_record WHERE bank_id = :bankId AND round_no = :roundNo"
    )
    suspend fun countRound(bankId: Long, roundNo: Int): Int
}
