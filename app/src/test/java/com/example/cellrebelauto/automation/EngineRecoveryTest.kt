package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Engine plan-loop + recovery tests (INV-2/3/4/5/9).
 * Robolectric in-memory Room + fake typed-outcome handlers + virtual clock.
 * # 引擎计划循环与恢复测试：内存 Room + 假处理器 + 虚拟时钟
 */
@RunWith(RobolectricTestRunner::class)
class EngineRecoveryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- Fakes ----

    /** # 脚本化假 CellRebel 执行器：逐条消费结果模板并用虚拟时钟重新盖时间戳 */
    private class FakeCellRebelRunner(
        templates: List<AttemptOutcome>,
        private val nowMs: () -> Long
    ) : CellRebelRunner {
        private val queue = templates.toMutableList()
        var calls = 0
            private set

        override suspend fun runTest(startedAt: Long, testTimeoutMs: Long): AttemptOutcome {
            calls++
            val template = if (queue.size > 1) queue.removeAt(0) else queue.first()
            return when (template) {
                is AttemptOutcome.Success -> template.copy(startedAt = startedAt, endedAt = nowMs())
                is AttemptOutcome.Failure -> template.copy(startedAt = startedAt, endedAt = nowMs())
            }
        }
    }

    /** # 脚本化假 GPS 设置器 */
    private class FakeGpsSetter(outcomes: List<GpsOutcome>) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        var calls = 0
            private set

        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
            calls++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    /** # 虚拟时钟：记录所有 delay 调用，时间只在 delay 时前进 */
    private class VirtualClock {
        var now = 0L
        val delays = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> delays.add(ms); now += ms }
    }

    private val successTemplate = AttemptOutcome.Success(
        webScore = 8.0, videoScore = 7.0, runningObservedAt = 0L, startedAt = 0L, endedAt = 0L
    )

    private fun failureTemplate(reason: FailureReason) = AttemptOutcome.Failure(
        reason = reason, detail = null, startedAt = 0L, endedAt = 0L
    )

    // ---- Seed helpers ----

    private suspend fun seedPlan(quota: Int, bufferSeconds: Int = 60): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = bufferSeconds, totalRows = 1, totalRequiredSuccesses = quota
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    private fun buildEngine(
        planId: Long,
        runner: CellRebelRunner,
        gps: GpsLocationSetter,
        clock: VirtualClock,
        bufferSeconds: Int = 60,
        gpsSettleMs: Long = 0L
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(bufferSeconds, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = gpsSettleMs,
        nowMs = clock.nowMs,
        delayMs = clock.delayMs
    )

    @Test
    fun `crash window mid attempt is swept to interrupted and task count preserved`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        // # 模拟崩溃窗口：上一次进程留下 running 会话 + running 尝试
        val staleSessionId = db.runSessionDao().insert(RunSession(startedAt = 500L))
        db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = staleSessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )

        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # INV-9：残留 running 尝试被清扫为 interrupted，失败原因 INTERRUPTED
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("interrupted", attempts[0].status)
        assertEquals("INTERRUPTED", attempts[0].failureReason)
        assertEquals("succeeded", attempts[1].status)

        // # 计数只来自新尝试；旧残留没有计入
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        assertEquals("completed", task.status)

        // # 残留 running 会话被标记 interrupted；新会话 completed
        assertEquals("interrupted", db.runSessionDao().getById(staleSessionId)!!.status)
    }

    @Test
    fun `success path fills quota exactly once and repeated finalize is idempotent`() = runTest {
        val (planId, taskId) = seedPlan(quota = 2)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate, successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # 恰好 2 次成功，successOrdinal 为 1、2
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals(listOf(1, 2), attempts.map { it.successOrdinal })
        assertTrue(attempts.all { it.status == "succeeded" })
        assertTrue(attempts.all { it.runningObservedAt != null })

        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(2, task.completedSuccesses)
        assertEquals("completed", task.status)
        assertEquals(2, runner.calls)

        // # INV-3：用过期的期望值重复 finalize → 不再增加（幂等）
        val finalized = repo.finalizeAttemptSuccess(
            attemptId = attempts[1].id,
            taskId = taskId,
            expectedCompletedSuccesses = 1, // # 过期值（当前已是 2）
            runningObservedAt = 0L, endedAt = 0L, webScore = 9.0, videoScore = 9.0
        )
        assertFalse(finalized)
        assertEquals(2, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        // # 尝试行也未被重复覆写
        assertEquals(2, db.testAttemptDao().getAttemptsForTask(taskId)[1].successOrdinal)
    }

    @Test
    fun `failure persists typed reason without quota and same task is retried`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(
            listOf(failureTemplate(FailureReason.CELLREBEL_TIMEOUT), successTemplate),
            clock.nowMs
        )
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # INV-4：失败行带类型化原因，不计入配额
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("CELLREBEL_TIMEOUT", attempts[0].failureReason)
        assertEquals(null, attempts[0].successOrdinal)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, attempts[1].successOrdinal)

        // # INV-2：同一任务被重试（两次尝试挂在同一 taskId 上），最终完成
        assertTrue(attempts.all { it.taskId == taskId })
        assertEquals(2, runner.calls)
        assertEquals(2, gps.calls)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        assertEquals("completed", task.status)
    }

    @Test
    fun `gps activation failure yields typed failed attempt and consumes no quota`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        // # 第一次 GPS 激活失败，第二次成功
        val gps = FakeGpsSetter(
            listOf(
                GpsOutcome.Failed(FailureReason.FAKE_GPS_NOT_ACTIVE, "no stop button"),
                GpsOutcome.Active
            )
        )
        buildEngine(planId, runner, gps, clock).run()

        // # INV-10：GPS 失败 → 类型化失败尝试，不消耗配额，也不运行 CellRebel
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("FAKE_GPS_NOT_ACTIVE", attempts[0].failureReason)
        assertEquals(1, runner.calls)
        assertEquals("completed", db.locationTaskDao().getTaskById(taskId)!!.status)
    }

    @Test
    fun `buffer gate delays next attempt by remaining buffer after success`() = runTest {
        val (planId, _) = seedPlan(quota = 2, bufferSeconds = 60)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate, successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        // # settle=0，隔离出唯一的缓冲延迟
        buildEngine(planId, runner, gps, clock, bufferSeconds = 60, gpsSettleMs = 0).run()

        // # INV-5：第一次成功结束后，第二次尝试恰好等了完整缓冲 60s
        assertEquals(listOf(60_000L), clock.delays)
    }
}
