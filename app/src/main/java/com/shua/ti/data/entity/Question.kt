package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题库中的一条四选一选择题。
 * answer 仅允许 A/B/C/D，由导入校验保证（REQ-001 AC-005）。
 * position 为同一题库内的取题顺序。
 * explanation 为可选解析，缺省为空串（不用 null，避免到处判空）。
 */
@Entity(
    tableName = "question",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBank::class,
            parentColumns = ["bank_id"],
            childColumns = ["bank_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bank_id", "position"])],
)
data class Question(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "question_id")
    val questionId: Long = 0,
    @ColumnInfo(name = "bank_id")
    val bankId: Long,
    @ColumnInfo(name = "stem")
    val stem: String,
    @ColumnInfo(name = "option_a")
    val optionA: String,
    @ColumnInfo(name = "option_b")
    val optionB: String,
    @ColumnInfo(name = "option_c")
    val optionC: String,
    @ColumnInfo(name = "option_d")
    val optionD: String,
    @ColumnInfo(name = "answer")
    val answer: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "explanation")
    val explanation: String = "",
)
