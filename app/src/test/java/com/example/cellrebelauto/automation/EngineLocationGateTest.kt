package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F002 engine location-gate integration tests (AC-F2-1/2/2b/3, INV-F2-1/2/3/4).
 * The gate under test is the REAL LocationGate driven by fake permission
 * checker / fix sampler / virtual nanos clock — only Android is stubbed.
 * # F002 引擎位置闸门集成测试：被测闸门是真实 LocationGate，
 * # 仅权限检查/采样器/时钟用假实现
 */
@RunWith(RobolectricTestRunner::class)
class EngineLocationGateTest {

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

    /** # 假 CellRebel 执行器：恒定成功；afterCall 钩子用于"运行中撤销权限" */
    private class FakeRunner(
        private val nowMs: () -> Long,
        private val afterCall: () -> Unit = {}
    ) : CellRebelRunner {
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            val outcome = AttemptOutcome.Success(
                webScore = 8.0, videoScore = 7.0,
                runningObservedAt = nowMs(), startedAt = startedAt, endedAt = nowMs()
            )
            afterCall()
            return outcome
        }
    }

    /** # 假 GPS 设置器：恒定 Active */
    private class FakeGps : GpsLocationSetter {
        var calls = 0
            private set

        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
            calls++
            return GpsOutcome.Active
        }
    }

    /** # 虚拟时钟：记录所有 delay 调用，时间只在 delay 时前进 */
    private class VirtualClock {
        var now = 0L
        val delays = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> delays.add(ms); now += ms }
    }

    /** # 可变权限检查器（mid-run 撤销用例翻转 state） */
    private class FakePermissionChecker(
        var state: LocationPermissionState = LocationPermissionState.FINE
    ) : LocationPermissionChecker {
        override fun current(): LocationPermissionState = state
    }

    /** # 脚本化采样器；afterSample 钩子收到已发生的调用次数 */
    private class ScriptedSampler(
        results: List<SampleResult>,
        private val afterSample: (Int) -> Unit = {}
    ) : LocationFixSampler {
        private val queue = results.toMutableList()
        var calls = 0
            private set

        override suspend fun sampleFix(): SampleResult {
            calls++
            val result = if (queue.size > 1) queue.removeAt(0) else queue.first()
            afterSample(calls)
            return result
        }
    }

    // # 假距离：固定返回值，用例按需改写
    private var fixedDistance = 0f

    private var nanos = 1_000L

    private fun mockFix(
        elapsedNanos: Long,
        isMock: Boolean = true,
        fixAtMs: Long = 0L
    ) = ObservedFix(
        latitude = 39.9, longitude = 116.4, accuracyMeters = 3.0f,
        isMock = isMock, elapsedRealtimeNanos = elapsedNanos, fixAtMs = fixAtMs
    )

    private fun buildGate(
        checker: FakePermissionChecker,
        sampler: ScriptedSampler,
        clock: VirtualClock
    ) = LocationGate(
        permissionChecker = checker,
        sampler = sampler,
        nowNanos = { nanos },
        nowMs = clock.nowMs,
        distanceMeters = { _, _, _, _ -> fixedDistance }
    )

    private suspend fun seedPlan(quota: Int, bufferSeconds: Int = 0): Pair<Long, Long> {
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
        return planId to db.locationTaskDao().getTasksForPlan(planId).first().id
    }

    private fun buildEngine(
        planId: Long,
        runner: CellRebelRunner,
        gps: GpsLocationSetter,
        clock: VirtualClock,
        gate: LocationGate,
        toggles: suspend () -> StageToggles = { StageToggles() },
        bufferSeconds: Int = 0,
        delayMs: suspend (Long) -> Unit = clock.delayMs
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(bufferSeconds, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = 0L,
        locationGate = gate,
        locationToleranceMeters = { LocationGateLogic.DEFAULT_TOLERANCE_METERS },
        elapsedRealtimeNanos = { nanos },
        stageToggles = toggles,
        nowMs = clock.nowMs,
        delayMs = delayMs
    )

    @Test
    fun `both stages on and gate verified completes cellrebel with full audit row`() = runTest {
        // # 1：双阶段 ON + 闸门 Verified → CellRebel 生命周期完成，审计八字段齐全
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val checker = FakePermissionChecker()
        val sampler = ScriptedSampler(
            listOf(SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L)))
        )
        buildEngine(planId, runner, gps, clock, buildGate(checker, sampler, clock)).run()

        assertEquals(1, gps.calls)
        assertEquals(1, runner.calls)
        assertEquals(1, sampler.calls)

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("succeeded", attempt.status)
        // # AC-F2-3：审计八字段持久化（tolerance 回显实际使用值）
        assertEquals(39.9, attempt.actualLatitude!!, 0.0001)
        assertEquals(116.4, attempt.actualLongitude!!, 0.0001)
        assertEquals(0.0, attempt.locationErrorMeters!!, 0.001)
        assertEquals(true, attempt.fixIsMock)
        assertEquals(4_900L, attempt.fixAt)
        assertEquals(5_000L, attempt.verifiedAt)
        assertEquals(3.0, attempt.fixAccuracyMeters!!, 0.001)
        assertEquals(100.0, attempt.toleranceMetersUsed!!, 0.001)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `test stage off and gate verified counts ok_gps_only quota`() = runTest {
        // # 2：testStage OFF + Verified → ok_gps_only 计配额（F003 语义不回归）
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val sampler = ScriptedSampler(
            listOf(SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L)))
        )
        buildEngine(
            planId, runner, gps, clock,
            buildGate(FakePermissionChecker(), sampler, clock),
            toggles = { StageToggles(locationStageEnabled = true, testStageEnabled = false) }
        ).run()

        assertEquals(0, runner.calls)
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("ok_gps_only", attempt.status)
        assertEquals(1, attempt.successOrdinal)
        assertEquals("test_skipped", attempt.stageNotes)
        // # 闸门 Verified 的审计也随 ok_gps_only 落库
        assertEquals(true, attempt.fixIsMock)
        assertEquals(5_000L, attempt.verifiedAt)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `test stage off and gate mismatch yields typed failure with audit and no quota`() = runTest {
        // # 3：testStage OFF + Rejected(MISMATCH 带 audit) → 类型化失败、不占配额、审计落库
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        fixedDistance = 150f
        val sampler = ScriptedSampler(
            listOf(
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L)),
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L))
            ),
            // # 第二次采样后落点回到容差内，计划得以收尾
            afterSample = { calls -> if (calls >= 2) fixedDistance = 0f }
        )
        buildEngine(
            planId, runner, gps, clock,
            buildGate(FakePermissionChecker(), sampler, clock),
            toggles = { StageToggles(locationStageEnabled = true, testStageEnabled = false) }
        ).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        // # AC-F2-1 关键回归：闸门拒绝的 ok_gps_only 绝不计配额
        assertEquals("failed", attempts[0].status)
        assertEquals("LOCATION_MISMATCH", attempts[0].failureReason)
        assertNull(attempts[0].successOrdinal)
        // # 拒绝也带审计（actual 坐标 + 距离 + 使用的 tolerance）
        assertEquals(39.9, attempts[0].actualLatitude!!, 0.0001)
        assertEquals(150.0, attempts[0].locationErrorMeters!!, 0.001)
        assertEquals(100.0, attempts[0].toleranceMetersUsed!!, 0.001)
        assertNotNull(attempts[0].verifiedAt)
        assertEquals("ok_gps_only", attempts[1].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `location stage off and gate not mocked yields typed failure without quota`() = runTest {
        // # 4：locationStage OFF + Rejected(NOT_MOCKED) → 类型化失败、不占配额
        // #（闸门不被 toggle 跳过，INV-F2-1）
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val sampler = ScriptedSampler(
            listOf(
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, isMock = false, fixAtMs = 4_900L)),
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L))
            )
        )
        buildEngine(
            planId, runner, gps, clock,
            buildGate(FakePermissionChecker(), sampler, clock),
            toggles = { StageToggles(locationStageEnabled = false, testStageEnabled = true) }
        ).run()

        assertEquals(0, gps.calls)
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("LOCATION_NOT_MOCKED", attempts[0].failureReason)
        assertEquals("gps_skipped", attempts[0].stageNotes)
        assertEquals(false, attempts[0].fixIsMock)
        assertNull(attempts[0].successOrdinal)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `mid run permission denial pauses the session and never retries`() = runTest {
        // # 5：mid-run DENIED → attempt failed + session "paused" + state PAUSED +
        // # 无第二次 attempt（INV-F2-3：永久失败绝不进重试环）
        val (planId, taskId) = seedPlan(quota = 2)
        val clock = VirtualClock().also { it.now = 5_000L }
        val checker = FakePermissionChecker()
        // # 第一次测试成功后操作员在系统设置里撤销了定位权限
        val runner = FakeRunner(clock.nowMs, afterCall = {
            checker.state = LocationPermissionState.DENIED
        })
        val gps = FakeGps()
        val sampler = ScriptedSampler(
            listOf(SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L)))
        )
        val engine = buildEngine(
            planId, runner, gps, clock, buildGate(checker, sampler, clock)
        )
        engine.run()

        // # 恰好 2 个 attempt：一个成功、一个永久失败，没有第三次（不重试）
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("succeeded", attempts[0].status)
        assertEquals("failed", attempts[1].status)
        assertEquals("LOCATION_PERMISSION_DENIED", attempts[1].failureReason)
        assertNull(attempts[1].successOrdinal)
        assertEquals(1, runner.calls)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)

        // # session 暂停 + operator 可见终态
        assertEquals("paused", db.runSessionDao().getLatest()!!.status)
        assertEquals(AutomationState.PAUSED, engine.state.value)
    }

    @Test
    fun `pre start preflight denial creates no session and no attempt`() = runTest {
        // # 6：pre-start preflight DENIED → 无 session、无 attempt、state ERROR
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val checker = FakePermissionChecker(LocationPermissionState.DENIED)
        val sampler = ScriptedSampler(
            listOf(SampleResult.Fix(mockFix(elapsedNanos = 1_000L)))
        )
        val engine = buildEngine(
            planId, runner, gps, clock, buildGate(checker, sampler, clock)
        )
        engine.run()

        assertNull(db.runSessionDao().getLatest())
        assertTrue(db.testAttemptDao().getAttemptsForTask(taskId).isEmpty())
        assertEquals(AutomationState.ERROR, engine.state.value)
        assertEquals(0, gps.calls)
        assertEquals(0, runner.calls)
        assertEquals(0, sampler.calls)
    }

    @Test
    fun `fix older than the activation anchor is rejected as stale`() = runTest {
        // # 7：locationStage ON，采样 fix 早于本次激活锚点 → STALE_FIX（AC-F2-2 锚点语义）
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        nanos = 1_000L
        val sampler = ScriptedSampler(
            listOf(
                // # 锚点 = 激活确认时刻的 1000ns；999ns 的 fix 是上一状态的陈旧证据
                SampleResult.Fix(mockFix(elapsedNanos = 999L, fixAtMs = 4_900L)),
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L))
            )
        )
        buildEngine(planId, runner, gps, clock, buildGate(FakePermissionChecker(), sampler, clock)).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("LOCATION_STALE_FIX", attempts[0].failureReason)
        assertNull(attempts[0].successOrdinal)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(2, gps.calls)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `recoverable rejection retries the same location only after the buffer gate`() = runTest {
        // # 8：可恢复失败后，下一次 attempt 前有 buffer gate 等待（INV-5 血缘）
        val (planId, taskId) = seedPlan(quota = 1, bufferSeconds = 60)
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val sampler = ScriptedSampler(
            listOf(
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, isMock = false, fixAtMs = 4_900L)),
                SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L))
            )
        )
        // # delay 瞬间抓取 cooldown 投影（等待开始后、结束前）
        var engineRef: AutomationEngine? = null
        val cooldownSeen = mutableListOf<CooldownInfo?>()
        val engine = buildEngine(
            planId, runner, gps, clock,
            buildGate(FakePermissionChecker(), sampler, clock),
            bufferSeconds = 60,
            delayMs = { ms ->
                cooldownSeen.add(engineRef?.cooldown?.value)
                clock.delayMs(ms)
            }
        )
        engineRef = engine
        engine.run()

        // # 第二次 attempt 恰好等满 60s 缓冲，且 cooldown 投影指向同点重试
        assertEquals(listOf(60_000L), clock.delays)
        val cooldown = cooldownSeen.single()
        assertNotNull(cooldown)
        assertEquals(60_000L, cooldown!!.remainingMs)
        assertEquals("retry same location", cooldown.nextAction)

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("LOCATION_NOT_MOCKED", attempts[0].failureReason)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `crash window after audit write keeps audit columns through recovery sweep`() = runTest {
        // # 9：audit 写入后、finalize 前崩溃 → 恢复清扫标 interrupted，审计列保留
        val (planId, taskId) = seedPlan(quota = 1)
        val staleSessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId))
        db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = staleSessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                // # 崩溃窗口：闸门 Verified 的审计已落库，attempt 未及收尾
                actualLatitude = 39.91, actualLongitude = 116.41,
                locationErrorMeters = 42.0, fixIsMock = true,
                fixAt = 650L, verifiedAt = 660L,
                fixAccuracyMeters = 3.5, toleranceMetersUsed = 100.0
            )
        )
        val clock = VirtualClock().also { it.now = 5_000L }
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        val sampler = ScriptedSampler(
            listOf(SampleResult.Fix(mockFix(elapsedNanos = 1_000L, fixAtMs = 4_900L)))
        )
        buildEngine(planId, runner, gps, clock, buildGate(FakePermissionChecker(), sampler, clock)).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        // # 恢复清扫标 interrupted（诚实记录），审计列原样保留
        assertEquals("interrupted", attempts[0].status)
        assertEquals(39.91, attempts[0].actualLatitude!!, 0.0001)
        assertEquals(116.41, attempts[0].actualLongitude!!, 0.0001)
        assertEquals(42.0, attempts[0].locationErrorMeters!!, 0.001)
        assertEquals(true, attempts[0].fixIsMock)
        assertEquals(650L, attempts[0].fixAt)
        assertEquals(660L, attempts[0].verifiedAt)
        assertEquals(3.5, attempts[0].fixAccuracyMeters!!, 0.001)
        assertEquals(100.0, attempts[0].toleranceMetersUsed!!, 0.001)
        assertEquals("succeeded", attempts[1].status)
    }
}
