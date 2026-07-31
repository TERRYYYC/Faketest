package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake GPS fail-closed activation tests (AC-B4, INV-10).
 * # Fake GPS 失败即停激活测试：只有 "Stop Fake GPS" 按钮出现才算激活成功
 */
class FakeGpsActivationTest {

    private fun node(text: String? = null, clickable: Boolean = false, children: List<ScreenNode> = emptyList()) =
        ScreenNode(text, null, null, clickable, enabled = true, children = children)

    @Test
    fun `stop button present confirms activation`() {
        // # "Stop Fake GPS" 按钮出现 = 伪造已激活
        val nodes = listOf(
            node(text = "Fake GPS"),
            node(text = "Stop Fake GPS", clickable = true)
        )
        assertTrue(isFakeGpsActivationConfirmed(nodes))
        assertEquals(GpsOutcome.Active, verifyFakeGpsActivation(nodes))
    }

    @Test
    fun `start button still showing means activation unproven and fails closed`() {
        // # 仍显示 "Start Fake GPS" → 激活未被证实 → 类型化失败，绝不继续
        val nodes = listOf(
            node(text = "Fake GPS"),
            node(text = "Start Fake GPS", clickable = true)
        )
        val outcome = verifyFakeGpsActivation(nodes)
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }

    @Test
    fun `empty screen fails closed`() {
        val outcome = verifyFakeGpsActivation(emptyList())
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }
}
