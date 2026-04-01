package com.example.cellrebelauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.model.TestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History screen showing all test results.
 * # 历史记录界面：显示所有测试结果
 */
@Composable
fun HistoryScreen(
    results: List<TestResult>,
    onExportCsv: () -> Unit,
    onBack: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // # 标题和记录数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Test History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${results.size} records", fontSize = 14.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = onExportCsv,
                enabled = results.isNotEmpty()
            ) { Text("Export CSV") }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // # 结果列表
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.status == "ok")
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // # 循环编号和时间
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("#${result.cycleIndex}", fontWeight = FontWeight.Bold)
                            Text(
                                dateFormat.format(Date(result.timestamp)),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // # 分数
                        if (result.status == "ok") {
                            Text("Web: ${result.webBrowsingScore}  |  Video: ${result.videoStreamingScore}")
                        } else {
                            Text(
                                "Status: ${result.status}",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }

                        // # 位置
                        Text(
                            "Location: ${result.latitude}, ${result.longitude}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}
