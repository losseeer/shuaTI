package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 每个题库的断点位置与已完成轮次数（REQ-003）。
 * 一个题库一条，重复交卷不新增记录（AC-004）。
 */
@Entity(
    tableName = "practice_progress",
    foreignKeys = [
        ForeignKey(
            entity = QuestionBank::class,
            parentColumns = ["bank_id"],
            childColumns = ["bank_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PracticeProgress(
    @PrimaryKey
    @ColumnInfo(name = "bank_id")
    val bankId: Long,
    @ColumnInfo(name = "completed_rounds")
    val completedRounds: Int,
    @ColumnInfo(name = "next_round_start")
    val nextRoundStart: Int,
    @ColumnInfo(name = "last_round_size")
    val lastRoundSize: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
