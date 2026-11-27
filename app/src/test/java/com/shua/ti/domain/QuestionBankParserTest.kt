package com.shua.ti.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionBankParserTest {

    private val validQuestion = """
        {
          "stem": "1 + 1 = ?",
          "option_a": "1",
          "option_b": "2",
          "option_c": "3",
          "option_d": "4",
          "answer": "B"
        }
    """.trimIndent()

    private fun bank(vararg questions: String) =
        """{ "schema_version": 1, "questions": [ ${questions.joinToString(",")} ] }"""

    @Test
    fun `parses valid object form`() {
        val outcome = QuestionBankParser.parse(bank(validQuestion))
        assertTrue(outcome is ParseOutcome.Ok)
        val parsed = (outcome as ParseOutcome.Ok).bank
        assertEquals(1, parsed.schemaVersion)
        assertEquals(1, parsed.questions.size)
        assertEquals("1 + 1 = ?", parsed.questions[0].stem)
        assertEquals("B", parsed.questions[0].answer)
        assertEquals(1, parsed.questions[0].position)
    }

    @Test
    fun `parses bare array form`() {
        val outcome = QuestionBankParser.parse("[$validQuestion]")
        assertTrue(outcome is ParseOutcome.Ok)
        assertEquals(1, (outcome as ParseOutcome.Ok).bank.questions.size)
    }

    @Test
    fun `answer is normalized to uppercase`() {
        val lower = validQuestion.replace("\"B\"", "\"b\"")
        val outcome = QuestionBankParser.parse(bank(lower)) as ParseOutcome.Ok
        assertEquals("B", outcome.bank.questions[0].answer)
    }

    @Test
    fun `invalid json returns INVALID_JSON`() {
        val outcome = QuestionBankParser.parse("{ not json")
        assertEquals(ImportErrorCode.INVALID_JSON, (outcome as ParseOutcome.Err).code)
    }

    @Test
    fun `missing questions array returns MISSING_FIELD`() {
        val outcome = QuestionBankParser.parse("""{ "schema_version": 1 }""")
        assertEquals(ImportErrorCode.MISSING_FIELD, (outcome as ParseOutcome.Err).code)
    }

    @Test
    fun `missing option points at question and field`() {
        val broken = validQuestion.replace("\"option_c\": \"3\",", "")
        val outcome = QuestionBankParser.parse(bank(broken)) as ParseOutcome.Err
        assertEquals(ImportErrorCode.MISSING_FIELD, outcome.code)
        assertEquals("第 1 题 · option_c", outcome.location)
    }

    @Test
    fun `second question missing stem reports question two`() {
        val broken = validQuestion.replace("\"stem\": \"1 + 1 = ?\",", "")
        val outcome = QuestionBankParser.parse(bank(validQuestion, broken)) as ParseOutcome.Err
        assertEquals(ImportErrorCode.MISSING_FIELD, outcome.code)
        assertEquals("第 2 题 · stem", outcome.location)
    }

    @Test
    fun `invalid answer returns INVALID_ANSWER`() {
        val bad = validQuestion.replace("\"B\"", "\"E\"")
        val outcome = QuestionBankParser.parse(bank(bad)) as ParseOutcome.Err
        assertEquals(ImportErrorCode.INVALID_ANSWER, outcome.code)
        assertEquals("第 1 题 · answer", outcome.location)
    }

    @Test
    fun `empty questions returns MISSING_FIELD`() {
        val outcome = QuestionBankParser.parse("""{ "questions": [] }""") as ParseOutcome.Err
        assertEquals(ImportErrorCode.MISSING_FIELD, outcome.code)
    }

    @Test
    fun `blank field is treated as missing`() {
        val blank = validQuestion.replace("\"1 + 1 = ?\"", "\"   \"")
        val outcome = QuestionBankParser.parse(bank(blank)) as ParseOutcome.Err
        assertEquals(ImportErrorCode.MISSING_FIELD, outcome.code)
        assertEquals("第 1 题 · stem", outcome.location)
    }
}
