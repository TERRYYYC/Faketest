package com.example.cellrebelauto.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android production seam for the F002 location gate (Task 4): permission
 * preflight via ContextCompat, fresh-fix sampling via
 * LocationManagerCompat.getCurrentLocation, Android-15-correct mock detection
 * (AC-F2-2). All decision logic stays in the pure LocationGate/LocationGateLogic.
 * # 位置闸门的 Android 生产接缝（Task 4）：权限预检走 ContextCompat，
 * # 新鲜 fix 采样走 LocationManagerCompat.getCurrentLocation，
 * # mock 检测符合 Android 15 契约。全部决策逻辑留在纯 LocationGate 中
 */

/**
 * Runtime permission preflight: FINE granted → FINE; only COARSE →
 * COARSE_ONLY; otherwise DENIED.
 * # 运行时权限预检：FINE 已授 → FINE；仅 COARSE → COARSE_ONLY；否则 DENIED
 */
class AndroidPermissionChecker(
    private val context: Context
) : LocationPermissionChecker {
    override fun current(): LocationPermissionState {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) return LocationPermissionState.FINE
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return if (coarse) LocationPermissionState.COARSE_ONLY else LocationPermissionState.DENIED
    }
}

/**
 * Fresh-fix sampler: single current-location request with an explicit
 * timeout. Timeout → SampleResult.Timeout; null callback → SampleResult.NoFix.
 * # 新鲜 fix 采样器：单次 current-location 请求 + 显式超时；
 * # 超时 → Timeout；回调 null → NoFix
 */
class AndroidLocationFixSampler(
    private val context: Context,
    private val timeoutMs: Long = SAMPLE_TIMEOUT_MS
) : LocationFixSampler {

    companion object {
        // # 采样超时（自决，显式常量）
        const val SAMPLE_TIMEOUT_MS = 15_000L
    }

    override suspend fun sampleFix(): SampleResult {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // # SDK≥31 用 FUSED_PROVIDER，否则 GPS_PROVIDER
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            LocationManager.FUSED_PROVIDER
        else
            LocationManager.GPS_PROVIDER

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<SampleResult> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    cont.resume(
                        if (location == null) SampleResult.NoFix
                        else SampleResult.Fix(location.toObservedFix())
                    )
                }
            }
        }
        return result ?: SampleResult.Timeout
    }

    /**
     * Maps an android Location onto the pure ObservedFix. Mock detection:
     * isMock on API 31+, deprecated isFromMockProvider below (minSdk 26's only
     * option; the target device runs API 35 → isMock, satisfying AC-F2-2).
     * # android Location → 纯 ObservedFix。mock 检测：API 31+ 用 isMock，
     * # 以下用已弃用的 isFromMockProvider（目标设备 API 35 走 isMock）
     */
    private fun Location.toObservedFix(): ObservedFix {
        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
        return ObservedFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            isMock = mock,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            fixAtMs = time
        )
    }
}

/**
 * Builds the production LocationGate: monotonic nanos clock, wall clock, and
 * Location.distanceBetween bound as the distance lambda.
 * # 组装生产 LocationGate：单调纳秒时钟 + 墙钟 + distanceBetween 距离 lambda
 */
fun buildAndroidLocationGate(context: Context): LocationGate = LocationGate(
    permissionChecker = AndroidPermissionChecker(context),
    sampler = AndroidLocationFixSampler(context),
    nowNanos = { SystemClock.elapsedRealtimeNanos() },
    nowMs = { System.currentTimeMillis() },
    distanceMeters = { lat1, lng1, lat2, lng2 ->
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        out[0]
    }
)
