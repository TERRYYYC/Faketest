package com.example.cellrebelauto.model.plan

/**
 * Plan-level configuration (O6). Buffer is nullable: null = not yet set, so the
 * Plan screen must require it on first run (design gate §1.1).
 * # 计划级配置。缓冲可空：null 表示尚未设置，计划页首启时必填
 */
data class PlanConfig(
    // # 全局缓冲秒数（相邻两次尝试之间）
    val globalBufferSeconds: Int?,
    // # 单次 CellRebel 测试超时秒数（高级设置，内部默认）
    val testTimeoutSeconds: Int = 90,
    // # Fake GPS 落点后的稳定等待秒数（高级设置）
    val gpsSettleSeconds: Int = 60
)
