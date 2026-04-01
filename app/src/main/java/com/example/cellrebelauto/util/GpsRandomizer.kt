package com.example.cellrebelauto.util

import com.example.cellrebelauto.model.AutoConfig
import kotlin.random.Random

/**
 * Generates random GPS points within the configured bounding box.
 * # 在配置的矩形范围内生成随机 GPS 坐标
 */
class GpsRandomizer(private val config: AutoConfig) {

    /**
     * Returns (latitude, longitude) rounded to 6 decimal places (~0.11m precision).
     * # 返回精度约 0.11 米的随机坐标对
     */
    fun randomPoint(): Pair<Double, Double> {
        val lat = Random.nextDouble(config.minLat, config.maxLat)
        val lng = Random.nextDouble(config.minLng, config.maxLng)
        return Pair(
            Math.round(lat * 1_000_000.0) / 1_000_000.0,
            Math.round(lng * 1_000_000.0) / 1_000_000.0
        )
    }
}
