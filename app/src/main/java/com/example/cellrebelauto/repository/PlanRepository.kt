package com.example.cellrebelauto.repository

import androidx.room.withTransaction
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.db.TaskAttemptCount
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.plan.WorklistRow
import kotlinx.coroutines.flow.Flow

/**
 * Plan-level repository (O1–O4 data owner). Wraps the plan/task/attempt/session
 * DAOs and hosts the transactional success finalization (INV-3): attempt row +
 * guarded task increment atomically, idempotent against stale expected values.
 * # 计划级仓库：封装计划/任务/尝试/会话 DAO，
 * # 并承载成功收尾事务（INV-3）：尝试行 + 守卫式任务自增原子完成，幂等
 */
class PlanRepository(private val db: AppDatabase) {

    // ---- Reads ----

    suspend fun getPlan(planId: Long): LocationPlan? = db.planDao().getPlanById(planId)

    suspend fun getTasks(planId: Long): List<LocationTask> = db.locationTaskDao().getTasksForPlan(planId)

    suspend fun getTask(taskId: Long): LocationTask? = db.locationTaskDao().getTaskById(taskId)

    suspend fun countAttemptsForTask(taskId: Long): Int = db.testAttemptDao().countAttemptsForTask(taskId)

    // # 最近一次终态尝试的 endedAt（缓冲投影，INV-5）
    suspend fun latestTerminalAttemptEndedAt(planId: Long): Long? =
        db.testAttemptDao().getLatestTerminalAttemptEndedAtForPlan(planId)

    // ---- Plan screen reads (observable) ----

    // # 观察最近导入的计划
    fun observeLatestPlan(): Flow<LocationPlan?> = db.planDao().observeLatestPlan()

    // # 观察某计划的任务列表（DAO 已按执行顺序排序，INV-1）
    fun observeTasks(planId: Long): Flow<List<LocationTask>> =
        db.locationTaskDao().observeTasksForPlan(planId)

    // # 观察某计划每个任务的尝试总数
    fun observeAttemptCounts(planId: Long): Flow<List<TaskAttemptCount>> =
        db.testAttemptDao().observeAttemptCountsForPlan(planId)

    // ---- Import (atomic, AC-A2) ----

    /**
     * Persists a validated worklist as plan + tasks in ONE transaction.
     * Callers must run WorklistParser first and pass only Success rows;
     * the unfinished-plan rejection is a UI/policy concern handled before this.
     * # 将已校验的清单原子落库（计划行 + 全部任务行，同一事务）。
     * # 调用方须先过 WorklistParser；未完成计划的拒绝策略在 UI 层处理
     */
    suspend fun importPlan(
        sourceFileName: String,
        globalBufferSeconds: Int,
        rows: List<WorklistRow>,
        importedAt: Long
    ): Long = db.planDao().insertPlanWithTasks(
        LocationPlan(
            sourceFileName = sourceFileName,
            importedAt = importedAt,
            globalBufferSeconds = globalBufferSeconds,
            totalRows = rows.size,
            totalRequiredSuccesses = rows.sumOf { it.requiredSuccesses }
        ),
        rows.map {
            LocationTask(
                planId = 0, // # 由 insertPlanWithTasks 回填
                csvRow = it.csvRow,
                longitude = it.longitude,
                latitude = it.latitude,
                priority = it.priority,
                requiredSuccesses = it.requiredSuccesses
            )
        }
    )

    // ---- Recovery (INV-9 / O4) ----

    suspend fun markNonTerminalInterrupted(nowMs: Long): Int =
        db.testAttemptDao().markNonTerminalInterrupted(nowMs)

    suspend fun markStaleSessionsInterrupted(nowMs: Long): Int =
        db.runSessionDao().markStaleRunningSessionsInterrupted(nowMs)

    // ---- Task lifecycle ----

    suspend fun markTaskActive(taskId: Long) = db.locationTaskDao().updateTaskStatus(taskId, "active")

    suspend fun markTaskCompleted(taskId: Long) = db.locationTaskDao().markTaskCompleted(taskId)

    // ---- Attempt lifecycle ----

    suspend fun insertAttempt(attempt: TestAttempt): Long = db.testAttemptDao().insert(attempt)

    /**
     * Atomic success finalization (INV-3): guarded task increment + attempt row
     * update in ONE Room transaction. Returns false when the expected
     * completedSuccesses is stale (re-run safety).
     * # 原子成功收尾（INV-3）：守卫式自增 + 尝试行更新在同一事务内；
     * # 期望值过期时返回 false（重跑安全）
     */
    suspend fun finalizeAttemptSuccess(
        attemptId: Long,
        taskId: Long,
        expectedCompletedSuccesses: Int,
        runningObservedAt: Long?,
        endedAt: Long,
        webScore: Double,
        videoScore: Double
    ): Boolean = db.withTransaction {
        val incremented = db.locationTaskDao()
            .incrementSuccessIfCurrent(taskId, expectedCompletedSuccesses)
        if (incremented == 0) return@withTransaction false
        db.testAttemptDao().markSucceeded(
            attemptId = attemptId,
            successOrdinal = expectedCompletedSuccesses + 1,
            runningObservedAt = runningObservedAt,
            endedAt = endedAt,
            webScore = webScore,
            videoScore = videoScore
        )
        true
    }

    /**
     * Persist a failed attempt with typed reason (INV-4/10); never touches quota.
     * # 持久化失败尝试（带类型化原因），绝不动配额
     */
    suspend fun finalizeAttemptFailure(attemptId: Long, reason: String, endedAt: Long) =
        db.testAttemptDao().markFailed(attemptId, reason, endedAt)

    // # 停止/取消：仅在途尝试仍为非终态时标记 interrupted
    suspend fun markAttemptInterruptedIfNonTerminal(attemptId: Long, nowMs: Long) =
        db.testAttemptDao().markInterruptedIfNonTerminal(attemptId, nowMs)

    // ---- Session lifecycle ----

    suspend fun createSession(planId: Long, startedAt: Long): Long =
        db.runSessionDao().insert(
            RunSession(startedAt = startedAt, planId = planId, configSnapshot = "plan:$planId")
        )

    suspend fun finishSession(sessionId: Long, status: String, endedAt: Long, totalCycles: Int) =
        db.runSessionDao().finish(sessionId, endedAt, status, totalCycles)
}
