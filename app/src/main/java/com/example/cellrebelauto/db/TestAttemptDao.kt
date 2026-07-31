package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.TestAttempt

/**
 * DAO for test_attempts.
 * # 测试尝试表的数据访问对象
 */
@Dao
interface TestAttemptDao {

    @Insert
    suspend fun insert(attempt: TestAttempt): Long

    @Query("SELECT * FROM test_attempts WHERE taskId = :taskId ORDER BY attemptOrdinal ASC")
    suspend fun getAttemptsForTask(taskId: Long): List<TestAttempt>

    // # 经任务表联查某计划下的全部尝试
    @Query(
        "SELECT a.* FROM test_attempts a INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId ORDER BY a.id ASC"
    )
    suspend fun getAttemptsForPlan(planId: Long): List<TestAttempt>

    // # 最近一次终态尝试（succeeded/failed/interrupted）的 endedAt，用于缓冲投影（INV-5）
    @Query(
        "SELECT MAX(a.endedAt) FROM test_attempts a INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId AND a.status IN ('succeeded', 'failed', 'interrupted')"
    )
    suspend fun getLatestTerminalAttemptEndedAtForPlan(planId: Long): Long?

    @Query("SELECT COUNT(*) FROM test_attempts WHERE taskId = :taskId")
    suspend fun countAttemptsForTask(taskId: Long): Int

    /**
     * Recovery sweep (INV-9): mark leftover starting/running rows interrupted.
     * # 恢复清扫（INV-9）：把残留的 starting/running 行标记为 interrupted
     */
    @Query(
        "UPDATE test_attempts SET status = 'interrupted', failureReason = 'INTERRUPTED', endedAt = :nowMs " +
            "WHERE status IN ('starting', 'running')"
    )
    suspend fun markNonTerminalInterrupted(nowMs: Long): Int
}
