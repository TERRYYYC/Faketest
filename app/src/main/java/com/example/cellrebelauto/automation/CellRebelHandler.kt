package com.example.cellrebelauto.automation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles all CellRebel app interaction: launch → navigate → start test → wait → collect scores.
 * Uses coroutine-based polling instead of event-driven state machine.
 *
 * # CellRebel 应用交互处理器：
 * # 启动 → 导航到测试页面 → 开始测试 → 等待完成 → 采集分数
 * # 使用协程轮询代替事件驱动的状态机
 */
class CellRebelHandler(
    private val bridge: AccessibilityBridge,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val PACKAGE = "com.cellrebel.mobile"
        private const val TAG = "CellRebelHandler"
        // # 轮询间隔（毫秒）
        private const val POLL_INTERVAL = 1500L
        // # 等待应用启动的超时时间（含 MIUI 弹窗自动放行的时间）
        private const val APP_LAUNCH_TIMEOUT = 20_000L
        // # MIUI SecurityCenter 包名
        private const val MIUI_SECURITY_PKG = "com.miui.securitycenter"
        // # 导航到测试页面的超时时间
        private const val NAVIGATION_TIMEOUT = 20_000L
        // # 点击 Start 后固定等待时间（30 秒）
        private const val TEST_WAIT_MS = 30_000L
        // # 采集分数的超时时间
        private const val COLLECT_TIMEOUT = 20_000L
    }

    /**
     * Result of a test run.
     * # 测试运行结果
     */
    data class TestScores(
        val webBrowsingScore: Double,
        val videoStreamingScore: Double,
        val webBrowsingLabel: String = "",
        val videoStreamingLabel: String = ""
    )

    /**
     * Runs a complete CellRebel test cycle.
     * Returns test scores, or null if collection failed.
     *
     * # 运行一次完整的 CellRebel 测试循环
     * # 返回分数，采集失败返回 null
     *
     * # [2026-04-02] 简化流程：移除了完成检测（waitForTestCompletion），
     * # 改为点击 Start 后固定等待 TEST_WAIT_MS（30s），因为：
     * # - CellRebel 的 Start 按钮在测试期间不会消失，导致检测逻辑死循环
     * # - 一次测试大约 30s 完成，固定等待更可靠
     */
    suspend fun runTest(collectDelayMs: Long): TestScores? {
        // # 第 1 步：启动应用并等待前台
        log("Launching CellRebel...")
        launchAndWaitForForeground()

        // # 第 2 步：导航到测试页面
        log("Navigating to test screen...")
        navigateToTestScreen()

        // # 第 3 步：点击 Start 按钮
        log("Starting test...")
        clickStartButton()

        // # 第 4 步：固定等待 30s，测试完成
        log("Waiting ${TEST_WAIT_MS / 1000}s for test to finish...")
        delay(TEST_WAIT_MS)

        // # 第 5 步：采集分数
        log("Collecting scores...")
        return collectScores()
    }

    // ---- Step implementations ----

    /**
     * Launches CellRebel and polls until it's in the foreground.
     * # 启动 CellRebel 并轮询直到它出现在前台
     *
     * # [2026-04-02] MIUI 会静默拦截从 AccessibilityService 发起的 startActivity，
     * # 当前台是第三方 app 时连 launchSelf() 都被封杀。
     * # 解决方案：通过最近任务（GLOBAL_ACTION_RECENTS）切换，
     * # 这走系统 UI 层面，MIUI 不会拦截。
     */
    private suspend fun launchAndWaitForForeground() {
        if (bridge.getCurrentPackage() == PACKAGE) {
            delay(1000)
            return
        }

        val selfPkg = bridge.getServicePackageName()
        val currentPkg = bridge.getCurrentPackage()

        // # 第三方 app 在前台时，startActivity 被 MIUI 封杀，走最近任务切换
        if (currentPkg != null && currentPkg != selfPkg && currentPkg != PACKAGE) {
            log("[MIUI] foreground=$currentPkg, switching via Recent Apps...")
            val switched = switchViaRecents()
            if (switched && bridge.getCurrentPackage() == PACKAGE) {
                log("CellRebel is foreground (via recents)")
                delay(1000)
                return
            }
        }

        // # 回退：直接 startActivity（当我们自己在前台时能工作）
        bridge.launchApp(PACKAGE)
        log("Launch CellRebel intent sent (direct)")

        // # 等待 CellRebel 出现在前台
        val launched = withTimeoutOrNull(APP_LAUNCH_TIMEOUT) {
            while (true) {
                delay(1500)
                val pkg = bridge.getCurrentPackage()
                if (pkg == PACKAGE) {
                    log("CellRebel is foreground")
                    return@withTimeoutOrNull true
                }
                if (pkg == MIUI_SECURITY_PKG) {
                    log("[MIUI] Confirmation dialog showing, waiting for auto-dismiss...")
                    continue
                }
                log("[DIAG] foreground=$pkg, retrying via recents...")
                switchViaRecents()
            }
        }

        if (launched != true) {
            error("Failed to launch CellRebel after ${APP_LAUNCH_TIMEOUT / 1000}s (foreground=${bridge.getCurrentPackage()})")
        }
        delay(1000)
    }

    /**
     * Opens Recent Apps, finds CellRebel's card, and taps it.
     * Returns true if the card was found and tapped.
     *
     * # 打开最近任务 → 找到 CellRebel 卡片 → 点击
     * # 返回 true 表示找到并点击了卡片
     */
    private suspend fun switchViaRecents(): Boolean {
        bridge.openRecents()
        delay(1500) // # 等待最近任务动画完成

        for (attempt in 1..3) {
            val root = bridge.getRootNode()
            if (root == null) {
                delay(500)
                continue
            }

            val card = findCellRebelInRecents(root)
            if (card != null) {
                log("Found CellRebel in recents, tapping...")
                bridge.clickNode(card)
                delay(300)
                // # dispatchTap 兜底
                val (cx, cy) = bridge.getNodeCenter(card)
                bridge.dispatchTap(cx, cy)
                delay(1500) // # 等待切换动画
                return true
            }

            // # 第一次找不到时输出诊断信息
            if (attempt == 1) {
                val allNodes = NodeFinder.flatten(root)
                val texts = allNodes.mapNotNull { it.text?.toString() }
                    .take(20).joinToString(" | ")
                log("[DIAG] Recents texts: [$texts]")
                val descs = allNodes.mapNotNull { it.contentDescription?.toString() }
                    .take(20).joinToString(" | ")
                log("[DIAG] Recents descriptions: [$descs]")
            }
            delay(500)
        }

        log("CellRebel not found in recents")
        bridge.goBack() // # 关闭最近任务
        delay(500)
        return false
    }

    /**
     * Searches for CellRebel's card in the recents accessibility tree.
     * # 在最近任务的无障碍树中查找 CellRebel 的卡片
     */
    private fun findCellRebelInRecents(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // # 按 app 名称搜索（text 和 contentDescription 都试）
        return NodeFinder.findByText(root, "CellRebel")
            ?: NodeFinder.findByText(root, "Cell Rebel")
            ?: NodeFinder.findByContentDescription(root, "CellRebel")
            ?: NodeFinder.findByContentDescription(root, "Cell Rebel")
    }

    /**
     * Navigates to the test screen. If already there, does nothing.
     * Otherwise opens the menu and clicks "Connection Test".
     *
     * # 导航到测试页面。如果已经在测试页面则跳过，
     * # 否则打开菜单点击 "Connection Test"
     */
    private suspend fun navigateToTestScreen() {
        withTimeout(NAVIGATION_TIMEOUT) {
            while (true) {
                val root = bridge.getRootNode() ?: run { delay(POLL_INTERVAL); continue }

                // # 检查是否已在测试页面（有 Start 按钮 + 有分数标签）
                if (isTestScreen(root)) {
                    log("Already on test screen")
                    return@withTimeout
                }

                // # 尝试通过菜单导航
                val menuItem = NodeFinder.findByText(root, "Connection Test")
                if (menuItem != null) {
                    log("Found 'Connection Test' menu item, clicking...")
                    bridge.clickNode(menuItem)
                    delay(2000)
                    continue
                }

                // # 尝试打开菜单（汉堡菜单或导航按钮）
                val menuBtn = NodeFinder.findByContentDescription(root, "Menu")
                    ?: NodeFinder.findByContentDescription(root, "Navigate up")
                    ?: NodeFinder.findByContentDescription(root, "Open navigation")
                if (menuBtn != null) {
                    log("Opening menu...")
                    bridge.clickNode(menuBtn)
                    delay(1500)
                    continue
                }

                delay(POLL_INTERVAL)
            }
        }
    }

    /**
     * Finds and clicks the Start button.
     * # 查找并点击 Start 按钮
     *
     * # [2026-04-02] 简化点击策略：ACTION_CLICK + dispatchTap 双保险，不滚动不重试。
     * # - 不能滚动页面：CellRebel 页面不可滚动，滚动手势反而干扰按钮响应
     * # - Start 按钮在屏幕底部(y≈2408, 屏幕高2670)，坐标在屏幕范围内可直接点击
     * # - ACTION_CLICK 可触发测试，dispatchTap 作为兜底
     */
    private suspend fun clickStartButton() {
        val root = bridge.getRootNode() ?: run {
            log("ERROR: no root node")
            return
        }
        val startBtn = findStartButton(root)
        val startNode = startBtn ?: NodeFinder.findByText(root, "Start")
        if (startNode == null) {
            log("ERROR: Start button not found")
            return
        }

        val (cx, cy) = bridge.getNodeCenter(startNode)
        log("Start button at ($cx, $cy)")

        // # 方式1：先用 ACTION_CLICK
        bridge.clickNode(startNode)
        log("ACTION_CLICK dispatched")
        delay(1000)

        // # 方式2：再用坐标点击兜底
        bridge.dispatchTap(cx, cy)
        log("dispatchTap($cx, $cy) dispatched")
        delay(500)
    }

    /**
     * Collects web browsing and video streaming scores from the screen.
     * # 从屏幕上采集网页浏览和视频流分数
     */
    private suspend fun collectScores(): TestScores? {
        var attempt = 0
        return withTimeoutOrNull(COLLECT_TIMEOUT) {
            while (true) {
                val root = bridge.getRootNode()
                if (root != null) {
                    val scores = extractScores(root)
                    if (scores != null) {
                        log("Scores: Web=${scores.webBrowsingLabel}(${scores.webBrowsingScore}), Video=${scores.videoStreamingLabel}(${scores.videoStreamingScore})")
                        return@withTimeoutOrNull scores
                    }
                }
                attempt++
                delay(POLL_INTERVAL)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    // ---- Detection helpers ----

    /**
     * Checks if we're on the CellRebel test screen.
     * # 检查是否在测试页面（需要同时有 Start 按钮和分数标签）
     */
    private fun isTestScreen(root: AccessibilityNodeInfo): Boolean {
        val hasStart = findStartButton(root) != null
        val hasScoreLabel = NodeFinder.containsText(root, "Web Browsing Score") ||
            NodeFinder.containsText(root, "Video Streaming Score") ||
            NodeFinder.containsText(root, "Connection Test")
        return hasStart && hasScoreLabel
    }

    /**
     * Finds the clickable Start button.
     * # 查找可点击的 Start 按钮
     */
    private fun findStartButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeFinder.findByText(root, "Start", clickable = true)
    }

    /**
     * Extracts web and video scores from the accessibility tree.
     * Searches for label nodes ("Web Browsing Score", "Video Streaming Score")
     * then looks for nearby numeric values.
     *
     * # 从无障碍树中提取分数：
     * # 找到标签节点后在附近查找数值
     */
    // # 文字评级到数值的映射
    private val ratingToScore = mapOf(
        "excellent" to 9.0,
        "good" to 7.0,
        "fair" to 5.0,
        "poor" to 3.0,
        "bad" to 1.0
    )

    private fun extractScores(root: AccessibilityNodeInfo): TestScores? {
        val allNodes = NodeFinder.flatten(root)
        var webLabel: String? = null
        var videoLabel: String? = null
        var webScore: Double? = null
        var videoScore: Double? = null

        for (i in allNodes.indices) {
            val text = allNodes[i].text?.toString() ?: continue

            if (text.contains("Web Browsing Score", ignoreCase = true)) {
                val (score, label) = findNearbyScoreOrLabel(allNodes, i)
                webScore = score
                webLabel = label
            }
            if (text.contains("Video Streaming Score", ignoreCase = true)) {
                val (score, label) = findNearbyScoreOrLabel(allNodes, i)
                videoScore = score
                videoLabel = label
            }
        }

        return if (webScore != null && videoScore != null) {
            TestScores(webScore, videoScore, webLabel ?: "", videoLabel ?: "")
        } else null
    }

    private fun findNearbyScoreOrLabel(
        nodes: List<AccessibilityNodeInfo>,
        labelIndex: Int
    ): Pair<Double?, String?> {
        val start = maxOf(0, labelIndex - 5)
        val end = minOf(nodes.size - 1, labelIndex + 5)
        for (i in start..end) {
            val text = nodes[i].text?.toString() ?: continue
            // # 先尝试数字
            val numScore = text.toDoubleOrNull()
            if (numScore != null && numScore in 0.0..10.0) return Pair(numScore, text)
            // # 再尝试文字评级
            val rating = ratingToScore[text.lowercase().trim()]
            if (rating != null) return Pair(rating, text)
        }
        return Pair(null, null)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }
}
