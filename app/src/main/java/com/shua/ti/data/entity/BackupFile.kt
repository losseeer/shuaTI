package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本机导出文件的元数据索引，文件本体由用户保管（REQ-004）。
 * file_name 记用户保存时最终用的文件名（系统保存对话框可编辑），
 * 列表用它 + 导出时间展示，不直接暴露 content uri。
 * 不设 backup_version：按 v0.2 决策，不做版本号预检，
 * 还原时直接按文件结构解析，失败走可见错误。
 */
@Entity(
    tableName = "backup_file",
    indices = [Index(value = ["exported_at"])],
)
data class BackupFile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "backup_id")
    val backupId: Long = 0,
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "question_count")
    val questionCount: Int,
    @ColumnInfo(name = "round_count")
    val roundCount: Int,
    @ColumnInfo(name = "exported_at")
    val exportedAt: Long,
)
