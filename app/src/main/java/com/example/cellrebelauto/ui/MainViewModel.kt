package com.example.cellrebelauto.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutoConfig
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.TestResult
import com.example.cellrebelauto.repository.TestRepository
import com.example.cellrebelauto.util.CsvExporter
import com.example.cellrebelauto.util.DebugExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the main UI. Bridges AutomationService state
 * and provides actions for the Compose screens.
 *
 * # 主界面 ViewModel：桥接 AutomationService 的状态
 * # 并为 Compose 界面提供操作接口
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TestRepository(AppDatabase.getInstance(application))

    // ---- Config (persisted in memory; could use DataStore for persistence) ----

    private val _config = MutableStateFlow(AutoConfig())
    val config: StateFlow<AutoConfig> = _config

    // ---- Navigation ----

    private val _currentScreen = MutableStateFlow(Screen.CONTROL)
    val currentScreen: StateFlow<Screen> = _currentScreen

    // ---- Data from service (delegated flows) ----

    // # 来自 AutomationService 的状态流
    val isRunning: StateFlow<Boolean> = AutomationService.isRunning
    val currentState: StateFlow<AutomationState> = AutomationService.currentState
    val cycleCount: StateFlow<Int> = AutomationService.cycleCount
    val logs: StateFlow<List<String>> = AutomationService.logs
    val isServiceConnected: StateFlow<Boolean> = AutomationService.isServiceConnected

    // ---- Data from repository ----

    // # 测试结果列表
    val results: StateFlow<List<TestResult>> = repository.getAllResults()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Actions ----

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun updateConfig(newConfig: AutoConfig) {
        _config.value = newConfig
        _currentScreen.value = Screen.CONTROL
    }

    fun startAutomation() {
        val cfg = _config.value
        if (!cfg.isGpsRangeValid() || !cfg.isTimingValid()) {
            showToast("Please configure valid GPS range and timing first")
            return
        }
        if (!isServiceConnected.value) {
            showToast("Accessibility service not connected. Enable it in Settings.")
            return
        }
        AutomationService.startAutomation(cfg)
    }

    fun stopAutomation() {
        AutomationService.stopAutomation()
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val allResults = withContext(Dispatchers.IO) {
                    repository.getAllResultsForExport()
                }
                if (allResults.isEmpty()) {
                    showToast("No results to export")
                    return@launch
                }
                val exporter = CsvExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.export(allResults)
                }
                showToast("Exported: $fileName")
            } catch (e: Exception) {
                showToast("Export failed: ${e.message}")
            }
        }
    }

    // ---- Debug actions ----

    /**
     * Exports current logs to a .txt file in Downloads.
     * # 将当前日志导出到 Downloads 目录的 .txt 文件
     */
    fun exportLogs() {
        viewModelScope.launch {
            try {
                val currentLogs = logs.value
                if (currentLogs.isEmpty()) {
                    showToast("No logs to export")
                    return@launch
                }
                val exporter = DebugExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.exportLogs(currentLogs)
                }
                showToast("Logs exported: $fileName")
            } catch (e: Exception) {
                showToast("Log export failed: ${e.message}")
            }
        }
    }

    /**
     * Dumps the current foreground app's accessibility tree to a .txt file.
     * # 将当前前台应用的无障碍节点树导出到 .txt 文件
     */
    fun dumpAccessibilityTree() {
        viewModelScope.launch {
            try {
                if (!isServiceConnected.value) {
                    showToast("Accessibility service not connected")
                    return@launch
                }
                val root = AutomationService.getRootNodeForDump()
                if (root == null) {
                    showToast("No active window to dump")
                    return@launch
                }
                val pkg = AutomationService.getCurrentForegroundPackage() ?: "unknown"
                val exporter = DebugExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.dumpAccessibilityTree(root, pkg)
                }
                showToast("A11y tree dumped: $fileName ($pkg)")
            } catch (e: Exception) {
                showToast("Dump failed: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }
}

/**
 * Navigation screens.
 * # 导航页面枚举
 */
enum class Screen {
    CONTROL,  // # 主控制面板
    CONFIG,   // # 配置页面
    HISTORY   // # 历史记录页面
}
