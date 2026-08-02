package com.example.cellrebelauto.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema v3 tests: plan/task/attempt entities, FK enforcement, guarded success
 * increment (INV-3), non-terminal recovery sweep (INV-9).
 * # 计划 Schema 测试：实体、外键约束、守卫式成功计数、非终态恢复清扫
 */
@RunWith(RobolectricTestRunner::class)
class PlanSchemaTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        // # 测试直接用内存库实例，不走单例
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedPlanWithTasks(): Pair<Long, List<Long>> {
        val plan = LocationPlan(
            sourceFileName = "sites.csv", importedAt = 1000L,
            globalBufferSeconds = 60, totalRows = 2, totalRequiredSuccesses = 3
        )
        val tasks = listOf(
            LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 2, requiredSuccesses = 1),
            LocationTask(planId = 0, csvRow = 2, longitude = 121.5, latitude = 31.2, priority = 1, requiredSuccesses = 2)
        )
        val planId = db.planDao().insertPlanWithTasks(plan, tasks)
        val ids = db.locationTaskDao().getTasksForPlan(planId).map { it.id }
        return planId to ids
    }

    private suspend fun seedSession(): Long =
        db.runSessionDao().insert(RunSession(startedAt = 500L))

    private fun attempt(taskId: Long, sessionId: Long, ordinal: Int, status: String) = TestAttempt(
        taskId = taskId, runSessionId = sessionId, attemptOrdinal = ordinal,
        successOrdinal = if (status == "succeeded") ordinal else null,
        startedAt = 1000L + ordinal, runningObservedAt = null,
        endedAt = if (status in listOf("succeeded", "failed", "interrupted")) 2000L + ordinal else null,
        status = status,
        failureReason = if (status in listOf("failed", "interrupted")) "CELLREBEL_TIMEOUT" else null,
        webBrowsingScore = null, videoStreamingScore = null,
        latitude = 39.9, longitude = 116.4
    )

    @Test
    fun `legacy results are readable for history and export`() = runTest {
        // # C1 回归：v2 遗留 test_results 行必须能从 repository 读到（History + 导出）
        val repo = com.example.cellrebelauto.repository.PlanRepository(db)
        val sessionId = seedSession()
        db.testResultDao().insert(
            com.example.cellrebelauto.model.TestResult(
                runSessionId = sessionId, timestamp = 1500L,
                webBrowsingScore = 8.5, videoStreamingScore = 7.5,
                latitude = 39.9, longitude = 116.4, cycleIndex = 1, status = "ok"
            )
        )

        val legacy = repo.getLegacyResultsForExport()
        assertEquals(1, legacy.size)
        assertEquals(8.5, legacy[0].webBrowsingScore, 0.001)
        assertEquals(sessionId, legacy[0].runSessionId)
    }

    @Test
    fun `buffer sync updates unstarted plan snapshot and refuses once started`() = runTest {
        // # F6 回归：UI 改 buffer 必须同步 engine 执行的 plan 快照（计划未启动），
        // # 计划一旦启动则拒绝（next-plan-only），二者绝不发散
        val repo = com.example.cellrebelauto.repository.PlanRepository(db)
        val (planId, taskIds) = seedPlanWithTasks()

        // # 未启动：同步成功，plan 行（engine BufferGate 的唯一来源）被更新
        assertTrue(repo.syncBufferIfPlanNotStarted(planId, 120))
        assertEquals(120, db.planDao().getPlanById(planId)!!.globalBufferSeconds)

        // # 已启动（存在尝试记录）：拒绝，快照不变
        val sessionId = seedSession()
        db.testAttemptDao().insert(attempt(taskIds[0], sessionId, 1, "starting"))
        assertFalse(repo.syncBufferIfPlanNotStarted(planId, 30))
        assertEquals(120, db.planDao().getPlanById(planId)!!.globalBufferSeconds)
    }

    @Test
    fun `plan tasks attempts survive dao round trip and plans table has no status column`() = runTest {
        val (planId, taskIds) = seedPlanWithTasks()
        val sessionId = seedSession()
        db.testAttemptDao().insert(attempt(taskIds[0], sessionId, 1, "succeeded"))

        // # 往返读取：计划与任务数据完整
        val plan = db.planDao().getPlanById(planId)!!
        assertEquals("sites.csv", plan.sourceFileName)
        assertEquals(60, plan.globalBufferSeconds)
        assertEquals(planId, db.planDao().getLatestPlan()!!.id)

        // # 任务按 priority ASC, csvRow ASC 排序（priority=1 的 csvRow=2 排最前）
        val tasks = db.locationTaskDao().getTasksForPlan(planId)
        assertEquals(listOf(2, 1), tasks.map { it.csvRow })

        // # 尝试记录可读回，且能按计划联查
        assertEquals(1, db.testAttemptDao().getAttemptsForTask(taskIds[0]).size)
        assertEquals(1, db.testAttemptDao().getAttemptsForPlan(planId).size)
        assertEquals(1, db.testAttemptDao().countAttemptsForTask(taskIds[0]))

        // # location_plans 不允许有 status 列（计划状态是纯投影）
        val columns = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info(location_plans)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        assertFalse("location_plans must not have a status column", columns.contains("status"))
        assertTrue(columns.contains("globalBufferSeconds"))
    }

    @Test
    fun `test_attempts has the eight v5 audit columns`() {
        // # F002 v5：test_attempts 列清单含 8 个位置审计列
        val columns = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info(test_attempts)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        listOf(
            "actualLatitude", "actualLongitude", "locationErrorMeters", "fixIsMock",
            "fixAt", "verifiedAt", "fixAccuracyMeters", "toleranceMetersUsed"
        ).forEach { assertTrue("test_attempts must have column $it", columns.contains(it)) }
    }

    @Test
    fun `recordLocationAudit writes all fields and markSucceeded preserves them`() = runTest {
        // # INV-F2-4：审计列仅由 recordLocationAudit 写入；
        // # finalize success 的 UPDATE 绝不清空它们
        val repo = com.example.cellrebelauto.repository.PlanRepository(db)
        val (_, taskIds) = seedPlanWithTasks()
        val sessionId = seedSession()
        val attemptId = db.testAttemptDao().insert(
            attempt(taskIds[0], sessionId, 1, "starting")
        )

        repo.recordLocationAudit(
            attemptId,
            com.example.cellrebelauto.automation.LocationAudit(
                actualLatitude = 39.91, actualLongitude = 116.41,
                locationErrorMeters = 42.0, fixIsMock = true,
                fixAt = 1500L, verifiedAt = 1600L,
                fixAccuracyMeters = 3.5, toleranceMetersUsed = 100.0
            )
        )

        // # 八字段读回一致
        var row = db.testAttemptDao().getAttemptsForTask(taskIds[0]).first { it.id == attemptId }
        assertEquals(39.91, row.actualLatitude!!, 0.0001)
        assertEquals(116.41, row.actualLongitude!!, 0.0001)
        assertEquals(42.0, row.locationErrorMeters!!, 0.001)
        assertEquals(true, row.fixIsMock)
        assertEquals(1500L, row.fixAt)
        assertEquals(1600L, row.verifiedAt)
        assertEquals(3.5, row.fixAccuracyMeters!!, 0.001)
        assertEquals(100.0, row.toleranceMetersUsed!!, 0.001)

        // # markSucceeded 后审计列保留
        repo.finalizeAttemptSuccess(
            attemptId = attemptId, taskId = taskIds[0], expectedCompletedSuccesses = 0,
            runningObservedAt = 1550L, endedAt = 2000L, webScore = 8.0, videoScore = 7.0
        )
        row = db.testAttemptDao().getAttemptsForTask(taskIds[0]).first { it.id == attemptId }
        assertEquals("succeeded", row.status)
        assertEquals(39.91, row.actualLatitude!!, 0.0001)
        assertEquals(42.0, row.locationErrorMeters!!, 0.001)
        assertEquals(true, row.fixIsMock)
        assertEquals(1500L, row.fixAt)
        assertEquals(1600L, row.verifiedAt)
        assertEquals(3.5, row.fixAccuracyMeters!!, 0.001)
        assertEquals(100.0, row.toleranceMetersUsed!!, 0.001)
    }

    @Test
    fun `attempt with unknown task id violates foreign key`() = runTest {
        val sessionId = seedSession()
        try {
            db.testAttemptDao().insert(attempt(taskId = 9999L, sessionId = sessionId, ordinal = 1, status = "starting"))
            fail("expected SQLiteConstraintException for dangling taskId")
        } catch (e: SQLiteConstraintException) {
            // # 预期：外键约束生效
        }
    }

    @Test
    fun `incrementSuccessIfCurrent increments exactly once and stale expected returns zero`() = runTest {
        val (planId, taskIds) = seedPlanWithTasks()
        val taskId = taskIds[0]

        // # 期望值匹配 → 恰好 +1
        assertEquals(1, db.locationTaskDao().incrementSuccessIfCurrent(taskId, expectedCompletedSuccesses = 0))
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)

        // # 过期期望值 → 0 行受影响（幂等，INV-3）
        assertEquals(0, db.locationTaskDao().incrementSuccessIfCurrent(taskId, expectedCompletedSuccesses = 0))
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `markNonTerminalInterrupted only touches starting and running attempts`() = runTest {
        val (planId, taskIds) = seedPlanWithTasks()
        val sessionId = seedSession()
        val taskId = taskIds[0]
        db.testAttemptDao().insert(attempt(taskId, sessionId, 1, "starting"))
        db.testAttemptDao().insert(attempt(taskId, sessionId, 2, "running"))
        db.testAttemptDao().insert(attempt(taskId, sessionId, 3, "succeeded"))
        db.testAttemptDao().insert(attempt(taskId, sessionId, 4, "failed"))

        val swept = db.testAttemptDao().markNonTerminalInterrupted(nowMs = 9999L)
        assertEquals(2, swept)

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(listOf("interrupted", "interrupted", "succeeded", "failed"), attempts.map { it.status })
        assertEquals(9999L, attempts[0].endedAt)
        assertEquals("INTERRUPTED", attempts[0].failureReason)
        // # 已终态的行不受影响
        assertEquals("CELLREBEL_TIMEOUT", attempts[3].failureReason)
        assertEquals(2004L, attempts[3].endedAt)

        // # 终态查询只看 succeeded/failed/interrupted 的最大 endedAt
        val latest = db.testAttemptDao().getLatestTerminalAttemptEndedAtForPlan(planId)
        assertEquals(9999L, latest)
    }
}
