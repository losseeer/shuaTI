package com.shua.ti.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/**
 * 本机单库。版本变更时递增 version 并补 Migration。
 * 2：backup_file 增加 file_name，列表不再展示 content uri。
 */
@Database(
    entities = [
        QuestionBank::class,
        Question::class,
        AnswerRecord::class,
        PracticeProgress::class,
        BackupFile::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun questionBankDao(): QuestionBankDao
    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun practiceProgressDao(): PracticeProgressDao
    abstract fun backupFileDao(): BackupFileDao

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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shuati.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
