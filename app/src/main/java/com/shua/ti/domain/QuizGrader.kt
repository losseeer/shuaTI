package com.shua.ti.domain

import com.shua.ti.data.entity.Question

data class GradedRow(
    val questionId: Long,
    val chosen: String,
    val answer: String,
    val correct: Boolean,
)

data class GradeResult(
    val rows: List<GradedRow>,
    val correct: Int,
    val unanswered: Int,
)

/**
 * 纯判分，不碰数据库，便于 JVM 单测。
 *
 * 未作答统一记为 "U" 并计为答错（REQ-002 AC-004），
 * 正确数只统计「已答且答对」。
 */
object QuizGrader {

    const val UNANSWERED = "U"

    fun grade(questions: List<Question>, answers: Map<Long, String>): GradeResult {
        var correct = 0
        var unanswered = 0
        val rows = questions.map { q ->
            val chosen = answers[q.questionId]?.uppercase() ?: UNANSWERED
            if (chosen == UNANSWERED) unanswered++
            val ok = chosen == q.answer
            if (ok) correct++
            GradedRow(
                questionId = q.questionId,
                chosen = chosen,
                answer = q.answer,
                correct = ok,
            )
        }
        return GradeResult(rows = rows, correct = correct, unanswered = unanswered)
    }
}
