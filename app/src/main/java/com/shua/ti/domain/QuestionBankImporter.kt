package com.shua.ti.domain

import androidx.room.withTransaction
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.dao.QuestionBankDao
import com.shua.ti.data.dao.QuestionDao
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank

/**
 * IF-001 的落地实现：解析 → 校验 → 事务写入。
 *
 * 约束：
 * - 任一题不合法即整份拒绝，不写入任何题目（REQ-001 AC-004 / AC-005）。
 * - 同一来源文件重复导入时返回 DUPLICATE_IMPORT，由界面让用户选择覆盖或追加；
 *   本类不自行决定，避免静默覆盖。
 * - 同一文件 3 秒内重复触发只执行一次（AC-006）。
 */
class QuestionBankImporter(
    private val db: AppDatabase,
    private val bankDao: QuestionBankDao,
    private val questionDao: QuestionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lastTriggerAt = HashMap<String, Long>()

    suspend fun import(fileName: String, content: String?): ImportResult {
        val now = clock()
        val previous = lastTriggerAt[fileName]
        if (previous != null && now - previous < REPEAT_WINDOW_MS) {
            return ImportResult.Failure(
                ImportErrorCode.REPEATED_TRIGGER,
                "同一文件 3 秒内重复触发，只执行一次写入",
            )
        }
        lastTriggerAt[fileName] = now

        if (content == null) {
            return ImportResult.Failure(
                ImportErrorCode.FILE_UNREADABLE,
                "文件已被删除或位于受限目录，请重新选择",
            )
        }

        val outcome = QuestionBankParser.parse(content)
        if (outcome is ParseOutcome.Err) {
            return ImportResult.Failure(outcome.code, outcome.message, outcome.location)
        }
        val bank = (outcome as ParseOutcome.Ok).bank

        if (bankDao.findBySourceFile(fileName) != null) {
            return ImportResult.Failure(
                ImportErrorCode.DUPLICATE_IMPORT,
                "来源文件已存在",
            )
        }

        val bankId = db.withTransaction {
            val id = bankDao.insert(
                QuestionBank(
                    sourceFileName = fileName,
                    schemaVersion = bank.schemaVersion,
                    questionCount = bank.questions.size,
                    importedAt = now,
                ),
            )
            questionDao.insertAll(
                bank.questions.map { q ->
                    Question(
                        bankId = id,
                        stem = q.stem,
                        optionA = q.optionA,
                        optionB = q.optionB,
                        optionC = q.optionC,
                        optionD = q.optionD,
                        answer = q.answer,
                        position = q.position,
                    )
                },
            )
            id
        }

        val ids = questionDao.idsByBank(bankId)
        return ImportResult.Success(
            bankId = bankId,
            importedCount = bank.questions.size,
            questionIds = ids,
        )
    }

    private companion object {
        const val REPEAT_WINDOW_MS = 3_000L
    }
}
