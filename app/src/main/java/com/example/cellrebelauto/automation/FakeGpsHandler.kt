package com.example.cellrebelauto.automation

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles Fake GPS app interaction using map-based positioning.
 *
 * Workflow:
 *   1. Launch Fake GPS app
 *   2. Stop existing GPS spoofing if active
 *   3. Enter coordinates in search bar to navigate map
 *   4. Wait for map to load at target location
 *   5. Tap map center to confirm pin placement
 *   6. Click "Start Fake GPS"
 *
 * # Fake GPS 应用交互处理器（基于地图定位）
 * # 工作流程：
 * #   1. 启动 Fake GPS 应用
 * #   2. 停止已有的 GPS 伪造
 * #   3. 在搜索框输入坐标，地图自动导航
 * #   4. 等待地图加载到目标位置
 * #   5. 点击地图中心确认标记位置
 * #   6. 点击 "Start Fake GPS"
 */
class FakeGpsHandler(
    private val bridge: AccessibilityBridge,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val PACKAGE = "com.hopefactory2021.fakegpslocation"
        private const val TAG = "FakeGpsHandler"
        private const val POLL_INTERVAL = 1500L
        private const val APP_LAUNCH_TIMEOUT = 15_000L
        private const val ACTION_TIMEOUT = 15_000L
        // # 搜索后等待地图加载的时间
        private const val MAP_LOAD_DELAY = 3000L
    }

    /**
     * Sets a fake GPS location using the map-based workflow.
     * # 使用地图定位方式设置伪造 GPS 位置
     *
     * @param lat Target latitude / 目标纬度
     * @param lng Target longitude / 目标经度
     */
    suspend fun setLocation(lat: Double, lng: Double) {
        // # 第 1 步：启动应用
        log("Launching Fake GPS...")
        launchAndWaitForForeground()

        // # 第 2 步：检查并停止已有 GPS 伪造
        log("Checking for active GPS spoofing...")
        stopExistingGpsIfRunning()

        // # 第 3 步：通过搜索框输入坐标，将地图导航到目标位置
        log("Searching location: $lat, $lng")
        searchCoordinates(lat, lng)

        // # 第 4 步：等待地图加载
        log("Waiting for map to load...")
        delay(MAP_LOAD_DELAY)

        // # 第 5 步：点击地图中心放置标记
        log("Tapping map center to place pin...")
        tapMapCenter()

        // # 第 6 步：等待短暂延迟后点击 Start
        delay(1000)

        // # 第 7 步：点击 "Start Fake GPS"
        log("Starting Fake GPS...")
        clickStartFakeGps()

        log("Fake GPS location set to ($lat, $lng)")
    }

    // ---- Step implementations ----

    /**
     * Launches Fake GPS and waits for it to be in the foreground.
     * # 启动 Fake GPS 并等待前台显示
     */
    private suspend fun launchAndWaitForForeground() {
        bridge.launchApp(PACKAGE)
        withTimeout(APP_LAUNCH_TIMEOUT) {
            while (true) {
                if (bridge.getCurrentPackage() == PACKAGE) return@withTimeout
                delay(POLL_INTERVAL)
            }
        }
        delay(1500) // # 等待 UI 完全加载
    }

    /**
     * If "Stop Fake GPS" button is visible, click it to stop the current spoofing.
     * # 如果看到 "Stop Fake GPS" 按钮，点击停止当前伪造
     */
    private suspend fun stopExistingGpsIfRunning() {
        val root = bridge.getRootNode() ?: return
        val stopBtn = findStopButton(root)
        if (stopBtn != null) {
            log("Found active GPS, stopping...")
            bridge.clickNode(stopBtn)
            delay(2000) // # 等待停止完成

            // # 验证已停止（Start 按钮应该出现）
            withTimeoutOrNull(ACTION_TIMEOUT) {
                while (true) {
                    val newRoot = bridge.getRootNode() ?: run { delay(POLL_INTERVAL); continue }
                    if (findStartButton(newRoot) != null) return@withTimeoutOrNull
                    delay(POLL_INTERVAL)
                }
            }
            log("GPS spoofing stopped")
        } else {
            log("No active GPS spoofing found")
        }
    }

    /**
     * Enters coordinates in the search field to navigate the map.
     * Uses the format "latitude,longitude" which most map search fields accept.
     *
     * # 在搜索框中输入坐标，格式 "纬度,经度"
     * # 大多数地图搜索框都能识别这种格式
     */
    private suspend fun searchCoordinates(lat: Double, lng: Double) {
        withTimeout(ACTION_TIMEOUT) {
            while (true) {
                val root = bridge.getRootNode() ?: run { delay(POLL_INTERVAL); continue }

                // # 查找搜索框：先找 EditText，再找带有搜索标签的字段
                val searchField = findSearchField(root)
                if (searchField != null) {
                    // # 点击搜索框使其获得焦点
                    bridge.clickNode(searchField)
                    delay(800)

                    // # 输入坐标文本
                    val coordText = "$lat,$lng"
                    bridge.setText(searchField, coordText)
                    delay(500)

                    // # 发送回车或等待自动搜索
                    bridge.pressEnter(searchField)
                    delay(1500)

                    // # 检查是否有搜索结果出现，如果有则点击第一个
                    selectFirstSearchResultIfAny()

                    return@withTimeout
                }

                // # 如果没有搜索框可见，尝试点击搜索图标
                val searchIcon = NodeFinder.findByContentDescription(root, "Search")
                    ?: NodeFinder.findClickable(root, "Search")
                if (searchIcon != null) {
                    bridge.clickNode(searchIcon)
                    delay(1000)
                    continue
                }

                delay(POLL_INTERVAL)
            }
        }
    }

    /**
     * If search results appear, selects the first one.
     * # 如果出现搜索结果，选择第一个
     */
    private suspend fun selectFirstSearchResultIfAny() {
        delay(2000) // # 等待搜索结果加载
        val root = bridge.getRootNode() ?: return
        val result = findFirstSearchResult(root)
        if (result != null) {
            log("Selecting search result...")
            bridge.clickNode(result)
            delay(1000)
        }
    }

    /**
     * Taps the center of the map view to place/confirm the location pin.
     * Finds the map view bounds and taps the center.
     *
     * # 点击地图视图的中心来放置/确认位置标记
     * # 先找到地图视图的边界，然后点击中心
     */
    private suspend fun tapMapCenter() {
        val root = bridge.getRootNode() ?: return

        // # 策略 1：查找 MapView 或 SurfaceView（Google Maps 常用组件）
        val mapView = findMapView(root)
        if (mapView != null) {
            val (cx, cy) = bridge.getNodeCenter(mapView)
            log("Tapping map center at ($cx, $cy)")
            bridge.dispatchTap(cx, cy)
            delay(500)
            return
        }

        // # 策略 2：如果找不到地图视图，点击屏幕中间偏上位置
        // # （大多数 fake GPS 应用地图在屏幕上半部分）
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val cx = bounds.exactCenterX()
        // # 地图通常在屏幕中间偏上，避开底部按钮区域
        val cy = bounds.height() * 0.4f
        log("No map view found, tapping screen center at ($cx, $cy)")
        bridge.dispatchTap(cx, cy)
        delay(500)
    }

    /**
     * Clicks the "Start Fake GPS" button.
     * # 点击 "Start Fake GPS" 按钮
     */
    private suspend fun clickStartFakeGps() {
        withTimeout(ACTION_TIMEOUT) {
            while (true) {
                val root = bridge.getRootNode() ?: run { delay(POLL_INTERVAL); continue }
                val startBtn = findStartButton(root)
                if (startBtn != null) {
                    bridge.clickNode(startBtn)
                    log("Clicked 'Start Fake GPS'")
                    delay(1000)

                    // # 验证 GPS 已启动（Stop 按钮应该出现）
                    val verifyRoot = bridge.getRootNode()
                    if (verifyRoot != null && findStopButton(verifyRoot) != null) {
                        log("Fake GPS confirmed active")
                    }
                    return@withTimeout
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    // ---- Node finding helpers ----

    /**
     * Finds the "Start Fake GPS" button.
     * # 查找 "Start Fake GPS" 按钮
     */
    private fun findStartButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeFinder.findByText(root, "Start Fake GPS")
            ?: NodeFinder.findClickable(root, "Start Fake GPS")
    }

    /**
     * Finds the "Stop Fake GPS" button.
     * # 查找 "Stop Fake GPS" 按钮
     */
    private fun findStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeFinder.findByText(root, "Stop Fake GPS")
            ?: NodeFinder.findClickable(root, "Stop Fake GPS")
    }

    /**
     * Finds the search/coordinate input field.
     * # 查找搜索/坐标输入框
     */
    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // # 优先找 EditText
        val editText = NodeFinder.findByClassName(root, "android.widget.EditText")
        if (editText != null) return editText

        // # 回退：找带 "Search" 文本的输入框
        return NodeFinder.findByText(root, "Search")
    }

    /**
     * Finds the map view (MapView, SurfaceView, TextureView, or Fragment containers).
     * # 查找地图视图组件（MapView、SurfaceView、TextureView 等）
     */
    private fun findMapView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val mapClasses = listOf(
            "com.google.android.gms.maps.MapView",
            "com.google.android.gms.maps.SupportMapFragment",
            "android.view.SurfaceView",
            "android.view.TextureView"
        )
        for (className in mapClasses) {
            val found = NodeFinder.findByClassName(root, className)
            if (found != null) return found
        }

        // # 回退：查找占据大面积的非文本视图（可能是地图）
        val allNodes = NodeFinder.flatten(root)
        val screenBounds = Rect()
        root.getBoundsInScreen(screenBounds)
        val screenArea = screenBounds.width().toLong() * screenBounds.height().toLong()

        return allNodes.firstOrNull { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val nodeArea = bounds.width().toLong() * bounds.height().toLong()
            // # 占屏幕面积 30% 以上且无文本的视图可能是地图
            nodeArea > screenArea * 0.3 && node.text == null && node.childCount == 0
        }
    }

    /**
     * Finds the first search result (non-UI-chrome clickable element after the EditText).
     * # 查找第一个搜索结果（EditText 之后的可点击非 UI 控件元素）
     */
    private fun findFirstSearchResult(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allNodes = NodeFinder.flatten(root)
        val editTextIndex = allNodes.indexOfFirst {
            it.className?.toString() == "android.widget.EditText"
        }
        if (editTextIndex < 0) return null

        // # 排除的 UI 元素
        val excludedTexts = setOf("search", "no ads", "start fake gps", "stop fake gps")
        val excludedClasses = setOf(
            "android.widget.EditText",
            "android.widget.Button",
            "android.widget.ImageButton",
            "android.widget.ImageView"
        )

        return allNodes.drop(editTextIndex + 1).firstOrNull { node ->
            val text = node.text?.toString() ?: return@firstOrNull false
            val className = node.className?.toString() ?: ""
            node.isClickable &&
                text.length > 3 &&
                excludedTexts.none { text.contains(it, ignoreCase = true) } &&
                className !in excludedClasses
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }
}
