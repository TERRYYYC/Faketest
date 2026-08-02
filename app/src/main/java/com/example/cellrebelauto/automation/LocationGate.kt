package com.example.cellrebelauto.automation

/**
 * Location verification gate (F002 L1): a pure-Kotlin decision layer with
 * Android seams behind interfaces. NO android.location imports — JVM-testable;
 * the Location.distanceBetween binding is injected as a lambda (Task 4).
 * # 位置验证闸门（F002 L1）：纯 Kotlin 决策层，Android 依赖藏在接口后面。
 * # 无 android.location 导入——可 JVM 单测；distanceBetween 以 lambda 注入
 */

/**
 * One observed location fix, decoupled from android.location.Location.
 * # 一次观测到的位置 fix，与 android.location.Location 解耦
 */
data class ObservedFix(
    val latitude: Double,
    val longitude: Double,
    // # null = !hasAccuracy()
    val accuracyMeters: Float?,
    val isMock: Boolean,
    // # 单调 fix 时间戳（elapsedRealtimeNanos）
    val elapsedRealtimeNanos: Long,
    // # 墙钟时间（Location.time）
    val fixAtMs: Long
)

/**
 * Freshness anchor for the accepted fix.
 * # 接受 fix 的新鲜度锚点
 */
sealed interface GateAnchor {
    /** # Location stage ON：fix 不得早于本次 Fake GPS 激活观察点 */
    data class ActivationAnchor(val anchorNanos: Long) : GateAnchor

    /** # Location stage OFF：验证时采样新鲜 fix，age ≤ FRESH_FIX_BUDGET_MS */
    data object FreshFix : GateAnchor
}

/**
 * Structured per-attempt audit, 1:1 with the Room v5 columns (AC-F2-3).
 * # 每次尝试的结构化审计，与 Room v5 列 1:1 对应
 */
data class LocationAudit(
    val actualLatitude: Double?,
    val actualLongitude: Double?,
    val locationErrorMeters: Double?,
    val fixIsMock: Boolean?,
    val fixAt: Long?,
    val verifiedAt: Long,
    val fixAccuracyMeters: Double?,
    val toleranceMetersUsed: Double
)

/**
 * Gate verdict: Verified carries the audit; Rejected carries a typed reason
 * and an audit only when a fix was observed.
 * # 闸门裁决：Verified 带审计；Rejected 带类型化原因，仅在有 fix 时带审计
 */
sealed interface LocationGateResult {
    data class Verified(val audit: LocationAudit) : LocationGateResult
    data class Rejected(val reason: FailureReason, val audit: LocationAudit?) : LocationGateResult
}

/** # 运行时定位权限状态 */
enum class LocationPermissionState { FINE, COARSE_ONLY, DENIED }

/** # 权限检查接缝（生产实现读 ContextCompat，Task 4） */
interface LocationPermissionChecker {
    fun current(): LocationPermissionState
}

/** # fix 采样接缝（实现内部自带超时，Task 4） */
interface LocationFixSampler {
    suspend fun sampleFix(): SampleResult
}

/** # 采样结果：Fix / 无信号 NoFix / 采样超时 Timeout */
sealed interface SampleResult {
    data class Fix(val fix: ObservedFix) : SampleResult
    data object NoFix : SampleResult
    data object Timeout : SampleResult
}

/**
 * Pure gate decision logic.
 * # 纯闸门决策逻辑
 */
object LocationGateLogic {
    // # OQ-F2-1：默认容差 100m（per-attempt 快照，audit 回显实际使用值）
    const val DEFAULT_TOLERANCE_METERS = 100.0

    // # FreshFix 模式的新鲜度预算（自决，显式常量）
    const val FRESH_FIX_BUDGET_MS = 10_000L

    /**
     * Permanent failures (permission denied / coarse-only) pause the session
     * and never enter the buffer retry loop (AC-F2-2b, INV-F2-3).
     * # 永久失败（权限被拒/仅粗略定位）暂停 session，绝不进 buffer 重试环
     */
    fun isPermanentFailure(reason: FailureReason): Boolean =
        reason == FailureReason.LOCATION_PERMISSION_DENIED ||
            reason == FailureReason.LOCATION_APPROXIMATE_ONLY

