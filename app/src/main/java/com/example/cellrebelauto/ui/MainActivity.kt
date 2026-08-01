package com.example.cellrebelauto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cellrebelauto.ui.theme.CellRebelAutoTheme

/**
 * Main activity — hosts the Compose UI.
 * # 主 Activity，承载 Compose 界面
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CellRebelAutoTheme {
                MainApp()
            }
        }
    }
}

/**
 * Root composable that manages screen navigation via ViewModel.
 * # 根 Composable，通过 ViewModel 管理页面导航
 */
@Composable
fun MainApp(vm: MainViewModel = viewModel()) {
    val currentScreen by vm.currentScreen.collectAsState()
    val config by vm.config.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val currentState by vm.currentState.collectAsState()
    val cycleCount by vm.cycleCount.collectAsState()
    val logs by vm.logs.collectAsState()
    val attempts by vm.attempts.collectAsState()
    val isServiceConnected by vm.isServiceConnected.collectAsState()
    val planState by vm.planUiState.collectAsState()
    val planConfig by vm.planConfig.collectAsState()
    val importErrors by vm.importErrors.collectAsState()
    val importNotice by vm.importNotice.collectAsState()
    val currentTask by vm.currentTask.collectAsState()
    val cooldown by vm.cooldown.collectAsState()
    val lastFailure by vm.lastFailure.collectAsState()

    when (currentScreen) {
        Screen.PLAN -> {
            PlanScreen(
                planState = planState,
                planConfig = planConfig,
                isRunning = isRunning,
                isServiceConnected = isServiceConnected,
                importErrors = importErrors,
                importNotice = importNotice,
                onImport = { vm.importCsv(it) },
                onSetGlobalBuffer = { vm.setGlobalBuffer(it) },
                onSetTestTimeout = { vm.setTestTimeout(it) },
                onSetGpsSettle = { vm.setGpsSettle(it) },
                onStartOrResume = { vm.startOrResumePlan() },
                onStop = { vm.stopAutomation() },
                onOpenRun = { vm.navigateTo(Screen.RUN) },
                onOpenHistory = { vm.navigateTo(Screen.HISTORY) }
            )
        }

        Screen.RUN -> {
            ControlScreen(
                isRunning = isRunning,
                currentState = currentState,
                cycleCount = cycleCount,
                currentTask = currentTask,
                cooldown = cooldown,
                lastFailure = lastFailure,
                planCompletedSuccesses = planState.completedSuccesses,
                planTotalSuccesses = planState.plan?.totalRequiredSuccesses ?: 0,
                logs = logs,
                isServiceConnected = isServiceConnected,
                onStop = { vm.stopAutomation() },
                onOpenPlan = { vm.navigateTo(Screen.PLAN) },
                onOpenHistory = { vm.navigateTo(Screen.HISTORY) },
                // # 调试功能
                onExportLogs = { vm.exportLogs() },
                onDumpA11yTree = { vm.dumpAccessibilityTree() }
            )
        }

        Screen.CONFIG -> {
            ConfigScreen(
                currentConfig = config,
                onSave = { vm.updateConfig(it) },
                onBack = { vm.navigateTo(Screen.PLAN) }
            )
        }

        Screen.HISTORY -> {
            HistoryScreen(
                attempts = attempts,
                onExportCsv = { vm.exportCsv() },
                onBack = { vm.navigateTo(Screen.PLAN) }
            )
        }
    }
}
