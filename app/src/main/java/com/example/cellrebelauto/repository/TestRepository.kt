package com.example.cellrebelauto.repository

import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.TestResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository pattern for test data access.
 * # 测试数据的仓库模式封装，统一数据访问入口
 */
class TestRepository(private val db: AppDatabase) {

    // --- TestResult ---

    suspend fun insertResult(result: TestResult): Long =
        db.testResultDao().insert(result)

    fun getAllResults(): Flow<List<TestResult>> =
        db.testResultDao().getAllResults()

    fun getResultCount(): Flow<Int> =
        db.testResultDao().getCount()

    suspend fun getAllResultsForExport(): List<TestResult> =
        db.testResultDao().getAllResultsForExport()

    suspend fun deleteAllResults() =
        db.testResultDao().deleteAll()

    // --- RunSession ---

    // # 创建新的运行会话，返回 session ID
    suspend fun createSession(configSnapshot: String): Long {
        val session = RunSession(
            startedAt = System.currentTimeMillis(),
            configSnapshot = configSnapshot
        )
        return db.runSessionDao().insert(session)
    }

    // # 结束运行会话，记录状态和循环数
    suspend fun finishSession(sessionId: Long, status: String, totalCycles: Int) {
        db.runSessionDao().finish(
            id = sessionId,
            endedAt = System.currentTimeMillis(),
            status = status,
            totalCycles = totalCycles
        )
    }

    fun getAllSessions(): Flow<List<RunSession>> =
        db.runSessionDao().getAllSessions()
}
