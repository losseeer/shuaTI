package com.shua.ti.domain

import androidx.room.withTransaction
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.dao.AnswerRecordDao
import com.shua.ti.data.dao.BackupFileDao
import com.shua.ti.data.dao.PracticeProgressDao
import com.shua.ti.data.dao.QuestionBankDao
import com.shua.ti.data.dao.QuestionDao
import com.shua.ti.data.entity.AnswerRecord
import com.shua.ti.data.entity.BackupFile
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank

enum class BackupErrorCode {
    NO_DATA,
    FILE_EXISTS,
    WRITE_FAILED,
    REPEATED_EXPORT,
    RESTORE_PARSE_FAILED,
}

sealed interface ExportResult {
    data class Success(
        val content: String,
        val fileName: String,
        val questionCount: Int,
        val roundCount: Int,
        val verificationHint: String,
    ) : ExportResult

    data class Failure(
        val code: BackupErrorCode,
        val message: String,
    ) : ExportResult
}

sealed interface RestoreResult {
    data class Success(
        val bankCount: Int,
        val questionCount: Int,
        val roundCount: Int,
    ) : RestoreResult

    data class Failure(
        val code: BackupErrorCode,
        val message: String,
    ) : RestoreResult
}

/**
 * IF-004 的落地实现。
 *
 * 按 v0.2 决策：导出后不做程序内建校验，只在成功信息里给出
 * 「可导入这个备份验证是否可还原」的提示；还原直接按结构解析。
 *
 * 还原不覆盖现有数据：每个备份里的题库都作为新题库插入，
 * 重名时加「(还原)」后缀。这样用户为验证而导入时不会破坏当前题库。
 */
class BackupService(
    private val db: AppDatabase,
    private val bankDao: QuestionBankDao,
    private val questionDao: QuestionDao,
    private val answerRecordDao: AnswerRecordDao,
    private val progressDao: PracticeProgressDao,
    private val backupFileDao: BackupFileDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun export(fileName: String = defaultFileName()): ExportResult {
        val banks = bankDao.findAll()
        if (banks.isEmpty()) {
            return ExportResult.Failure(BackupErrorCode.NO_DATA, "暂无可导出数据，不生成文件")
        }

        val backupBanks = banks.map { bank ->
            val questions = questionDao.page(bank.bankId, Int.MAX_VALUE, 0)
            val progress = progressDao.find(bank.bankId)
            val records = answerRecordDao.findAllByBank(bank.bankId)
            val idToPosition = questions.associate { it.questionId to it.position }
            BackupBank(
                sourceFileName = bank.sourceFileName,
                schemaVersion = bank.schemaVersion,
                importedAt = bank.importedAt,
                questions = questions.map { q ->
                    BackupQuestion(
                        stem = q.stem,
                        optionA = q.optionA,
                        optionB = q.optionB,
                        optionC = q.optionC,
                        optionD = q.optionD,
                        answer = q.answer,
                        position = q.position,
                    )
                },
                progress = progress?.let {
                    BackupProgress(
                        completedRounds = it.completedRounds,
                        nextRoundStart = it.nextRoundStart,
                        lastRoundSize = it.lastRoundSize,
                        updatedAt = it.updatedAt,
                    )
                },
                records = records.map { r ->
                    BackupRecord(
                        roundNo = r.roundNo,
                        position = idToPosition[r.questionId] ?: 0,
                        chosenOption = r.chosenOption,
                        isCorrect = r.isCorrect,
                        answeredAt = r.answeredAt,
                    )
                },
            )
        }

        val payload = BackupPayload(exportedAt = clock(), banks = backupBanks)
        val content = BackupCodec.encode(payload)
        val questionCount = backupBanks.sumOf { it.questions.size }
        val roundCount = backupBanks.sumOf { it.records.map { r -> r.roundNo }.distinct().size }

        return ExportResult.Success(
            content = content,
            fileName = fileName,
            questionCount = questionCount,
            roundCount = roundCount,
            verificationHint = "可导入这个备份，看是否能成功还原。",
        )
    }

    suspend fun recordExported(
        fileUri: String,
        fileName: String,
        result: ExportResult.Success,
    ) {
        backupFileDao.insert(
            BackupFile(
                fileUri = fileUri,
                fileName = fileName,
                questionCount = result.questionCount,
                roundCount = result.roundCount,
                exportedAt = clock(),
            ),
        )
    }

    suspend fun restore(content: String): RestoreResult {
        val decoded = BackupCodec.decode(content)
        if (decoded is DecodeOutcome.Err) {
            return RestoreResult.Failure(BackupErrorCode.RESTORE_PARSE_FAILED, decoded.message)
        }
        val payload = (decoded as DecodeOutcome.Ok).payload

        return try {
            var questionCount = 0
            var roundCount = 0
            db.withTransaction {
                payload.banks.forEach { bank ->
                    val name = uniqueName(bank.sourceFileName)
                    val bankId = bankDao.insert(
                        QuestionBank(
                            sourceFileName = name,
                            schemaVersion = bank.schemaVersion,
                            questionCount = bank.questions.size,
                            importedAt = clock(),
                        ),
                    )
                    val idMap = HashMap<Int, Long>()
                    val questions = bank.questions.map { q ->
                        Question(
                            bankId = bankId,
                            stem = q.stem,
                            optionA = q.optionA,
                            optionB = q.optionB,
                            optionC = q.optionC,
                            optionD = q.optionD,
                            answer = q.answer,
                            position = q.position,
                        )
                    }
                    val newIds = questionDao.insertAll(questions)
                    bank.questions.forEachIndexed { index, q ->
                        idMap[q.position] = newIds.getOrElse(index) { 0L }
                    }
                    questionCount += questions.size

                    if (bank.records.isNotEmpty()) {
                        bank.records.forEach { r ->
                            check(r.position in idMap) {
                                "答题记录引用了不存在的题号 ${r.position}"
                            }
                        }
                        answerRecordDao.insertAll(
                            bank.records.map { r ->
                                AnswerRecord(
                                    bankId = bankId,
                                    roundNo = r.roundNo,
                                    questionId = idMap[r.position] ?: 0L,
                                    chosenOption = r.chosenOption,
                                    isCorrect = r.isCorrect,
                                    answeredAt = r.answeredAt,
                                )
                            },
                        )
                        roundCount += bank.records.map { it.roundNo }.distinct().size
                    }

                    bank.progress?.let { p ->
                        progressDao.upsert(
                            PracticeProgress(
                                bankId = bankId,
                                completedRounds = p.completedRounds,
                                nextRoundStart = p.nextRoundStart,
                                lastRoundSize = p.lastRoundSize,
                                updatedAt = p.updatedAt,
                            ),
                        )
                    }
                }
            }
            RestoreResult.Success(
                bankCount = payload.banks.size,
                questionCount = questionCount,
                roundCount = roundCount,
            )
        } catch (e: Exception) {
            RestoreResult.Failure(
                BackupErrorCode.RESTORE_PARSE_FAILED,
                "还原失败，已保留本机现有数据：${e.message ?: "未知原因"}",
            )
        }
    }

    private suspend fun uniqueName(original: String): String {
        if (bankDao.findBySourceFile(original) == null) return original
        var index = 1
        while (true) {
            val candidate = "$original (还原${if (index == 1) "" else " $index"})"
            if (bankDao.findBySourceFile(candidate) == null) return candidate
            index++
        }
    }

    private fun defaultFileName(): String = "shuati-backup-${clock()}.json"
}
