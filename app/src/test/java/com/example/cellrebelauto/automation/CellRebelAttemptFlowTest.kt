package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.CellRebelFixtures
import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified attempt-lifecycle tests driving CellRebelAttemptFlow against a
 * scripted fake driver with virtual time (AC-B2/B3/B5, INV-6/7).
 * # 已验证尝试生命周期测试：脚本化假驱动 + 虚拟时间
 */
class CellRebelAttemptFlowTest {

    /**
     * Scripted fake driver: each snapshot() consumes one frame; when the queue
     * runs out it keeps returning the last frame. Click effects are pre-scripted
     * via the frame sequence; counters record click/tap invocations.
     * # 脚本化假驱动：snapshot() 逐帧消费，耗尽后重复最后一帧；
     * # 点击效果由帧序列预先编排，计数器记录点击/坐标点按次数
     */
    private class FakeDriver(frames: List<ScreenNode?>) : CellRebelDriver {
        private val frames = frames.toMutableList()
        var clickStartCount = 0
            private set
        var dispatchTapCount = 0
            private set

        override suspend fun snapshot(): ScreenNode? {
            val current = frames.firstOrNull()
            if (frames.size > 1) frames.removeAt(0)
            return current
        }

        override suspend fun clickStart(): Boolean {
            clickStartCount++
            return true
        }

        override suspend fun dispatchStartTap(): Boolean {
            dispatchTapCount++
            return true
        }
    }

    /** # 虚拟时钟：只在 delayMs 时前进 */
    private class VirtualClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    private fun newFlow(clock: VirtualClock) = CellRebelAttemptFlow(
        nowMs = clock.nowMs,
        delayMs = clock.delayMs
    )

    @Test
    fun `never transitions to running yields NO_RUNNING_EVIDENCE after fallback tap`() {
        val clock = VirtualClock()
        val driver = FakeDriver(listOf(CellRebelFixtures.ready()))
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 15_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.NO_RUNNING_EVIDENCE, (outcome as AttemptOutcome.Failure).reason)
        // # AC-B3：ACTION_CLICK 一次 + 3 秒无 running 证据后坐标点按兜底一次
        assertEquals(1, driver.clickStartCount)
        assertEquals(1, driver.dispatchTapCount)
    }

    @Test
    fun `fallback tap starts run, identical scores to previous run are a valid success`() {
        val clock = VirtualClock()
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),      // t=0: 第一次 ACTION_CLICK 后仍 READY（点击无效）
                CellRebelFixtures.ready(),      // t=1500: 仍 READY
                CellRebelFixtures.running(),    // t=3000: 坐标点按兜底后进入 RUNNING
                CellRebelFixtures.completed(),  // 完成轮询 1（分数 10.00/7.50）
                CellRebelFixtures.completed()   // 完成轮询 2（稳定一致 → 采纳）
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        // # INV-7：与"上一次运行"完全相同的分数同样是合法成功，不做跨尝试比较
        assertTrue(outcome is AttemptOutcome.Success)
        val success = outcome as AttemptOutcome.Success
        assertEquals(10.0, success.webScore, 0.001)
        assertEquals(7.5, success.videoScore, 0.001)
        // # AC-B2：记录了 runningObservedAt（t=3000 观察到 RUNNING）
        assertEquals(3000L, success.runningObservedAt)
        assertEquals(1, driver.clickStartCount)
        assertEquals(1, driver.dispatchTapCount)
    }

    @Test
    fun `running persisting past timeout yields CELLREBEL_TIMEOUT`() {
        val clock = VirtualClock()
        val driver = FakeDriver(listOf(CellRebelFixtures.running()))
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 10_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.CELLREBEL_TIMEOUT, (outcome as AttemptOutcome.Failure).reason)
    }

    @Test
    fun `markers gone with unparseable scores yields SCORE_PARSE_FAILED`() {
        val clock = VirtualClock()
        // # 分数标签在但附近没有可解析的数值/评级（损坏的完成页）
        val brokenCompleted = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode("Web Browsing Score", null, null, false, true),
                ScreenNode("Video Streaming Score", null, null, false, true),
                ScreenNode("Start", null, "android.widget.Button", clickable = true, enabled = true)
            )
        )
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.running(),
                brokenCompleted,
                brokenCompleted
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.SCORE_PARSE_FAILED, (outcome as AttemptOutcome.Failure).reason)
    }

    @Test
    fun `completion requires two identical consecutive score polls`() {
        val clock = VirtualClock()
        val completedA = CellRebelFixtures.completed() // # 10.00 / 7.50
        val completedB = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode(null, null, null, false, true, listOf(
                    ScreenNode("Web Browsing Score", null, null, false, true),
                    ScreenNode("GOOD", null, null, false, true),
                    ScreenNode("8.25", null, null, false, true)
                )),
                ScreenNode(null, null, null, false, true, listOf(
                    ScreenNode("Video Streaming Score", null, null, false, true),
                    ScreenNode("FAIR", null, null, false, true),
                    ScreenNode("6.00", null, null, false, true)
                )),
                ScreenNode("Start", null, "android.widget.Button", clickable = true, enabled = true)
            )
        )
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.running(),
                completedA,  // t=0: 第一次完成轮询，分数 A
                completedB,  // t=1500: 分数变了 → 不采纳
                completedB   // t=3000: 第二次连续相同 → 采纳
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        assertTrue(outcome is AttemptOutcome.Success)
        val success = outcome as AttemptOutcome.Success
        // # 采纳的是稳定后的 B 分数
        assertEquals(8.25, success.webScore, 0.001)
        assertEquals(6.0, success.videoScore, 0.001)
        // # 不稳时点（t=0、t=1500）绝不能产出成功；成功只发生在稳定轮询 t=3000
        assertEquals(3000L, success.endedAt)
    }
}
