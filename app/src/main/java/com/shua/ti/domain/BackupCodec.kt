package com.shua.ti.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupQuestion(
    val stem: String,
    @SerialName("option_a") val optionA: String,
    @SerialName("option_b") val optionB: String,
    @SerialName("option_c") val optionC: String,
    @SerialName("option_d") val optionD: String,
    val answer: String,
    val position: Int,
)

@Serializable
data class BackupRecord(
    @SerialName("round_no") val roundNo: Int,
    /** 用题目的 position 定位，而不是旧库的 question_id：还原后自增 ID 会变。 */
    @SerialName("position") val position: Int,
    @SerialName("chosen_option") val chosenOption: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("answered_at") val answeredAt: Long,
)

@Serializable
data class BackupProgress(
    @SerialName("completed_rounds") val completedRounds: Int,
    @SerialName("next_round_start") val nextRoundStart: Int,
    @SerialName("last_round_size") val lastRoundSize: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class BackupBank(
    @SerialName("source_file_name") val sourceFileName: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("imported_at") val importedAt: Long,
    val questions: List<BackupQuestion>,
    val progress: BackupProgress? = null,
    val records: List<BackupRecord> = emptyList(),
)

@Serializable
data class BackupPayload(
    @SerialName("backup_type") val backupType: String = BACKUP_TYPE,
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    val banks: List<BackupBank>,
) {
    companion object {
        const val BACKUP_TYPE = "shuati_backup"
        const val FORMAT_VERSION = 1
    }
}

sealed interface DecodeOutcome {
    data class Ok(val payload: BackupPayload) : DecodeOutcome
    data class Err(val message: String) : DecodeOutcome
}

/**
 * 备份文件与题库 JSON 是两种文件：本类只处理备份格式。
 *
 * 按 v0.2 决策不做版本号预检：format_version 仅随文件写出，
 * 还原时直接按结构解析，结构不符即报错（E_RESTORE_PARSE_FAILED）。
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload): String =
        json.encodeToString(BackupPayload.serializer(), payload)

    fun decode(content: String): DecodeOutcome {
        val payload = try {
            json.decodeFromString<BackupPayload>(content)
        } catch (e: Exception) {
            return DecodeOutcome.Err("备份文件无法解析：${e.message ?: "格式不符"}")
        }
        if (payload.backupType != BackupPayload.BACKUP_TYPE) {
            return DecodeOutcome.Err("不是本 App 的备份文件")
        }
        if (payload.banks.isEmpty()) {
            return DecodeOutcome.Err("备份里没有题库")
        }
        payload.banks.forEachIndexed { index, bank ->
            val no = index + 1
            if (bank.questions.isEmpty()) {
                return DecodeOutcome.Err("第 $no 个题库没有题目")
            }
            bank.questions.forEachIndexed { qIndex, q ->
                if (q.answer !in setOf("A", "B", "C", "D")) {
                    return DecodeOutcome.Err(
                        "第 $no 个题库的第 ${qIndex + 1} 题答案非法：${q.answer}",
                    )
                }
            }
        }
        return DecodeOutcome.Ok(payload)
    }
}
