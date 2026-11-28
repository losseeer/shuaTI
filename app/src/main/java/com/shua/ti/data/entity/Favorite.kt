package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 收藏的题。question_id 全局唯一，直接作主键；
 * 题目被移除时随外键级联删除，不留下指向空题的收藏。
 */
@Entity(
    tableName = "favorite",
    foreignKeys = [
        ForeignKey(
            entity = Question::class,
            parentColumns = ["question_id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Favorite(
    @PrimaryKey
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
