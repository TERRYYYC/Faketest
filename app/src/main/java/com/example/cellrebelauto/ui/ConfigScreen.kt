package com.example.cellrebelauto.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.model.AutoConfig

/**
 * Configuration screen for GPS range and timing parameters.
 * # 配置界面：设置 GPS 范围和时间参数
 */
@Composable
fun ConfigScreen(
    currentConfig: AutoConfig,
    onSave: (AutoConfig) -> Unit,
    onBack: () -> Unit
) {
    var swLat by remember { mutableStateOf(currentConfig.southWestLat.toString()) }
    var swLng by remember { mutableStateOf(currentConfig.southWestLng.toString()) }
    var neLat by remember { mutableStateOf(currentConfig.northEastLat.toString()) }
    var neLng by remember { mutableStateOf(currentConfig.northEastLng.toString()) }
    var collectDelay by remember { mutableStateOf(currentConfig.collectDelaySeconds.toString()) }
    var cycleInterval by remember { mutableStateOf(currentConfig.cycleIntervalSeconds.toString()) }
    var maxCycles by remember { mutableStateOf(currentConfig.maxCycles.toString()) }
    // # 验证错误信息
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // # 获取当前位置并填充坐标（±0.01度 ≈ 1km 范围）
    val fillFromCurrentLocation = {
        val location = getLastKnownLocation(context)
        if (location != null) {
            val offset = 0.01
            swLat = "%.6f".format(location.first - offset)
            swLng = "%.6f".format(location.second - offset)
            neLat = "%.6f".format(location.first + offset)
            neLng = "%.6f".format(location.second + offset)
            errorMessage = null
        } else {
            errorMessage = "Unable to get current location. Make sure GPS is enabled."
        }
    }

    // # 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fillFromCurrentLocation()
        } else {
            errorMessage = "Location permission denied."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Configuration", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(16.dp))

        // # GPS 矩形范围设置
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPS Range (bounding box)", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                // # 西南角（左下）
                Text("South-West corner (bottom-left)", fontSize = 12.sp)
                CoordinateRow(
                    lat = swLat, onLatChange = { swLat = it },
                    lng = swLng, onLngChange = { swLng = it }
                )

                // # 东北角（右上）
                Text("North-East corner (top-right)", fontSize = 12.sp)
                CoordinateRow(
                    lat = neLat, onLatChange = { neLat = it },
                    lng = neLng, onLngChange = { neLng = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // # 使用当前位置按钮
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use Current Location (~1km range)")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // # 时间参数设置
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Timing", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                // # 收集延迟：测试完成后等待多久再采集分数
                OutlinedTextField(
                    value = collectDelay,
                    onValueChange = { collectDelay = it },
                    label = { Text("Collect delay (seconds)") },
                    supportingText = { Text("Wait time after test completes before reading scores") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // # 周期间隔：设置 GPS 后等待多久再开始测试
                OutlinedTextField(
                    value = cycleInterval,
                    onValueChange = { cycleInterval = it },
                    label = { Text("Cycle interval (seconds)") },
                    supportingText = { Text("Wait time after GPS set before starting test") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // # 最大循环数
                OutlinedTextField(
                    value = maxCycles,
                    onValueChange = { maxCycles = it },
                    label = { Text("Max cycles (0 = unlimited)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // # 错误提示
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // # 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val config = AutoConfig(
                        southWestLat = swLat.toDoubleOrNull() ?: 0.0,
                        southWestLng = swLng.toDoubleOrNull() ?: 0.0,
                        northEastLat = neLat.toDoubleOrNull() ?: 0.0,
                        northEastLng = neLng.toDoubleOrNull() ?: 0.0,
                        collectDelaySeconds = collectDelay.toIntOrNull() ?: 120,
                        cycleIntervalSeconds = cycleInterval.toIntOrNull() ?: 60,
                        maxCycles = maxCycles.toIntOrNull() ?: 0
                    )
                    when {
                        !config.isGpsRangeValid() -> {
                            errorMessage = "Invalid GPS range. Check coordinates."
                        }
                        !config.isTimingValid() -> {
                            errorMessage = "Invalid timing. Values must be positive."
                        }
                        else -> {
                            errorMessage = null
                            onSave(config)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * Lat/Lng input row with validation.
 * # 经纬度输入行，带正则验证
 */
@Composable
private fun CoordinateRow(
    lat: String, onLatChange: (String) -> Unit,
    lng: String, onLngChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = lat,
            onValueChange = { if (it.matches(Regex("-?\\d*\\.?\\d*"))) onLatChange(it) },
            label = { Text("Lat") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = lng,
            onValueChange = { if (it.matches(Regex("-?\\d*\\.?\\d*"))) onLngChange(it) },
            label = { Text("Lng") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

// # 获取最后已知位置，优先 GPS，其次 Network
@SuppressLint("MissingPermission")
private fun getLastKnownLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    return location?.let { Pair(it.latitude, it.longitude) }
}
