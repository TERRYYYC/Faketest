package com.example.cellrebelauto.automation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutoConfig
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.repository.TestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Android AccessibilityService that hosts the automation engine.
 * Thin wrapper — all logic lives in AutomationEngine and Handlers.
 *
 * # 无障碍服务：承载自动化引擎的 Android 系统服务
 * # 自身逻辑很薄，所有业务逻辑在 Engine 和 Handler 中
 *
 * Communication with UI:
 *   - Companion object exposes StateFlow fields for Compose to collect
 *   - startAutomation() / stopAutomation() for user control
 *
 * # 与 UI 的通信：
 * #   - 通过 companion object 的 StateFlow 让 Compose 采集状态
 * #   - startAutomation() / stopAutomation() 供用户控制
 */
class AutomationService : AccessibilityService() {

    // # 服务级别的协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // # 当前运行的自动化任务
    private var automationJob: Job? = null
    // # 当前引擎实例
    private var engine: AutomationEngine? = null

    companion object {
        private const val TAG = "AutomationSvc"

        // # 服务实例引用（供 UI 调用）
        private var instance: AutomationService? = null

        // ---- Shared state (collected by UI) ----

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _currentState = MutableStateFlow(AutomationState.IDLE)
        val currentState: StateFlow<AutomationState> = _currentState

        private val _cycleCount = MutableStateFlow(0)
        val cycleCount: StateFlow<Int> = _cycleCount

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs

        // # 服务是否已连接（用于 UI 检查）
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected

        /**
         * Starts automation with given config.
         * # 使用给定配置启动自动化
         */
        fun startAutomation(config: AutoConfig) {
            instance?.startWithConfig(config) ?: run {
                Log.e(TAG, "Service not connected — cannot start")
            }
        }

        /**
         * Stops the running automation.
         * # 停止正在运行的自动化
         */
        fun stopAutomation() {
            instance?.stopRunning()
        }

        /**
         * Returns the current foreground window's root node for tree dump.
         * # 返回当前前台窗口的根节点，用于无障碍树 dump
         */
        fun getRootNodeForDump(): android.view.accessibility.AccessibilityNodeInfo? {
            return instance?.rootInActiveWindow
        }

        /**
         * Returns the current foreground package name.
         * # 返回当前前台应用的包名
         */
        fun getCurrentForegroundPackage(): String? {
            return instance?.rootInActiveWindow?.packageName?.toString()
        }
    }

    // ---- Lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.d(TAG, "Service connected")
        addLog("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // # 新架构下不需要处理事件 — 引擎使用轮询模式
        // # 但保留方法以满足 AccessibilityService 的要求
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        automationJob?.cancel()
        serviceScope.cancel()
        instance = null
        _isServiceConnected.value = false
        _isRunning.value = false
        Log.d(TAG, "Service destroyed")
    }

    // ---- Control ----

    /**
     * Creates the engine, handlers, and starts the automation coroutine.
     * # 创建引擎和处理器，启动自动化协程
     */
    private fun startWithConfig(config: AutoConfig) {
        if (_isRunning.value) {
            addLog("Already running, ignoring start request")
            return
        }
        if (!config.isGpsRangeValid() || !config.isTimingValid()) {
            addLog("ERROR: Invalid configuration")
            return
        }

        val bridge = AccessibilityBridge(this)
        val repository = TestRepository(AppDatabase.getInstance(applicationContext))

        val cellRebelHandler = CellRebelHandler(bridge) { addLog(it) }
        val fakeGpsHandler = FakeGpsHandler(bridge) { addLog(it) }

        val newEngine = AutomationEngine(
            config = config,
            repository = repository,
            cellRebelHandler = cellRebelHandler,
            fakeGpsHandler = fakeGpsHandler
        )
        engine = newEngine
        _isRunning.value = true

        // # 监听引擎状态并转发到 companion 的 StateFlow
        automationJob = serviceScope.launch {
            // # 收集状态变化
            launch {
                newEngine.state.collect { _currentState.value = it }
            }
            // # 收集循环计数
            launch {
                newEngine.cycleCount.collect { _cycleCount.value = it }
            }
            // # 收集日志
            launch {
                newEngine.logs.collect { _logs.value = it }
            }

            // # 运行引擎（阻塞直到完成/取消/出错）
            try {
                newEngine.run()
            } finally {
                _isRunning.value = false
                engine = null
            }
        }
    }

    /**
     * Cancels the automation coroutine.
     * # 取消自动化协程
     */
    private fun stopRunning() {
        if (automationJob?.isActive == true) {
            addLog("Stopping automation...")
            automationJob?.cancel()
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        _logs.value = (_logs.value + entry).takeLast(200)
        Log.d(TAG, message)
    }
}
