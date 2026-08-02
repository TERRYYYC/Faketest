package com.example.cellrebelauto.automation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the location verification gate (F002 AC-F2-1/2).
 * No android.location involved — distance is a fake lambda; the Android
 * Location.distanceBetween binding is a Task 4 concern.
 * # 位置验证闸门的纯 JVM 测试：distance 用假 lambda，
 * # Android Location.distanceBetween 接线属于 Task 4
 */
class LocationGateTest {

    private val targetLat = 31.23
    private val targetLng = 121.474
    private val tolerance = LocationGateLogic.DEFAULT_TOLERANCE_METERS
    private val nowMs = 1_753_900_000_000L

    // # 假距离：固定返回值，按需替换
    private var fixedDistance = 0f
    private val distanceMeters: (Double, Double, Double, Double) -> Float =
        { _, _, _, _ -> fixedDistance }

    private var permission = LocationPermissionState.FINE
    private val permissionChecker = object : LocationPermissionChecker {
        override fun current(): LocationPermissionState = permission
    }

    // # 采样调用计数假实现：验证 permission 拒绝路径绝不触碰 sampler
    private var sampleCalls = 0
    private var sampleResult: SampleResult = SampleResult.Timeout
    private val sampler = object : LocationFixSampler {
        override suspend fun sampleFix(): SampleResult {
            sampleCalls++
            return sampleResult
        }
    }

    private fun mockFix(
        elapsedNanos: Long,
        accuracy: Float? = 3.0f,
        isMock: Boolean = true,
        lat: Double = targetLat,
        lng: Double = targetLng,
        fixAtMs: Long = nowMs - 1000L
    ) = ObservedFix(
        latitude = lat, longitude = lng, accuracyMeters = accuracy,
        isMock = isMock, elapsedRealtimeNanos = elapsedNanos, fixAtMs = fixAtMs
    )

    private fun evaluate(
        fix: ObservedFix,
        anchor: GateAnchor,
        nowNanos: Long,
        tol: Double = tolerance
    ): LocationGateResult = LocationGateLogic.evaluateFix(
        fix = fix, targetLat = targetLat, targetLng = targetLng,
        toleranceMeters = tol, anchor = anchor,
        nowNanos = nowNanos, verifiedAtMs = nowMs,
        distanceMeters = distanceMeters
    )

