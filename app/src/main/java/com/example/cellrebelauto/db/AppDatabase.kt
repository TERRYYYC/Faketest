package com.example.cellrebelauto.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.TestResult

/**
 * Room database singleton.
 * # Room 数据库单例，版本 2（新架构重建）
 */
@Database(
    entities = [TestResult::class, RunSession::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun testResultDao(): TestResultDao
    abstract fun runSessionDao(): RunSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cellrebel_auto.db"
                )
                    // # 架构升级时销毁旧数据重建（测试阶段可接受）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
