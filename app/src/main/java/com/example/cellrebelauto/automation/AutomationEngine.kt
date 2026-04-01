package com.example.cellrebelauto.automation

import android.util.Log
import com.example.cellrebelauto.model.AutoConfig
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.TestResult
import com.example.cellrebelauto.repository.TestRepository
import com.example.cellrebelauto.util.GpsRandomizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * Main automation orchestrator — drives the complete test cycle using
 * a sequential coroutine-based approach instead of an event-driven state machine.
 *
 * # 自动化主引擎 — 使用顺序协程驱动完整的测试循环
 * # 相比事件驱动的状态机，代码更线性、更易理解
 *
 * Workflow per cycle:
 *   1. Generate random GPS coordinates
 *   2. Open Fake GPS → set location on map → start spoofing
 *   3. Wait for GPS to settle (cycleIntervalSeconds)
 *   4. Open CellRebel → run test → collect scores
 *   5. Save results
 *   6. Repeat until maxCycles or stopped
 *
 * # 每次循环的工作流：
 * #   1. 生成随机 GPS 坐标
 * #   2. 打开 Fake GPS → 在地图上设置位置 → 开始伪造
 * #   3. 等待 GPS 信号稳定（cycleIntervalSeconds）
 * #   4. 打开 CellRebel → 运行测试 → 采集分数
 * #   5. 保存结果
 * #   6. 重复直到达到最大循环数或被停止
 */
class AutomationEngine(
    private val config: AutoConfig,
    private val repository: TestRepository,
    private val cellRebelHandler: CellRebelHandler,
    private val fakeGpsHandler: FakeGpsHandler
) {
    companion object {
        private const val TAG = "AutoEngine"
        // # 单步操作失败后的最大重试次数
        private const val MAX_STEP_RETRIES = 3
    }

    // # 当前状态
    private val _state = MutableStateFlow(AutomationState.IDLE)
    val state: StateFlow<AutomationState> = _state

    // # 已完成的循环数
    private val _cycleCount = MutableStateFlow(0)
    val cycleCount: StateFlow<Int> = _cycleCount

    // # 日志列表（保留最近 200 条）
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val gpsRandomizer = GpsRandomizer(config)
    private var runSessionId: Long = 0

    /**
     * Runs the full automation loop. Call from a coroutine scope.
     * Cancelling the coroutine cleanly stops the automation.
     *
     * # 运行完整的自动化循环。在协程作用域中调用。
     * # 取消协程即可优雅地停止自动化。
     */
    suspend fun run() = coroutineScope {
        // # 创建数据库会话
        runSessionId = repository.createSession(config.toSnapshot())
        _cycleCount.value = 0
        log("=== Automation started (session #$runSessionId) ===")
        log("GPS range: (${config.minLat},${config.minLng}) → (${config.maxLat},${config.maxLng})")
        log("Settings: collect=${config.collectDelaySeconds}s, interval=${config.cycleIntervalSeconds}s, maxCycles=${config.maxCycles}")

        try {
            while (isActive) {
                val cycleIndex = _cycleCount.value + 1
                log("--- Cycle $cycleIndex ---")

                // # 生成新的随机 GPS 坐标
                val (lat, lng) = gpsRandomizer.randomPoint()
                log("Target: ($lat, $lng)")

                // ==================== Phase 1: Set GPS ====================
                updateState(AutomationState.LAUNCHING_FAKE_GPS)
                val gpsSuccess = retryWithFallback("Set GPS location") {
                    fakeGpsHandler.setLocation(lat, lng)
                }
                if (!gpsSuccess) {
                    log("WARN: Failed to set GPS, continuing with current location")
                }

                ensureActive()

                // ==================== Phase 2: Wait for GPS settle ====================
                updateState(AutomationState.WAITING_INTERVAL)
                log("Waiting ${config.cycleIntervalSeconds}s for GPS to settle...")
                delay(config.cycleIntervalSeconds * 1000L)

                ensureActive()

                // ==================== Phase 3: Run CellRebel test ====================
                updateState(AutomationState.LAUNCHING_CELLREBEL)
                var testScores: CellRebelHandler.TestScores? = null

                val testSuccess = retryWithFallback("Run CellRebel test") {
                    testScores = cellRebelHandler.runTest(
                        collectDelayMs = config.collectDelaySeconds * 1000L
                    )
                }

                ensureActive()

                // ==================== Phase 4: Save result ====================
                updateState(AutomationState.COLLECTING_RESULT)
                val result = TestResult(
                    runSessionId = runSessionId,
                    timestamp = System.currentTimeMillis(),
                    webBrowsingScore = testScores?.webBrowsingScore ?: -1.0,
                    videoStreamingScore = testScores?.videoStreamingScore ?: -1.0,
                    latitude = lat,
                    longitude = lng,
                    cycleIndex = cycleIndex,
                    status = if (testScores != null) "ok" else "error_no_scores"
                )
                repository.insertResult(result)

                _cycleCount.value = cycleIndex
                log("Cycle $cycleIndex complete: Web=${result.webBrowsingScore}, Video=${result.videoStreamingScore}")

                // # 检查是否达到最大循环数
                if (config.maxCycles > 0 && cycleIndex >= config.maxCycles) {
                    log("Max cycles reached (${config.maxCycles}). Done.")
                    break
                }
            }

            // # 正常完成
            updateState(AutomationState.DONE)
            repository.finishSession(runSessionId, "completed", _cycleCount.value)
            log("=== Automation completed: ${_cycleCount.value} cycles ===")

        } catch (e: CancellationException) {
            // # 被用户取消
            updateState(AutomationState.IDLE)
            repository.finishSession(runSessionId, "stopped", _cycleCount.value)
            log("=== Automation stopped by user ===")
            throw e // # 重新抛出以正确传播取消

        } catch (e: Exception) {
            // # 不可恢复的错误
            updateState(AutomationState.ERROR)
            repository.finishSession(runSessionId, "error", _cycleCount.value)
            log("=== Automation ERROR: ${e.message} ===")
            Log.e(TAG, "Automation failed", e)
        }
    }

    /**
     * Executes [block] with retry logic. Returns true if successful.
     * # 带重试逻辑执行代码块。成功返回 true。
     */
    private suspend fun retryWithFallback(
        stepName: String,
        maxRetries: Int = MAX_STEP_RETRIES,
        block: suspend () -> Unit
    ): Boolean {
        for (attempt in 1..maxRetries) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e // # 不拦截取消异常
            } catch (e: Exception) {
                log("RETRY: $stepName failed (attempt $attempt/$maxRetries): ${e.message}")
                Log.w(TAG, "$stepName attempt $attempt failed", e)
                if (attempt < maxRetries) {
                    delay(2000L * attempt) // # 递增延迟重试
                }
            }
        }
        log("FAILED: $stepName after $maxRetries attempts")
        return false
    }

    private fun updateState(newState: AutomationState) {
        val old = _state.value
        _state.value = newState
        if (old != newState) {
            Log.d(TAG, "State: $old → $newState")
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        _logs.value = (_logs.value + entry).takeLast(200)
        Log.d(TAG, message)
    }
}
