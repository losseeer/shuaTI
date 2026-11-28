package com.shua.ti.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次导入形成的题库。
 * source_file_name 用于重复导入识别（REQ-001 AC-003），
 * schema_version 对应题库格式版本边界。
 */
@Entity(
    tableName = "question_bank",
    indices = [Index(value = ["source_file_name"])],
)
data class QuestionBank(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "bank_id")
    val bankId: Long = 0,
    @ColumnInfo(name = "source_file_name")
    val sourceFileName: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "question_count")
    val questionCount: Int,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
    /** 常驻的「错题与收藏题库」标记：不参与导入去重、备份与删除。 */
    @ColumnInfo(name = "is_special")
    val isSpecial: Boolean = false,
)