    @Test
    fun `mock fix within tolerance at anchor is verified with full audit`() {
        // # 1：mock fix 在容差内 + 不早于锚点 → Verified，audit 八字段回显
        fixedDistance = 42.0f
        val result = evaluate(
            fix = mockFix(elapsedNanos = 2000L),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        val verified = result as LocationGateResult.Verified
        val audit = verified.audit
        assertEquals(targetLat, audit.actualLatitude!!, 0.0001)
        assertEquals(targetLng, audit.actualLongitude!!, 0.0001)
        assertEquals(42.0, audit.locationErrorMeters!!, 0.001)
        assertEquals(true, audit.fixIsMock)
        assertEquals(nowMs - 1000L, audit.fixAt)
        assertEquals(nowMs, audit.verifiedAt)
        assertEquals(3.0, audit.fixAccuracyMeters!!, 0.001)
        assertEquals(tolerance, audit.toleranceMetersUsed, 0.0001)
    }

    @Test
    fun `real gps fix is rejected as not mocked with audit coordinates`() {
        // # 2：isMock=false → LOCATION_NOT_MOCKED（无条件要求 mock），audit 带 fix 坐标
        val result = evaluate(
            fix = mockFix(elapsedNanos = 2000L, isMock = false),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        val rejected = result as LocationGateResult.Rejected
        assertEquals(FailureReason.LOCATION_NOT_MOCKED, rejected.reason)
        val audit = rejected.audit!!
        assertEquals(targetLat, audit.actualLatitude!!, 0.0001)
        assertEquals(targetLng, audit.actualLongitude!!, 0.0001)
        assertEquals(false, audit.fixIsMock)
        assertEquals(tolerance, audit.toleranceMetersUsed, 0.0001)
        assertEquals(nowMs, audit.verifiedAt)
    }

    @Test
    fun `fix one nanosecond before activation anchor is stale`() {
        // # 3a：ActivationAnchor：fix 早于锚点 1ns → STALE_FIX
        val result = evaluate(
            fix = mockFix(elapsedNanos = 999L),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        val rejected = result as LocationGateResult.Rejected
        assertEquals(FailureReason.LOCATION_STALE_FIX, rejected.reason)
    }

    @Test
    fun `fix exactly at activation anchor passes`() {
        // # 3b：fix 等于锚点 → 通过（≥ 语义）
        val result = evaluate(
            fix = mockFix(elapsedNanos = 1000L),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        assertTrue(result is LocationGateResult.Verified)
    }

    @Test
    fun `fresh fix older than budget is stale and within budget is verified`() {
        // # 4：FreshFix：age > 10s → STALE_FIX；age ≤ 10s → Verified
        val budgetNanos = LocationGateLogic.FRESH_FIX_BUDGET_MS * 1_000_000L
        val stale = evaluate(
            fix = mockFix(elapsedNanos = 10_000L),
            anchor = GateAnchor.FreshFix,
            nowNanos = 10_000L + budgetNanos + 1L
        )
        assertEquals(
            FailureReason.LOCATION_STALE_FIX,
            (stale as LocationGateResult.Rejected).reason
        )

        val fresh = evaluate(
            fix = mockFix(elapsedNanos = 10_000L),
            anchor = GateAnchor.FreshFix,
            nowNanos = 10_000L + budgetNanos
        )
        assertTrue(fresh is LocationGateResult.Verified)
    }

    @Test
    fun `fresh fix without accuracy is no fix but anchor mode tolerates it`() {
        // # 5：FreshFix：accuracyMeters=null → NO_FIX；ActivationAnchor 模式不要求 accuracy
        val noAccuracy = evaluate(
            fix = mockFix(elapsedNanos = 10_000L, accuracy = null),
            anchor = GateAnchor.FreshFix,
            nowNanos = 11_000L
        )
        assertEquals(
            FailureReason.LOCATION_NO_FIX,
            (noAccuracy as LocationGateResult.Rejected).reason
        )

        val anchorMode = evaluate(
            fix = mockFix(elapsedNanos = 1000L, accuracy = null),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        assertTrue(anchorMode is LocationGateResult.Verified)
    }

    @Test
    fun `distance beyond tolerance is mismatch with actual coords and distance`() {
        // # 6：distance = tolerance + 0.1 → MISMATCH，audit 带 actual 坐标与距离
        fixedDistance = (tolerance + 0.1).toFloat()
        val result = evaluate(
            fix = mockFix(elapsedNanos = 2000L, lat = 31.24, lng = 121.475),
            anchor = GateAnchor.ActivationAnchor(1000L),
            nowNanos = 3000L
        )
        val rejected = result as LocationGateResult.Rejected
        assertEquals(FailureReason.LOCATION_MISMATCH, rejected.reason)
        val audit = rejected.audit!!
        assertEquals(31.24, audit.actualLatitude!!, 0.0001)
        assertEquals(121.475, audit.actualLongitude!!, 0.0001)
        assertEquals(tolerance + 0.1, audit.locationErrorMeters!!, 0.001)
        assertEquals(tolerance, audit.toleranceMetersUsed, 0.0001)
        assertEquals(nowMs, audit.verifiedAt)
    }

    @Test
    fun `sampler timeout and no fix map to typed rejections without audit`() = runTest {
        // # 7：Timeout → VERIFY_TIMEOUT；NoFix → NO_FIX，均无 audit
        val gate = LocationGate(
            permissionChecker = permissionChecker,
            sampler = sampler,
            nowNanos = { 0L },
            nowMs = { nowMs },
            distanceMeters = distanceMeters
        )
        sampleResult = SampleResult.Timeout
        val timeoutResult = gate.verify(targetLat, targetLng, GateAnchor.FreshFix, tolerance)
        assertEquals(
            FailureReason.LOCATION_VERIFY_TIMEOUT,
            (timeoutResult as LocationGateResult.Rejected).reason
        )
        assertNull(timeoutResult.audit)

        sampleResult = SampleResult.NoFix
        val noFixResult = gate.verify(targetLat, targetLng, GateAnchor.FreshFix, tolerance)
        assertEquals(
            FailureReason.LOCATION_NO_FIX,
            (noFixResult as LocationGateResult.Rejected).reason
        )
        assertNull(noFixResult.audit)
    }

    @Test
    fun `denied permission rejects without calling the sampler`() = runTest {
        // # 8：DENIED → PERMISSION_DENIED，sampler 未被调用（计数断言）
        permission = LocationPermissionState.DENIED
        sampleCalls = 0
        val gate = LocationGate(
            permissionChecker = permissionChecker,
            sampler = sampler,
            nowNanos = { 0L },
            nowMs = { nowMs },
            distanceMeters = distanceMeters
        )
        val result = gate.verify(targetLat, targetLng, GateAnchor.FreshFix, tolerance)
        assertEquals(
            FailureReason.LOCATION_PERMISSION_DENIED,
            (result as LocationGateResult.Rejected).reason
        )
        assertNull(result.audit)
        assertEquals(0, sampleCalls)
    }

    @Test
    fun `coarse only permission rejects without calling the sampler`() = runTest {
        // # 9：COARSE_ONLY → APPROXIMATE_ONLY，sampler 未被调用
        permission = LocationPermissionState.COARSE_ONLY
        sampleCalls = 0
        val gate = LocationGate(
            permissionChecker = permissionChecker,
            sampler = sampler,
            nowNanos = { 0L },
            nowMs = { nowMs },
            distanceMeters = distanceMeters
        )
        val result = gate.verify(targetLat, targetLng, GateAnchor.FreshFix, tolerance)
        assertEquals(
            FailureReason.LOCATION_APPROXIMATE_ONLY,
            (result as LocationGateResult.Rejected).reason
        )
        assertNull(result.audit)
        assertEquals(0, sampleCalls)
    }

    @Test
    fun `permanent failure classification covers exactly the two permission reasons`() {
        // # 10：7 个新 reason 中恰好 PERMISSION_DENIED / APPROXIMATE_ONLY 为永久失败
        val permanent = FailureReason.entries.filter { LocationGateLogic.isPermanentFailure(it) }
        assertEquals(
            setOf(
                FailureReason.LOCATION_PERMISSION_DENIED,
                FailureReason.LOCATION_APPROXIMATE_ONLY
            ),
            permanent.toSet()
        )
        listOf(
            FailureReason.LOCATION_NO_FIX, FailureReason.LOCATION_STALE_FIX,
            FailureReason.LOCATION_VERIFY_TIMEOUT, FailureReason.LOCATION_NOT_MOCKED,
            FailureReason.LOCATION_MISMATCH
        ).forEach { assertFalse(LocationGateLogic.isPermanentFailure(it)) }
    }
}
