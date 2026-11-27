package com.shua.ti.domain

import androidx.room.withTransaction
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.dao.AnswerRecordDao
import com.shua.ti.data.dao.PracticeProgressDao
import com.shua.ti.data.dao.QuestionBankDao
import com.shua.ti.data.dao.QuestionDao
import com.shua.ti.data.entity.AnswerRecord
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question

enum class QuizErrorCode {
    EMPTY_BANK,
    INSUFFICIENT_QUESTIONS,
    UNANSWERED_CONFIRM,
    DOUBLE_SUBMIT,
    WRITE_FAILED,
    BANK_DELETED,
}

sealed interface RoundStart {
    data class Started(
        val bankId: Long,
        val roundNo: Int,
        val requestedSize: Int,
        val actualSize: Int,
        val questions: List<Question>,
    ) : RoundStart {
        val insufficient: Boolean get() = actualSize < requestedSize
    }

    data class Failed(val code: QuizErrorCode, val message: String) : RoundStart
}

sealed interface SubmitResult {
    /** 有未作答题，等用户确认；确认后带 confirmed = true 重新提交。 */
    data class NeedConfirm(val unanswered: Int) : SubmitResult

    data class Graded(
        val bankId: Long,
        val roundNo: Int,
        val correct: Int,
        val size: Int,
        val rows: List<GradedRow>,
        val nextRoundStart: Int,
    ) : SubmitResult

    data class Failed(val code: QuizErrorCode, val message: String) : SubmitResult
}

/**
 * IF-002 / IF-003 的落地实现。
 *
 * 关键约束：
 * - 一轮一存：交卷判分完成后才写 AnswerRecord 与 PracticeProgress（REQ-003）。
 * - 同一轮重复交卷不产生第二条记录（AC-004），依靠唯一索引 + REPLACE。
 * - 有未作答时不直接判分，先返回 NeedConfirm（REQ-002 AC-004）。
 * - 写入异常暴露为 WRITE_FAILED，不静默吞掉（REQ-003 AC-006）。
 */
class QuizEngine(
    private val db: AppDatabase,
    private val bankDao: QuestionBankDao,
    private val questionDao: QuestionDao,
    private val answerRecordDao: AnswerRecordDao,
    private val progressDao: PracticeProgressDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun startRound(bankId: Long, requestedSize: Int): RoundStart {
        val bank = bankDao.findById(bankId)
            ?: return RoundStart.Failed(QuizErrorCode.EMPTY_BANK, "题库不存在或已删除")

        val count = questionDao.countByBank(bankId)
        if (count == 0) {
            return RoundStart.Failed(QuizErrorCode.EMPTY_BANK, "题库中没有题目")
        }

        val progress = progressDao.find(bankId)
        val offset = ((progress?.nextRoundStart ?: 1) - 1).coerceAtLeast(0)
        val remaining = count - offset
        if (remaining <= 0) {
            return RoundStart.Failed(QuizErrorCode.EMPTY_BANK, "该题库已刷完")
        }

        val actual = minOf(requestedSize, remaining)
        val questions = questionDao.page(bankId, actual, offset)
        val roundNo = (progress?.completedRounds ?: 0) + 1
        return RoundStart.Started(
            bankId = bankId,
            roundNo = roundNo,
            requestedSize = requestedSize,
            actualSize = actual,
            questions = questions,
        )
    }

    suspend fun submit(
        bankId: Long,
        roundNo: Int,
        questions: List<Question>,
        answers: Map<Long, String>,
        confirmedUnanswered: Boolean,
    ): SubmitResult {
        if (bankDao.findById(bankId) == null) {
            return SubmitResult.Failed(QuizErrorCode.BANK_DELETED, "题库已删除，本轮作废")
        }

        val existing = answerRecordDao.countRound(bankId, roundNo)
        if (existing > 0) {
            return SubmitResult.Failed(QuizErrorCode.DOUBLE_SUBMIT, "本轮已交卷，结果不变")
        }

        val grade = QuizGrader.grade(questions, answers)
        if (grade.unanswered > 0 && !confirmedUnanswered) {
            return SubmitResult.NeedConfirm(grade.unanswered)
        }

        val now = clock()
        return try {
            db.withTransaction {
                answerRecordDao.insertAll(
                    grade.rows.map { row ->
                        AnswerRecord(
                            bankId = bankId,
                            roundNo = roundNo,
                            questionId = row.questionId,
                            chosenOption = row.chosen,
                            isCorrect = row.correct,
                            answeredAt = now,
                        )
                    },
                )
                val previous = progressDao.find(bankId)
                val nextStart = (previous?.nextRoundStart ?: 1) + questions.size
                progressDao.upsert(
                    PracticeProgress(
                        bankId = bankId,
                        completedRounds = roundNo,
                        nextRoundStart = nextStart,
                        lastRoundSize = questions.size,
                        updatedAt = now,
                    ),
                )
                nextStart
            }.let { nextStart ->
                SubmitResult.Graded(
                    bankId = bankId,
                    roundNo = roundNo,
                    correct = grade.correct,
                    size = questions.size,
                    rows = grade.rows,
                    nextRoundStart = nextStart,
                )
            }
        } catch (e: Exception) {
            SubmitResult.Failed(
                QuizErrorCode.WRITE_FAILED,
                "本机存储写入失败：${e.message ?: "未知原因"}",
            )
        }
    }

    /**
     * 删除题库时一并清除进度与答题记录（REQ-003 AC-005）。
     * 外键已设 CASCADE，这里显式删一次以保证语义清晰。
     */
    suspend fun deleteBank(bankId: Long) {
        db.withTransaction {
            answerRecordDao.deleteByBank(bankId)
            progressDao.delete(bankId)
            bankDao.deleteById(bankId)
        }
    }
}
