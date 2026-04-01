package com.example.cellrebelauto.model

/**
 * Configuration for automation cycles.
 * # 自动化循环的配置参数，包含 GPS 矩形范围和时间控制
 */
data class AutoConfig(
    // # 西南角纬度（矩形左下）
    val southWestLat: Double = 0.0,
    // # 西南角经度
    val southWestLng: Double = 0.0,
    // # 东北角纬度（矩形右上）
    val northEastLat: Double = 0.0,
    // # 东北角经度
    val northEastLng: Double = 0.0,
    // # 测试完成后等待收集数据的延迟（秒）
    val collectDelaySeconds: Int = 120,
    // # 设置 GPS 后等待信号稳定的间隔（秒）
    val cycleIntervalSeconds: Int = 60,
    // # 最大循环次数，0 = 无限循环
    val maxCycles: Int = 0
) {
    // # 经纬度最小/最大值，确保 min <= max
    val minLat get() = minOf(southWestLat, northEastLat)
    val maxLat get() = maxOf(southWestLat, northEastLat)
    val minLng get() = minOf(southWestLng, northEastLng)
    val maxLng get() = maxOf(southWestLng, northEastLng)

    // # 验证 GPS 范围合法性
    fun isGpsRangeValid(): Boolean =
        southWestLat in -90.0..90.0 &&
            northEastLat in -90.0..90.0 &&
            southWestLng in -180.0..180.0 &&
            northEastLng in -180.0..180.0 &&
            minLat != maxLat && minLng != maxLng

    // # 验证时间参数合法性
    fun isTimingValid(): Boolean =
        collectDelaySeconds >= 0 && cycleIntervalSeconds >= 0 && maxCycles >= 0

    // # 序列化为快照字符串，存入数据库
    fun toSnapshot(): String = buildString {
        append("gps=$minLat,$maxLat,$minLng,$maxLng")
        append("|collect=$collectDelaySeconds")
        append("|interval=$cycleIntervalSeconds")
        append("|maxCycles=$maxCycles")
    }
}
