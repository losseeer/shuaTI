package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一道题的作答与对错，按轮次归组供回看（REQ-003 AC-002）。
 * chosen_option 取值 A/B/C/D；未作答记为 U（REQ-002 AC-004）。
 * (bank_id, round_no, question_id) 唯一，保证同一轮重复交卷只有一条记录（AC-004）。
 */
@Entity(
    tableName = "answer_record",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBank::class,
            parentColumns = ["bank_id"],
            childColumns = ["bank_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bank_id", "round_no"]),
        Index(value = ["bank_id", "round_no", "question_id"], unique = true),
    ],
)
data class AnswerRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "record_id")
    val recordId: Long = 0,
    @ColumnInfo(name = "bank_id")
    val bankId: Long,
    @ColumnInfo(name = "round_no")
    val roundNo: Int,
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    @ColumnInfo(name = "chosen_option")
    val chosenOption: String,
    @ColumnInfo(name = "is_correct")
    val isCorrect: Boolean,
    @ColumnInfo(name = "answered_at")
    val answeredAt: Long,
    /** 轮次类型：NORMAL 顺序轮 / SPECIAL 错题与收藏轮 / REPLAY 整轮重刷。后两类不推进顺序进度。 */
    @ColumnInfo(name = "round_kind")
    val roundKind: String = "NORMAL",
)
