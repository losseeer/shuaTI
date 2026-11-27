package com.shua.ti.domain

import com.shua.ti.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizGraderTest {

    private fun q(id: Long, answer: String) = Question(
        questionId = id,
        bankId = 1,
        stem = "q$id",
        optionA = "a",
        optionB = "b",
        optionC = "c",
        optionD = "d",
        answer = answer,
        position = id.toInt(),
    )

    private val questions = listOf(
        q(1, "A"),
        q(2, "B"),
        q(3, "C"),
    )

    @Test
    fun `all correct`() {
        val r = QuizGrader.grade(questions, mapOf(1L to "A", 2L to "B", 3L to "C"))
        assertEquals(3, r.correct)
        assertEquals(0, r.unanswered)
        assertTrue(r.rows.all { it.correct })
    }

    @Test
    fun `one wrong`() {
        val r = QuizGrader.grade(questions, mapOf(1L to "A", 2L to "C", 3L to "C"))
        assertEquals(2, r.correct)
        assertFalse(r.rows[1].correct)
    }

    @Test
    fun `unanswered counted as wrong and marked U`() {
        val r = QuizGrader.grade(questions, mapOf(1L to "A"))
        assertEquals(1, r.correct)
        assertEquals(2, r.unanswered)
        assertEquals(QuizGrader.UNANSWERED, r.rows[1].chosen)
        assertEquals(QuizGrader.UNANSWERED, r.rows[2].chosen)
        assertFalse(r.rows[1].correct)
    }

    @Test
    fun `lowercase answer is normalized`() {
        val r = QuizGrader.grade(questions, mapOf(1L to "a"))
        assertEquals(1, r.correct)
    }

    @Test
    fun `empty answers means all unanswered`() {
        val r = QuizGrader.grade(questions, emptyMap())
        assertEquals(0, r.correct)
        assertEquals(3, r.unanswered)
    }

    @Test
    fun `rows keep question order`() {
        val r = QuizGrader.grade(questions, emptyMap())
        assertEquals(listOf(1L, 2L, 3L), r.rows.map { it.questionId })
    }
}