    /**
     * Evaluates one observed fix against the target. Decision order:
     * 1) !isMock → NOT_MOCKED; 2) freshness (anchor mode); 3) distance >
     * tolerance → MISMATCH; 4) Verified. Every fix-bearing result carries
     * an audit with toleranceMetersUsed and verifiedAt.
     * # 评估一次 fix：判定顺序 1) 非 mock → NOT_MOCKED；2) 新鲜度（按锚点模式）；
     * # 3) 距离超容差 → MISMATCH；4) Verified。凡带 fix 的结果必带审计
     */
    fun evaluateFix(
        fix: ObservedFix,
        targetLat: Double,
        targetLng: Double,
        toleranceMeters: Double,
        anchor: GateAnchor,
        nowNanos: Long,
        verifiedAtMs: Long,
        distanceMeters: (Double, Double, Double, Double) -> Float
    ): LocationGateResult {
        fun audit(errorMeters: Double?) = LocationAudit(
            actualLatitude = fix.latitude,
            actualLongitude = fix.longitude,
            locationErrorMeters = errorMeters,
            fixIsMock = fix.isMock,
            fixAt = fix.fixAtMs,
            verifiedAt = verifiedAtMs,
            fixAccuracyMeters = fix.accuracyMeters?.toDouble(),
            toleranceMetersUsed = toleranceMeters
        )

        // # 1) 无条件要求 mock：真实 GPS fix 无论落在哪都拒绝
        if (!fix.isMock) {
            return LocationGateResult.Rejected(FailureReason.LOCATION_NOT_MOCKED, audit(null))
        }

        // # 2) 新鲜度：锚点模式决定陈旧与 accuracy 要求
        when (anchor) {
            is GateAnchor.ActivationAnchor ->
                // # fix 早于本次激活观察点 = 上一状态的陈旧证据（≥ 语义通过）
                if (fix.elapsedRealtimeNanos < anchor.anchorNanos) {
                    return LocationGateResult.Rejected(FailureReason.LOCATION_STALE_FIX, audit(null))
                }
            GateAnchor.FreshFix -> {
                // # 以纳秒比较预算，避免毫秒截断吞掉边界外的亚毫秒
                if (nowNanos - fix.elapsedRealtimeNanos > FRESH_FIX_BUDGET_MS * 1_000_000L) {
                    return LocationGateResult.Rejected(FailureReason.LOCATION_STALE_FIX, audit(null))
                }
                // # FreshFix 模式要求 hasAccuracy()
                if (fix.accuracyMeters == null) {
                    return LocationGateResult.Rejected(FailureReason.LOCATION_NO_FIX, audit(null))
                }
            }
        }

        // # 3) 容差检查：distanceTo(target) ≤ tolerance
        val distance = distanceMeters(fix.latitude, fix.longitude, targetLat, targetLng)
        if (distance > toleranceMeters) {
            return LocationGateResult.Rejected(
                FailureReason.LOCATION_MISMATCH, audit(distance.toDouble())
            )
        }

        // # 4) 通过
        return LocationGateResult.Verified(audit(distance.toDouble()))
    }
}

/**
 * Gate entry point: permission preflight + fresh-fix sampling + evaluation.
 * Permission failures reject WITHOUT touching the sampler.
 * # 闸门入口：权限预检 + 新鲜 fix 采样 + 评估。权限失败绝不调用 sampler
 */
class LocationGate(
    private val permissionChecker: LocationPermissionChecker,
    private val sampler: LocationFixSampler,
    private val nowNanos: () -> Long,
    private val nowMs: () -> Long,
    private val distanceMeters: (Double, Double, Double, Double) -> Float
) {
    /** # 启动前预检（pre-start 永久失败拦截用） */
    fun preflight(): LocationPermissionState = permissionChecker.current()

    /**
     * Verifies the device's current location against the target coordinate.
     * # 验证设备当前位置是否为目标坐标
     */
    suspend fun verify(
        targetLat: Double,
        targetLng: Double,
        anchor: GateAnchor,
        toleranceMeters: Double
    ): LocationGateResult {
        when (permissionChecker.current()) {
            LocationPermissionState.DENIED ->
                return LocationGateResult.Rejected(FailureReason.LOCATION_PERMISSION_DENIED, null)
            LocationPermissionState.COARSE_ONLY ->
                return LocationGateResult.Rejected(FailureReason.LOCATION_APPROXIMATE_ONLY, null)
            LocationPermissionState.FINE -> {}
        }
        return when (val s = sampler.sampleFix()) {
            SampleResult.Timeout ->
                LocationGateResult.Rejected(FailureReason.LOCATION_VERIFY_TIMEOUT, null)
            SampleResult.NoFix ->
                LocationGateResult.Rejected(FailureReason.LOCATION_NO_FIX, null)
            is SampleResult.Fix -> LocationGateLogic.evaluateFix(
                fix = s.fix,
                targetLat = targetLat,
                targetLng = targetLng,
                toleranceMeters = toleranceMeters,
                anchor = anchor,
                nowNanos = nowNanos(),
                verifiedAtMs = nowMs(),
                distanceMeters = distanceMeters
            )
        }
    }
}
