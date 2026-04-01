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
    val results by vm.results.collectAsState()
    val isServiceConnected by vm.isServiceConnected.collectAsState()

    when (currentScreen) {
        Screen.CONTROL -> {
            ControlScreen(
                isRunning = isRunning,
                currentState = currentState,
                cycleCount = cycleCount,
                logs = logs,
                isServiceConnected = isServiceConnected,
                onStart = { vm.startAutomation() },
                onStop = { vm.stopAutomation() },
                onOpenConfig = { vm.navigateTo(Screen.CONFIG) },
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
                onBack = { vm.navigateTo(Screen.CONTROL) }
            )
        }

        Screen.HISTORY -> {
            HistoryScreen(
                results = results,
                onExportCsv = { vm.exportCsv() },
                onBack = { vm.navigateTo(Screen.CONTROL) }
            )
        }
    }
}
