package com.autoshorts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

class AutoSwipeService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var settingsManager: SettingsManager

    // VIDEO_COMPLETE モード用の状態管理
    private var isWatching = false
    private var videoBarState = VideoBarState.UNKNOWN
    private var noBarCheckCount = 0
    private var noBarElapsedSeconds = 0

    // ループ検知用：直前のバー進捗を記憶
    private var lastCompletionRatio = 0f
    private var reachedNearEnd = false  // 80%以上に達したことがあるか

    // スクリーンショット取得ループ
    private val screenshotRunnable = Runnable {
        takeScreenshotIfWatching()
    }

    // バーなし動画のフォールバックタイマー
    private val noBarFallbackRunnable = object : Runnable {
        override fun run() {
            if (!isWatching || videoBarState != VideoBarState.NO_BAR) return
            noBarElapsedSeconds++
            val fallback = settingsManager.noBarFallbackSeconds
            if (noBarElapsedSeconds >= fallback) {
                Log.d(TAG, "No-bar fallback fired, swiping")
                performSwipe()
                resetWatchState()
                scheduleNextScreenshot(POST_SWIPE_DELAY_MS)
            } else {
                val remaining = fallback - noBarElapsedSeconds
                sendBroadcastToOverlay(ACTION_NO_BAR_TICK, "remaining", remaining)
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PERFORM_SWIPE -> performSwipe()
                ACTION_AUTO_START    -> startWatching()
                ACTION_AUTO_STOP     -> stopWatching()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)

        val filter = IntentFilter().apply {
            addAction(ACTION_PERFORM_SWIPE)
            addAction(ACTION_AUTO_START)
            addAction(ACTION_AUTO_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
        Log.d(TAG, "AutoSwipeService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { Log.d(TAG, "AutoSwipeService interrupted") }

    override fun onDestroy() {
        super.onDestroy()
        stopWatching()
        unregisterReceiver(commandReceiver)
        screenshotExecutor.shutdown()
    }

    // ───────────── 視聴完了検知 ─────────────

    private fun startWatching() {
        isWatching = true
        resetWatchState()
        scheduleNextScreenshot(0)
        Log.d(TAG, "Started watching")
    }

    private fun stopWatching() {
        isWatching = false
        handler.removeCallbacks(screenshotRunnable)
        handler.removeCallbacks(noBarFallbackRunnable)
        Log.d(TAG, "Stopped watching")
    }

    private fun resetWatchState() {
        videoBarState = VideoBarState.UNKNOWN
        noBarCheckCount = 0
        noBarElapsedSeconds = 0
        lastCompletionRatio = 0f
        reachedNearEnd = false
        handler.removeCallbacks(noBarFallbackRunnable)
    }

    private fun scheduleNextScreenshot(delayMs: Long = SCREENSHOT_INTERVAL_MS) {
        handler.removeCallbacks(screenshotRunnable)
        if (isWatching) handler.postDelayed(screenshotRunnable, delayMs)
    }

    private fun takeScreenshotIfWatching() {
        if (!isWatching) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30未満はスクリーンショット不可 → スケジュールのみ継続
            scheduleNextScreenshot()
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // HardwareBuffer → ARGB_8888 Bitmap に変換
                    val hwBitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBitmap?.recycle()
                    if (bitmap != null) {
                        val result = analyzeProgressBar(bitmap)
                        bitmap.recycle()
                        handler.post { handleBarResult(result) }
                    } else {
                        handler.post { scheduleNextScreenshot() }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot failed: $errorCode")
                    handler.post { scheduleNextScreenshot() }
                }
            }
        )
    }

    private fun handleBarResult(result: BarAnalysisResult) {
        if (!isWatching) return
        val method = settingsManager.detectMethod
        when {
            result.barFound -> {
                // バーあり → 再生中
                if (videoBarState == VideoBarState.NO_BAR) {
                    handler.removeCallbacks(noBarFallbackRunnable)
                }
                if (videoBarState != VideoBarState.HAS_BAR) {
                    videoBarState = VideoBarState.HAS_BAR
                    noBarCheckCount = 0
                }

                val ratio = result.completionRatio

                // ── 検知方式: 閾値 ──
                if (method == SettingsManager.DETECT_THRESHOLD) {
                    val threshold = settingsManager.barCompleteThresholdPercent / 100f
                    if (ratio >= threshold) {
                        Log.d(TAG, "Threshold reached (${ratio}), swiping")
                        performSwipe()
                        resetWatchState()
                        scheduleNextScreenshot(POST_SWIPE_DELAY_MS)
                        return
                    }
                }

                // ── 検知方式: ループ検知 ──
                // 「バーが30%以上一気に後退」= ループしたと判断してスワイプ
                // ※短い動画でも400msのスクリーンショット間隔で確実に検知できる
                if (method == SettingsManager.DETECT_LOOP && lastCompletionRatio > 0f) {
                    val drop = lastCompletionRatio - ratio
                    if (drop >= LOOP_JUMP_THRESHOLD) {
                        Log.d(TAG, "Loop detected: drop ${lastCompletionRatio} → ${ratio} (${drop}), swiping")
                        performSwipe()
                        resetWatchState()
                        scheduleNextScreenshot(POST_SWIPE_DELAY_MS)
                        return
                    }
                }

                lastCompletionRatio = ratio
                val progress = (ratio * 100).toInt()
                sendBroadcastToOverlay(ACTION_BAR_PROGRESS, "progress", progress)
                scheduleNextScreenshot()
            }
            else -> {
                // バーが見つからない
                noBarCheckCount++
                if (videoBarState == VideoBarState.UNKNOWN && noBarCheckCount >= NO_BAR_CONFIRM_COUNT) {
                    videoBarState = VideoBarState.NO_BAR
                    noBarElapsedSeconds = 0
                    Log.d(TAG, "No bar confirmed, fallback timer: ${settingsManager.noBarFallbackSeconds}s")
                    handler.post(noBarFallbackRunnable)
                }
                // フォールバックタイマー中もスクリーンショット継続（バー出現を監視）
                scheduleNextScreenshot()
            }
        }
    }

    // ───────────── 赤いバー解析 ─────────────

    data class BarAnalysisResult(val barFound: Boolean, val completionRatio: Float)

    private fun analyzeProgressBar(bitmap: Bitmap): BarAnalysisResult {
        val width = bitmap.width
        val height = bitmap.height

        // 縦横で異なるスキャン範囲を使用
        // 縦画面: 80〜93%（YouTubeナビバーが93%以降にあるため除外）
        // 横画面: 80〜96%（ナビバーが小さくなるためより広めにスキャン）
        val isLandscape = width > height
        val scanStartY = (height * 0.80).toInt()
        val scanEndY   = if (isLandscape) (height * 0.96).toInt() else (height * 0.93).toInt()

        // 赤いバー（従来デザイン）→ 見つからなければ白/グレーのバー（新デザイン）
        val red = analyzeRedBar(bitmap, scanStartY, scanEndY)
        if (red.barFound) return red
        return analyzeGrayBar(bitmap, scanStartY, scanEndY)
    }

    /** 従来の赤いプログレスバーを解析 */
    private fun analyzeRedBar(bitmap: Bitmap, scanStartY: Int, scanEndY: Int): BarAnalysisResult {
        val width = bitmap.width
        val height = bitmap.height

        var bestRowY = -1
        var maxRedCount = 0

        // 最も赤いピクセルが多い行を探す（3ピクセルおきにサンプリング）
        for (y in scanStartY..scanEndY) {
            var redCount = 0
            for (x in 0 until width step 3) {
                if (isYoutubeRed(bitmap.getPixel(x, y))) redCount++
            }
            if (redCount > maxRedCount) {
                maxRedCount = redCount
                bestRowY = y
            }
        }

        // サンプリング幅の10%未満しか赤くなければバーなし
        val minThreshold = (width / 3) / 10
        if (maxRedCount < minThreshold || bestRowY < 0) {
            return BarAnalysisResult(false, 0f)
        }

        // bestRowY ±3行の範囲で右端の赤ピクセルを探す
        var rightmostRed = 0
        var leftmostRed = width
        val rowRange = (bestRowY - 3..bestRowY + 3).filter { it in 0 until height }
        for (y in rowRange) {
            for (x in width - 1 downTo 0) {
                if (isYoutubeRed(bitmap.getPixel(x, y))) {
                    if (x > rightmostRed) rightmostRed = x
                    break
                }
            }
            for (x in 0 until width) {
                if (isYoutubeRed(bitmap.getPixel(x, y))) {
                    if (x < leftmostRed) leftmostRed = x
                    break
                }
            }
        }

        // バーは幅の20%以上の広がりが必要（小さいアイコン等を除外）
        if (rightmostRed - leftmostRed < width * 0.20f) {
            return BarAnalysisResult(false, 0f)
        }

        val completionRatio = rightmostRed.toFloat() / width
        return BarAnalysisResult(true, completionRatio)
    }

    /**
     * 白/グレーのプログレスバー（新デザイン）を解析。
     * 「左から明るいグレー（再生済み）→ 右端まで濃いグレー（未再生）」が
     * 画面幅いっぱいに並ぶ行を探し、その境目を進捗率とする。
     * 動画内の白い部分との誤検知を避けるため、
     *  - 各部分が単色でムラがない
     *  - この形の行が「バーの太さ」程度だけ連続し、境目の位置が揃っている
     * 場合のみバーと判定する。
     */
    private fun analyzeGrayBar(bitmap: Bitmap, scanStartY: Int, scanEndY: Int): BarAnalysisResult {
        val width = bitmap.width
        val sampleCount = (width + GRAY_SAMPLE_STEP - 1) / GRAY_SAMPLE_STEP
        val classes = IntArray(sampleCount)
        val lums = IntArray(sampleCount)
        val maxRows = maxOf(GRAY_MIN_ROWS, (bitmap.height * GRAY_MAX_ROWS_FRACTION).toInt())

        var runLength = 0
        var runSplit = -1

        // 連続帯が途切れた時点で、太さが条件内ならバーとして確定
        fun bandResult(): BarAnalysisResult? =
            if (runLength in GRAY_MIN_ROWS..maxRows) {
                val ratio = (runSplit * GRAY_SAMPLE_STEP).toFloat() / width
                BarAnalysisResult(true, ratio.coerceIn(0f, 1f))
            } else null

        for (y in scanStartY..scanEndY) {
            for (i in 0 until sampleCount) {
                val p = bitmap.getPixel(i * GRAY_SAMPLE_STEP, y)
                classes[i] = classifyGrayPixel(p)
                lums[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
            val split = findBarSplit(classes, lums)
            if (split >= 0 && runLength > 0 &&
                kotlin.math.abs(split - runSplit) <= GRAY_SPLIT_TOLERANCE) {
                runLength++
            } else {
                bandResult()?.let { return it }
                if (split >= 0) { runLength = 1; runSplit = split } else { runLength = 0 }
            }
        }
        return bandResult() ?: BarAnalysisResult(false, 0f)
    }

    /**
     * 1行分の分類結果から「明るい部分 | 暗い部分」の境目（サンプル番号）を求める。
     * バーの形でなければ -1。オーバーレイボタン等で一部隠れても許容する。
     */
    private fun findBarSplit(classes: IntArray, lums: IntArray): Int {
        var played = 0
        var track = 0
        for (c in classes) {
            if (c == PX_PLAYED) played++ else if (c == PX_TRACK) track++
        }
        val classified = played + track
        // 行の大半がバーの色でなければバーではない
        if (classified < classes.size * GRAY_MIN_COVERAGE) return -1
        // 再生済み部分が全くない行は対象外（再生開始直後は数秒以内に出現する）
        if (played == 0 || track == 0) return -1

        // 境目 k で分けたとき「k より左の暗い画素 + k 以降の明るい画素」が最小になる k を探す
        var errors = played  // k = 0 のとき：明るい画素はすべて誤り
        var bestErrors = errors
        var bestK = 0
        for (k in classes.indices) {
            when (classes[k]) {
                PX_PLAYED -> errors--
                PX_TRACK  -> errors++
            }
            if (errors < bestErrors) {
                bestErrors = errors
                bestK = k + 1
            }
        }
        if (bestK == 0 || bestErrors > classified * GRAY_MAX_ERROR_RATIO) return -1

        // バーは左端から明るい部分が始まる：境目より左はほぼ明るい画素であること
        var leftPlayed = 0
        var leftClassified = 0
        for (k in 0 until bestK) {
            if (classes[k] == PX_PLAYED) leftPlayed++
            if (classes[k] != PX_OTHER) leftClassified++
        }
        if (leftClassified == 0 || leftPlayed < leftClassified * GRAY_LEFT_PLAYED_RATIO) return -1

        // 各部分が単色（ムラがない）こと
        if (!isUniform(classes, lums, PX_PLAYED) || !isUniform(classes, lums, PX_TRACK)) return -1
        return bestK
    }

    /** 指定クラスの画素の明るさが中央値付近に揃っているか（境目のアンチエイリアス等は数画素まで許容） */
    private fun isUniform(classes: IntArray, lums: IntArray, cls: Int): Boolean {
        val values = lums.filterIndexed { i, _ -> classes[i] == cls }.sorted()
        if (values.isEmpty()) return false
        val median = values[values.size / 2]
        val outliers = values.count { kotlin.math.abs(it - median) > GRAY_UNIFORM_TOLERANCE }
        return outliers <= maxOf(GRAY_UNIFORM_MAX_OUTLIERS, (values.size * GRAY_UNIFORM_OUTLIER_RATIO).toInt())
    }

    private fun classifyGrayPixel(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max - min > GRAY_MAX_CHROMA) return PX_OTHER  // 色付き（グレーではない）
        return when {
            min >= GRAY_PLAYED_MIN                       -> PX_PLAYED
            min >= GRAY_TRACK_MIN && max <= GRAY_TRACK_MAX -> PX_TRACK
            else                                          -> PX_OTHER
        }
    }

    /** YouTube Shortsの進捗バーの赤色を判定 */
    private fun isYoutubeRed(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r > 180 && g < 70 && b < 70
    }

    // ───────────── スワイプ実行 ─────────────

    fun performSwipe() {
        val (screenWidth, screenHeight) = getScreenSize()
        val direction        = settingsManager.swipeDirection
        val distanceFraction = settingsManager.swipeDistanceFraction
        val durationMs       = settingsManager.swipeDurationMs.toLong()

        val centerX   = screenWidth  / 2f
        val centerY   = screenHeight / 2f
        val halfDistH = screenWidth  * distanceFraction / 2f
        val halfDistV = screenHeight * distanceFraction / 2f

        val (startX, startY, endX, endY) = when (direction) {
            SettingsManager.DIRECTION_UP    -> listOf(centerX, centerY + halfDistV, centerX, centerY - halfDistV)
            SettingsManager.DIRECTION_DOWN  -> listOf(centerX, centerY - halfDistV, centerX, centerY + halfDistV)
            SettingsManager.DIRECTION_LEFT  -> listOf(centerX + halfDistH, centerY, centerX - halfDistH, centerY)
            SettingsManager.DIRECTION_RIGHT -> listOf(centerX - halfDistH, centerY, centerX + halfDistH, centerY)
            else                            -> listOf(centerX, centerY + halfDistV, centerX, centerY - halfDistV)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed [$direction]")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun sendBroadcastToOverlay(action: String, key: String, value: Int) {
        val intent = Intent(action).apply {
            setPackage(packageName)
            putExtra(key, value)
        }
        sendBroadcast(intent)
    }

    // ───────────── 定数・列挙 ─────────────

    enum class VideoBarState { UNKNOWN, HAS_BAR, NO_BAR }

    companion object {
        private const val TAG = "AutoSwipeService"

        const val ACTION_PERFORM_SWIPE = "com.autoshorts.ACTION_PERFORM_SWIPE"
        const val ACTION_AUTO_START    = "com.autoshorts.ACTION_AUTO_START"
        const val ACTION_AUTO_STOP     = "com.autoshorts.ACTION_AUTO_STOP"
        const val ACTION_BAR_PROGRESS  = "com.autoshorts.ACTION_BAR_PROGRESS"
        const val ACTION_NO_BAR_TICK   = "com.autoshorts.ACTION_NO_BAR_TICK"

        private const val SCREENSHOT_INTERVAL_MS = 400L
        private const val POST_SWIPE_DELAY_MS   = 1500L
        private const val LOOP_JUMP_THRESHOLD   = 0.30f  // 30%以上後退したらループとみなす
        private const val NO_BAR_CONFIRM_COUNT  = 4      // 約1.6秒でバーなし確定

        // 白/グレーバー検知用
        private const val PX_OTHER  = 0
        private const val PX_PLAYED = 1  // 再生済み（明るいグレー 例: 199,199,199）
        private const val PX_TRACK  = 2  // 未再生（濃いグレー 例: 68,68,68）
        private const val GRAY_SAMPLE_STEP      = 3
        private const val GRAY_MAX_CHROMA       = 25     // R/G/B の差がこれ以下ならグレー
        private const val GRAY_PLAYED_MIN       = 160
        private const val GRAY_TRACK_MIN        = 40
        private const val GRAY_TRACK_MAX        = 110
        private const val GRAY_MIN_COVERAGE     = 0.80f  // 行の80%以上がバーの色
        private const val GRAY_MAX_ERROR_RATIO  = 0.01f  // 境目の前後で順序が乱れた画素は1%まで
        private const val GRAY_LEFT_PLAYED_RATIO = 0.90f // 境目より左は90%以上が明るい画素
        private const val GRAY_UNIFORM_TOLERANCE = 12    // 中央値からの明るさのズレ許容
        private const val GRAY_UNIFORM_MAX_OUTLIERS = 3
        private const val GRAY_UNIFORM_OUTLIER_RATIO = 0.03f
        private const val GRAY_MIN_ROWS         = 3      // バーの太さとして最低3行連続
        private const val GRAY_MAX_ROWS_FRACTION = 0.008f // バーの太さは画面高さの0.8%まで
        private const val GRAY_SPLIT_TOLERANCE  = 2      // 連続行の境目のズレ許容（サンプル数）
    }
}
