package com.shua.ti

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shua.ti.data.AppDatabase
import com.shua.ti.domain.BackupService
import com.shua.ti.domain.ExportResult
import com.shua.ti.domain.ImportErrorCode
import com.shua.ti.domain.ImportResult
import com.shua.ti.domain.QuestionBankImporter
import com.shua.ti.domain.QuizEngine
import com.shua.ti.domain.QuizErrorCode
import com.shua.ti.domain.RestoreResult
import com.shua.ti.domain.RoundStart
import com.shua.ti.domain.SubmitResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真机上跑全链路，用真实 SQLite（内存库）验证数据层行为。
 * 界面点击由人工验收补充，这里覆盖逻辑与持久化。
 */
@RunWith(AndroidJUnit4::class)
class FullFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: QuestionBankImporter
    private lateinit var engine: QuizEngine
    private lateinit var backup: BackupService

    private fun questionJson(answer: String = "A") = """
        {
          "stem": "1 + 1 = ?",
          "option_a": "1",
          "option_b": "2",
          "option_c": "3",
          "option_d": "4",
          "answer": "$answer"
        }
    """.trimIndent()

    private fun bankJson(count: Int, answerFor: (Int) -> String = { "A" }): String {
        val items = (1..count).joinToString(",") { i ->
            """
            {
              "stem": "第 $i 题",
              "option_a": "A 选项",
              "option_b": "B 选项",
              "option_c": "C 选项",
              "option_d": "D 选项",
              "answer": "${answerFor(i)}"
            }
            """.trimIndent()
        }
        return """{ "schema_version": 1, "questions": [ $items ] }"""
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = QuestionBankImporter(db, db.questionBankDao(), db.questionDao())
        engine = QuizEngine(
            db,
            db.questionBankDao(),
            db.questionDao(),
            db.answerRecordDao(),
            db.practiceProgressDao(),
        )
        backup = BackupService(
            db,
            db.questionBankDao(),
            db.questionDao(),
            db.answerRecordDao(),
            db.practiceProgressDao(),
            db.backupFileDao(),
            db.favoriteDao(),
            db.excludedQuestionDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun import_success_reports_count() = runTest {
        val result = importer.import("sample.json", bankJson(10))
        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(10, success.importedCount)
        assertEquals(10, db.questionDao().countByBank(success.bankId))
    }

    @Test
    fun import_invalid_json_writes_nothing() = runTest {
        val result = importer.import("bad.json", "{ not json")
        assertTrue(result is ImportResult.Failure)
        assertEquals(ImportErrorCode.INVALID_JSON, (result as ImportResult.Failure).code)
        assertEquals(0, db.questionBankDao().findAll().size)
    }

    @Test
    fun import_invalid_answer_writes_nothing() = runTest {
        val bad = """{ "questions": [ ${questionJson("E")} ] }"""
        val result = importer.import("bad-answer.json", bad)
        assertEquals(ImportErrorCode.INVALID_ANSWER, (result as ImportResult.Failure).code)
        assertEquals(0, db.questionBankDao().findAll().size)
    }

    @Test
    fun import_duplicate_source_is_rejected() = runTest {
        importer.import("same.json", bankJson(3))
        val second = importer.import("same.json", bankJson(3))
        assertEquals(ImportErrorCode.DUPLICATE_IMPORT, (second as ImportResult.Failure).code)
        assertEquals(1, db.questionBankDao().findAll().size)
    }

    @Test
    fun import_unreadable_file_is_rejected() = runTest {
        val result = importer.import("missing.json", null)
        assertEquals(ImportErrorCode.FILE_UNREADABLE, (result as ImportResult.Failure).code)
    }

    @Test
    fun round_start_then_submit_persists_progress() = runTest {
        val bank = importer.import("quiz.json", bankJson(10)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 5) as RoundStart.Started
        assertEquals(5, start.actualSize)
        assertEquals(1, start.roundNo)

        val answers = start.questions.associate { it.questionId to "A" }
        val submitted = engine.submit(bank.bankId, start.roundNo, start.questions, answers, false)
        assertTrue(submitted is SubmitResult.Graded)
        val graded = submitted as SubmitResult.Graded
        assertEquals(5, graded.correct)
        assertEquals(6, graded.nextRoundStart)

        val progress = db.practiceProgressDao().find(bank.bankId)
        assertEquals(1, progress?.completedRounds)
        assertEquals(6, progress?.nextRoundStart)
    }

    @Test
    fun unanswered_needs_confirm_then_counts_wrong() = runTest {
        val bank = importer.import("confirm.json", bankJson(5)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 5) as RoundStart.Started
        val partial = start.questions.take(2).associate { it.questionId to "A" }

        val first = engine.submit(bank.bankId, start.roundNo, start.questions, partial, false)
        assertTrue(first is SubmitResult.NeedConfirm)
        assertEquals(3, (first as SubmitResult.NeedConfirm).unanswered)

        val second = engine.submit(bank.bankId, start.roundNo, start.questions, partial, true)
        val graded = second as SubmitResult.Graded
        assertEquals(2, graded.correct)
        assertEquals(3, graded.rows.count { it.chosen == "U" })
    }

    @Test
    fun double_submit_is_rejected() = runTest {
        val bank = importer.import("double.json", bankJson(5)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 5) as RoundStart.Started
        val answers = start.questions.associate { it.questionId to "A" }
        engine.submit(bank.bankId, start.roundNo, start.questions, answers, true)
        val again = engine.submit(bank.bankId, start.roundNo, start.questions, answers, true)
        assertEquals(QuizErrorCode.DOUBLE_SUBMIT, (again as SubmitResult.Failed).code)
        assertEquals(1, db.practiceProgressDao().find(bank.bankId)?.completedRounds)
    }

    @Test
    fun insufficient_questions_reports_actual_size() = runTest {
        val bank = importer.import("small.json", bankJson(3)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 20) as RoundStart.Started
        assertEquals(3, start.actualSize)
        assertTrue(start.insufficient)
    }

    @Test
    fun empty_bank_is_rejected() = runTest {
        val start = engine.startRound(999L, 5)
        assertEquals(QuizErrorCode.EMPTY_BANK, (start as RoundStart.Failed).code)
    }

    @Test
    fun export_then_restore_round_trip() = runTest {
        val bank = importer.import("backup.json", bankJson(5)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 5) as RoundStart.Started
        val answers = start.questions.associate { it.questionId to "A" }
        engine.submit(bank.bankId, start.roundNo, start.questions, answers, true)

        val exported = backup.export("test-backup.json")
        assertTrue(exported is ExportResult.Success)
        val payload = exported as ExportResult.Success
        assertEquals(5, payload.questionCount)
        assertEquals(1, payload.roundCount)
        assertTrue(payload.verificationHint.contains("导入"))

        val restored = backup.restore(payload.content)
        assertTrue(restored is RestoreResult.Success)
        val ok = restored as RestoreResult.Success
        assertEquals(1, ok.bankCount)
        assertEquals(5, ok.questionCount)
        assertEquals(1, ok.roundCount)

        // 还原不覆盖：原题库 + 还原出来的新题库
        assertEquals(2, db.questionBankDao().findAll().size)
    }

    @Test
    fun export_without_data_is_rejected() = runTest {
        val result = backup.export()
        assertTrue(result is ExportResult.Failure)
    }

    @Test
    fun restore_garbage_keeps_existing_data() = runTest {
        importer.import("keep.json", bankJson(5))
        val result = backup.restore("not a backup")
        assertTrue(result is RestoreResult.Failure)
        assertEquals(1, db.questionBankDao().findAll().size)
    }

    @Test
    fun delete_bank_clears_progress_and_records() = runTest {
        val bank = importer.import("delete.json", bankJson(5)) as ImportResult.Success
        val start = engine.startRound(bank.bankId, 5) as RoundStart.Started
        engine.submit(
            bank.bankId,
            start.roundNo,
            start.questions,
            start.questions.associate { it.questionId to "A" },
            true,
        )
        engine.deleteBank(bank.bankId)
        assertEquals(0, db.questionBankDao().findAll().size)
        assertEquals(null, db.practiceProgressDao().find(bank.bankId))
        assertEquals(0, db.answerRecordDao().countRound(bank.bankId, 1))
    }
}
