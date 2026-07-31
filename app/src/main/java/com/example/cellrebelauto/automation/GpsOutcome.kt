package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.ScreenNode

/**
 * Typed outcome of a Fake GPS activation (AC-B4). GPS has no scores, so this
 * is a dedicated minimal type instead of reusing AttemptOutcome.
 * # Fake GPS 激活的类型化结果（AC-B4）。GPS 没有分数，
 * # 所以用专门的最小类型而不是复用 AttemptOutcome
 */
sealed interface GpsOutcome {
    /** # 激活已证实（"Stop Fake GPS" 按钮出现） */
    data object Active : GpsOutcome

    /** # 失败即停：原因类型化，绝不告警后继续 */
    data class Failed(val reason: FailureReason, val detail: String?) : GpsOutcome
}

/**
 * Pure activation check: presence of the "Stop Fake GPS" button is the
 * activation evidence.
 * # 纯激活判断："Stop Fake GPS" 按钮存在即激活证据
 */
fun isFakeGpsActivationConfirmed(nodes: List<ScreenNode>): Boolean =
    nodes.any { it.text?.contains("Stop Fake GPS", ignoreCase = true) == true }

/**
 * Fail-closed verification: confirmed → Active, otherwise typed failure.
 * # 失败即停验证：证实 → Active，否则类型化失败
 */
fun verifyFakeGpsActivation(nodes: List<ScreenNode>): GpsOutcome =
    if (isFakeGpsActivationConfirmed(nodes)) {
        GpsOutcome.Active
    } else {
        GpsOutcome.Failed(
            reason = FailureReason.FAKE_GPS_NOT_ACTIVE,
            detail = "\"Stop Fake GPS\" button never appeared after the start sequence"
        )
    }
