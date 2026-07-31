package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.flow.Flow

/**
 * Per-task attempt count projection for the Plan screen cards.
 * # 每个任务的尝试总数投影（Plan 页卡片用）
 */
data class TaskAttemptCount(
    val taskId: Long,
    val count: Int
)

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

    // # 观察某计划下每个任务的尝试总数（Plan 页卡片 Attempts n）
    @Query(
        "SELECT a.taskId AS taskId, COUNT(*) AS count FROM test_attempts a " +
            "INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId GROUP BY a.taskId"
    )
    fun observeAttemptCountsForPlan(planId: Long): Flow<List<TaskAttemptCount>>

    /**
     * Recovery sweep (INV-9): mark leftover starting/running rows interrupted.
     * # 恢复清扫（INV-9）：把残留的 starting/running 行标记为 interrupted
     */
    @Query(
        "UPDATE test_attempts SET status = 'interrupted', failureReason = 'INTERRUPTED', endedAt = :nowMs " +
            "WHERE status IN ('starting', 'running')"
    )
    suspend fun markNonTerminalInterrupted(nowMs: Long): Int

    /**
     * Finalize a succeeded attempt (called inside the finalize transaction, INV-3).
     * # 成功尝试收尾（在 finalize 事务内调用）
     */
    @Query(
        "UPDATE test_attempts SET status = 'succeeded', successOrdinal = :successOrdinal, " +
            "runningObservedAt = :runningObservedAt, endedAt = :endedAt, " +
            "webBrowsingScore = :webScore, videoStreamingScore = :videoScore WHERE id = :attemptId"
    )
    suspend fun markSucceeded(
        attemptId: Long,
        successOrdinal: Int,
        runningObservedAt: Long?,
        endedAt: Long,
        webScore: Double,
        videoScore: Double
    )

    /**
     * Finalize a failed attempt with a typed reason (INV-4/10).
     * # 失败尝试收尾，带类型化原因
     */
    @Query("UPDATE test_attempts SET status = 'failed', failureReason = :reason, endedAt = :endedAt WHERE id = :attemptId")
    suspend fun markFailed(attemptId: Long, reason: String, endedAt: Long)

    /**
     * Stop/cancel path: interrupt the in-flight attempt only if still non-terminal.
     * # 停止/取消路径：仅当尝试仍为非终态时标记 interrupted
     */
    @Query(
        "UPDATE test_attempts SET status = 'interrupted', failureReason = 'INTERRUPTED', endedAt = :nowMs " +
            "WHERE id = :attemptId AND status IN ('starting', 'running')"
    )
    suspend fun markInterruptedIfNonTerminal(attemptId: Long, nowMs: Long)
}
