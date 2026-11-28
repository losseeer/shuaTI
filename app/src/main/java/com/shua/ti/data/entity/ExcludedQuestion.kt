package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 被用户手工移出错题集的题（管理页操作）。
 * 错题集由作答记录推导，推导时排除本表条目；
 * 没有这张表，被移出的题会在下次答错时重新回到错题集。
 * 题库删除时随外键级联清除。
 */
@Entity(
    tableName = "excluded_question",
    primaryKeys = ["bank_id", "question_id"],
    foreignKeys = [
        ForeignKey(
            entity = QuestionBank::class,
            parentColumns = ["bank_id"],
            childColumns = ["bank_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["question_id"])],
)
data class ExcludedQuestion(
    @ColumnInfo(name = "bank_id")
    val bankId: Long,
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
