package com.example.cellrebelauto.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.model.AutomationState

/**
 * Main control panel screen.
 * # 主控制面板界面：显示状态、日志，提供启动/停止操作
 */
@Composable
fun ControlScreen(
    isRunning: Boolean,
    currentState: AutomationState,
    cycleCount: Int,
    logs: List<String>,
    isServiceConnected: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenHistory: () -> Unit,
    // # 调试功能回调
    onExportLogs: () -> Unit = {},
    onDumpA11yTree: () -> Unit = {}
) {
    val context = LocalContext.current
    val logListState = rememberLazyListState()

    // # 新日志自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // # 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CellRebel Auto",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            // # 服务连接状态指示灯
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isServiceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722))
                )
                Text(
                    text = if (isServiceConnected) " Service ON" else " Service OFF",
                    fontSize = 12.sp,
                    color = if (isServiceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // # 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    currentState == AutomationState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    isRunning -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status", fontWeight = FontWeight.Medium)
                    Text(
                        text = currentState.displayName,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            currentState == AutomationState.ERROR -> MaterialTheme.colorScheme.error
                            currentState == AutomationState.DONE -> Color(0xFF4CAF50)
                            isRunning -> MaterialTheme.colorScheme.primary
                            else -> Color.Gray
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Completed cycles: $cycleCount")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // # 主操作按钮
        if (isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop Automation", modifier = Modifier.padding(vertical = 8.dp))
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = isServiceConnected
            ) {
                Text("Start Automation", modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 功能按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenConfig,
                modifier = Modifier.weight(1f),
                enabled = !isRunning
            ) {
                Text("Config")
            }
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f)
            ) {
                Text("History")
            }
            OutlinedButton(
                onClick = {
                    // # 打开系统无障碍设置页面
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("A11y")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // # 调试工具按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onExportLogs,
                modifier = Modifier.weight(1f),
                enabled = logs.isNotEmpty()
            ) {
                // # 导出日志到文件
                Text("Export Logs", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onDumpA11yTree,
                modifier = Modifier.weight(1f),
                enabled = isServiceConnected
            ) {
                // # 抓取当前前台应用的无障碍节点树
                Text("Dump A11y Tree", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 日志区域标题
        Text("Log", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))

        // # 日志内容（暗色背景终端风格）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A2E)
            )
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No logs yet. Start automation to see activity.",
                        color = Color(0xFF666688),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                logLine.contains("ERROR") || logLine.contains("FAILED") -> Color(0xFFFF6B6B)
                                logLine.contains("WARN") || logLine.contains("RETRY") -> Color(0xFFFFD93D)
                                logLine.contains("===") -> Color(0xFF6BCB77)
                                logLine.contains("---") -> Color(0xFF4D96FF)
                                else -> Color(0xFFCCCCDD)
                            }
                        )
                    }
                }
            }
        }
    }
}
