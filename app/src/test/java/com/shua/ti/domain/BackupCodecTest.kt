package com.shua.ti.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun question(answer: String = "A") = BackupQuestion(
        stem = "1 + 1 = ?",
        optionA = "1",
        optionB = "2",
        optionC = "3",
        optionD = "4",
        answer = answer,
        position = 1,
    )

    private fun payload(answer: String = "A") = BackupPayload(
        exportedAt = 1_700_000_000_000,
        banks = listOf(
            BackupBank(
                sourceFileName = "sample.json",
                schemaVersion = 1,
                importedAt = 1_700_000_000_000,
                questions = listOf(question(answer)),
                progress = BackupProgress(
                    completedRounds = 1,
                    nextRoundStart = 2,
                    lastRoundSize = 1,
                    updatedAt = 1_700_000_000_000,
                ),
                records = listOf(
                    BackupRecord(
                        roundNo = 1,
                        position = 1,
                        chosenOption = "A",
                        isCorrect = true,
                        answeredAt = 1_700_000_000_000,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `encode then decode round trips`() {
        val encoded = BackupCodec.encode(payload())
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is DecodeOutcome.Ok)
        val p = (decoded as DecodeOutcome.Ok).payload
        assertEquals(1, p.banks.size)
        assertEquals("sample.json", p.banks[0].sourceFileName)
        assertEquals(1, p.banks[0].questions.size)
        assertEquals("A", p.banks[0].questions[0].answer)
        assertEquals(1, p.banks[0].records.size)
        assertEquals(1, p.banks[0].records[0].position)
    }

    @Test
    fun `invalid json is rejected`() {
        val decoded = BackupCodec.decode("{ broken")
        assertTrue(decoded is DecodeOutcome.Err)
    }

    @Test
    fun `wrong backup type is rejected`() {
        val encoded = BackupCodec.encode(payload()).replace("shuati_backup", "other_backup")
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is DecodeOutcome.Err)
    }

    @Test
    fun `empty banks is rejected`() {
        val encoded = BackupCodec.encode(BackupPayload(exportedAt = 1, banks = emptyList()))
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is DecodeOutcome.Err)
    }

    @Test
    fun `invalid answer inside backup is rejected`() {
        val encoded = BackupCodec.encode(payload(answer = "E"))
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is DecodeOutcome.Err)
        assertTrue((decoded as DecodeOutcome.Err).message.contains("答案非法"))
    }

    @Test
    fun `empty question list inside bank is rejected`() {
        val encoded = BackupCodec.encode(
            BackupPayload(
                exportedAt = 1,
                banks = listOf(
                    BackupBank(
                        sourceFileName = "x.json",
                        schemaVersion = 1,
                        importedAt = 1,
                        questions = emptyList(),
                    ),
                ),
            ),
        )
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is DecodeOutcome.Err)
    }

    @Test
    fun `explanation survives round trip`() {
        val question = BackupQuestion(
            stem = "1 + 1 = ?",
            optionA = "1",
            optionB = "2",
            optionC = "3",
            optionD = "4",
            answer = "B",
            position = 1,
            explanation = "一加一等于二",
        )
        val encoded = BackupCodec.encode(
            BackupPayload(
                exportedAt = 1,
                banks = listOf(
                    BackupBank(
                        sourceFileName = "e.json",
                        schemaVersion = 1,
                        importedAt = 1,
                        questions = listOf(question),
                    ),
                ),
            ),
        )
        val decoded = BackupCodec.decode(encoded) as DecodeOutcome.Ok
        assertEquals("一加一等于二", decoded.payload.banks[0].questions[0].explanation)
    }

    @Test
    fun `backup without explanation still decodes`() {
        // 模拟 0.1.1 及更早导出的备份：题目里没有 explanation 字段
        val legacy = """
            {
              "backup_type": "shuati_backup",
              "format_version": 1,
              "exported_at": 1700000000000,
              "banks": [
                {
                  "source_file_name": "old.json",
                  "schema_version": 1,
                  "imported_at": 1700000000000,
                  "questions": [
                    {
                      "stem": "1 + 1 = ?",
                      "option_a": "1",
                      "option_b": "2",
                      "option_c": "3",
                      "option_d": "4",
                      "answer": "B",
                      "position": 1
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(legacy)
        assertTrue(decoded is DecodeOutcome.Ok)
        assertEquals("", (decoded as DecodeOutcome.Ok).payload.banks[0].questions[0].explanation)
    }
}
