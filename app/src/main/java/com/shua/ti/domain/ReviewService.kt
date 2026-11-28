package com.shua.ti.domain

import androidx.room.withTransaction
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.dao.AnswerRecordDao
import com.shua.ti.data.dao.ExcludedQuestionDao
import com.shua.ti.data.dao.FavoriteDao
import com.shua.ti.data.dao.PracticeProgressDao
import com.shua.ti.data.dao.QuestionBankDao
import com.shua.ti.data.dao.QuestionDao
import com.shua.ti.data.entity.AnswerRecord
import com.shua.ti.data.entity.ExcludedQuestion
import com.shua.ti.data.entity.Favorite
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank

/**
 * 「错题与收藏题库」的领域逻辑。
 *
 * 错题集不是独立存储，而是从全部作答记录推导：每道题取最近一次作答，
 * 最近一次答错即在集内（答对自动移出）；被用户手工移出的题（excluded_question）
 * 不再参与推导。
 *
 * 常驻错题库是一行 is_special = 1 的 question_bank：作答记录挂它名下才能
 * 满足外键与回看；它不参与导入去重、备份与删除（由调用方保证）。
 */
class ReviewService(
    private val db: AppDatabase,
    private val bankDao: QuestionBankDao,
    private val questionDao: QuestionDao,
    private val answerRecordDao: AnswerRecordDao,
    private val progressDao: PracticeProgressDao,
    private val favoriteDao: FavoriteDao,
    private val excludedQuestionDao: ExcludedQuestionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 常驻错题库：不存在则创建，整个生命周期只有一行。 */
    suspend fun ensureSpecialBank(): QuestionBank =
        bankDao.findSpecial() ?: bankDao.insert(
            QuestionBank(
                sourceFileName = "错题与收藏题库",
                schemaVersion = 1,
                questionCount = 0,
                importedAt = clock(),
                isSpecial = true,
            ),
        ).let { bankDao.findSpecial()!! }

    /**
     * 当前错题集：全部题库的记录按 (bank_id, question_id) 取最近一次，
     * 最近答错且未被手工移出的题。
     */
    suspend fun wrongQuestions(): List<Question> {
        val last = latestResults()
        val excluded = excludedIds()
        val wrongIds = last.filterValues { !it }
            .keys.filter { it !in excluded }
            .map { it.second }
            .distinct()
        if (wrongIds.isEmpty()) return emptyList()
        return questionDao.findByIds(wrongIds).sortedBy { it.questionId }
    }

    /** 当前收藏的题，按收藏时间先后。 */
    suspend fun favoriteQuestions(): List<Question> {
        val ids = favoriteDao.findAllIds()
        if (ids.isEmpty()) return emptyList()
        return questionDao.findByIds(ids)
            .sortedBy { ids.indexOf(it.questionId) }
    }

    /** @return true 表示现在是已收藏状态。 */
    suspend fun toggleFavorite(questionId: Long): Boolean {
        val existing = favoriteDao.findAllIds().contains(questionId)
        return if (existing) {
            favoriteDao.deleteByQuestion(questionId)
            false
        } else {
            favoriteDao.insert(Favorite(questionId = questionId, createdAt = clock()))
            true
        }
    }

    /** 把题移出错题集：此后推导不再把它算作错题，即使将来再答错。 */
    suspend fun excludeFromWrong(bankId: Long, questionId: Long) {
        excludedQuestionDao.insert(
            ExcludedQuestion(bankId = bankId, questionId = questionId, createdAt = clock()),
        )
    }

    /** 取消手工移出（原型未做该入口，实施阶段暂无 UI 入口，供后续使用）。 */
    suspend fun restoreExcluded(bankId: Long, questionId: Long) {
        excludedQuestionDao.delete(bankId, questionId)
    }

    /**
     * 清空一个题库的作答记录与进度；当前错题自动转为收藏（用户已确认方案）。
     * @return 自动收藏的题数。
     */
    suspend fun clearProgress(bankId: Long): Int {
        return db.withTransaction {
            val last = latestResultsForBank(bankId)
            val excluded = excludedQuestionDao.findAll()
                .filter { it.bankId == bankId }
                .map { it.questionId }
                .toSet()
            val existingFavs = favoriteDao.findAllIds().toSet()
            val toFavor = last.filterValues { !it }
                .keys.filter { it !in excluded && it !in existingFavs }
            toFavor.forEach { qid ->
                favoriteDao.insert(Favorite(questionId = qid, createdAt = clock()))
            }
            answerRecordDao.deleteByBank(bankId)
            progressDao.delete(bankId)
            toFavor.size
        }
    }

    /**
     * 从题库移除一道题。作答记录保留（回看中仍可见），收藏随外键级联删除。
     */
    suspend fun removeQuestion(bankId: Long, questionId: Long) {
        questionDao.deleteById(bankId, questionId)
    }

    /**
     * 轮次回看的行：题目可能已被移除，缺失的题以 null 表示，
     * 界面显示「题目已移除」占位。
     */
    data class ReviewRow(
        val record: AnswerRecord,
        val question: Question?,
    )

    suspend fun roundRows(bankId: Long, roundNo: Int): List<ReviewRow> {
        val records = answerRecordDao.findAllByBank(bankId)
            .filter { it.roundNo == roundNo }
        val ids = records.map { it.questionId }
        val byId = if (ids.isEmpty()) emptyMap() else questionDao.findByIds(ids).associateBy { it.questionId }
        return records.map { ReviewRow(record = it, question = byId[it.questionId]) }
    }

    /**
     * 特殊库的下一轮题目：错题集 + 收藏，按题号去重。
     * @return 空列表表示暂无可刷的题。
     */
    suspend fun specialRoundQuestions(): List<Question> {
        val seen = HashSet<Long>()
        val list = ArrayList<Question>()
        wrongQuestions().forEach { q -> if (seen.add(q.questionId)) list.add(q) }
        favoriteQuestions().forEach { q -> if (seen.add(q.questionId)) list.add(q) }
        return list
    }

    /** 每题最近一次作答结果（全局）。 */
    private suspend fun latestResults(): Map<Pair<Long, Long>, Boolean> {
        val last = HashMap<Pair<Long, Long>, Boolean>()
        answerRecordDao.findAll()
            .sortedWith(compareBy({ it.bankId }, { it.questionId }, { it.answeredAt }, { it.roundNo }))
            .forEach { last[it.bankId to it.questionId] = it.isCorrect }
        return last
    }

    private suspend fun latestResultsForBank(bankId: Long): Map<Long, Boolean> {
        val last = HashMap<Long, Boolean>()
        answerRecordDao.findAllByBank(bankId)
            .sortedWith(compareBy({ it.questionId }, { it.answeredAt }, { it.roundNo }))
            .forEach { last[it.questionId] = it.isCorrect }
        return last
    }

    private suspend fun excludedIds(): Set<Pair<Long, Long>> =
        excludedQuestionDao.findAll().map { it.bankId to it.questionId }.toSet()

    companion object {
        const val SPECIAL_BANK_NAME = "错题与收藏题库"
    }
}
