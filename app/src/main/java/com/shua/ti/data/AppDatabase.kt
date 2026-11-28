package com.shua.ti.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shua.ti.data.dao.AnswerRecordDao
import com.shua.ti.data.dao.BackupFileDao
import com.shua.ti.data.dao.ExcludedQuestionDao
import com.shua.ti.data.dao.FavoriteDao
import com.shua.ti.data.dao.PracticeProgressDao
import com.shua.ti.data.dao.QuestionBankDao
import com.shua.ti.data.dao.QuestionDao
import com.shua.ti.data.entity.AnswerRecord
import com.shua.ti.data.entity.BackupFile
import com.shua.ti.data.entity.ExcludedQuestion
import com.shua.ti.data.entity.Favorite
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank

/**
 * 本机单库。版本变更时递增 version 并补 Migration。
 * 2：backup_file 增加 file_name，列表不再展示 content uri。
 * 3：question 增加 explanation，题目可选解析。
 * 4：新增 favorite 与 excluded_question 两表（收藏、手工移出错题集）；
 *    answer_record 增加 round_kind（轮次类型）；question_bank 增加 is_special（常驻错题库标记）。
 */
@Database(
    entities = [
        QuestionBank::class,
        Question::class,
        AnswerRecord::class,
        PracticeProgress::class,
        BackupFile::class,
        Favorite::class,
        ExcludedQuestion::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun questionBankDao(): QuestionBankDao
    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun practiceProgressDao(): PracticeProgressDao
    abstract fun backupFileDao(): BackupFileDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun excludedQuestionDao(): ExcludedQuestionDao

    companion object {
        /**
         * 旧备份记录没有文件名，补空串后列表回退显示导出时间。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE backup_file ADD COLUMN file_name TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * 旧题目没有解析，补空串后结果页不显示解析区。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE question ADD COLUMN explanation TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * 回看与重刷：收藏表、手工移出错题集表、轮次类型、常驻错题库标记。
         * 全部有缺省值或新建表，旧数据无需回填。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorite (" +
                        "question_id INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(question_id), " +
                        "FOREIGN KEY(question_id) REFERENCES question(question_id) " +
                        "ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS excluded_question (" +
                        "bank_id INTEGER NOT NULL, " +
                        "question_id INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(bank_id, question_id), " +
                        "FOREIGN KEY(bank_id) REFERENCES question_bank(bank_id) " +
                        "ON DELETE CASCADE)",
                )
                db.execSQL(
                    "ALTER TABLE answer_record ADD COLUMN round_kind TEXT NOT NULL DEFAULT 'NORMAL'",
                )
                db.execSQL(
                    "ALTER TABLE question_bank ADD COLUMN is_special INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shuati.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
