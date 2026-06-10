package com.sbs.telecom.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccService"

        var instance: RemoteAccessibilityService? = null
            private set
    }

    private val gesturePoints = mutableListOf<GesturePoint>()
    private val thresholdDistance = 8f // pixels
    private val thresholdTime = 40L // ms

    // 제스처 큐: dispatchGesture가 비동기이므로, 이전 제스처가 완료된 후 다음 제스처를 실행합니다.
    private val gestureQueue = ConcurrentLinkedQueue<GestureDescription>()
    private val isDispatching = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ACTION_DOWN 후 ACTION_UP이 누락되는 경우를 대비한 타임아웃
    private var gestureTimeoutRunnable: Runnable? = null
    private val GESTURE_TIMEOUT_MS = 3000L // 3초 이내에 ACTION_UP이 없으면 강제 처리

    private data class GesturePoint(val x: Float, val y: Float, val time: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "onServiceConnected: Accessibility service is now active")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "onUnbind: Accessibility service unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // OS 보안 정책(Tapjacking 방지)에 의해 미디어 프로젝션 권한 확인 다이얼로그("화면 공유"/"지금 시작" 버튼)에 대한
        // 자동 클릭(performAction 및 dispatchGesture 시뮬레이션 전체)은 시스템 수준에서 전면 차단되므로,
        // 사용자 정보 보안을 위해 어떠한 가짜 클릭 행위도 발생하지 않도록 코드를 완전히 배제합니다.
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>?) {
        if (nodes != null) {
            for (node in nodes) {
                try {
                    node.recycle()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        cancelGestureTimeout()
        gestureQueue.clear()
        instance = null
    }

    /**
     * 터치 이벤트를 주입합니다. 이 메서드는 어떤 스레드에서든 호출될 수 있습니다.
     * 동기화를 위해 synchronized 블록을 사용합니다.
     */
    fun injectTouch(action: Int, xRatio: Float, yRatio: Float) {
        val (width, height) = getRealScreenSize()
        val x = xRatio * width
        val y = yRatio * height
        val currentTime = System.currentTimeMillis()

        synchronized(gesturePoints) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    // 이전에 완료되지 않은 제스처가 있으면 강제로 디스패치합니다.
                    if (gesturePoints.isNotEmpty()) {
                        Log.w(TAG, "injectTouch: ACTION_DOWN received while previous gesture pending. Force-dispatching previous gesture.")
                        val pendingXRatio = gesturePoints.last().x / width
                        val pendingYRatio = gesturePoints.last().y / height
                        dispatchBufferedGesture(pendingXRatio, pendingYRatio)
                    }
                    cancelGestureTimeout()
                    gesturePoints.clear()
                    gesturePoints.add(GesturePoint(x, y, currentTime))
                    startGestureTimeout(xRatio, yRatio)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesturePoints.isNotEmpty()) {
                        val lastPoint = gesturePoints.last()
                        val dx = x - lastPoint.x
                        val dy = y - lastPoint.y
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        val dt = currentTime - lastPoint.time
                        if (dist > thresholdDistance || dt > thresholdTime) {
                            gesturePoints.add(GesturePoint(x, y, currentTime))
                        } else {
                            // 이동 거리/시간 미달 — 포인트 무시
                        }
                    } else {
                        // ACTION_DOWN이 누락된 경우에도 MOVE를 시작점으로 취급
                        gesturePoints.add(GesturePoint(x, y, currentTime))
                        startGestureTimeout(xRatio, yRatio)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelGestureTimeout()
                    if (gesturePoints.isNotEmpty()) {
                        gesturePoints.add(GesturePoint(x, y, currentTime))
                        dispatchBufferedGesture(xRatio, yRatio)
                    } else {
                        // ACTION_DOWN이 누락된 경우 — 단일 탭으로 처리
                        gesturePoints.add(GesturePoint(x, y, currentTime))
                        dispatchBufferedGesture(xRatio, yRatio)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelGestureTimeout()
                    gesturePoints.clear()
                    Log.d(TAG, "injectTouch: ACTION_CANCEL, gesture cleared")
                }
                else -> {
                    // 기타 액션 (ACTION_POINTER_DOWN 등)은 무시
                    Log.d(TAG, "injectTouch: Unhandled action=$action, ignored")
                }
            }
        }
    }

    /**
     * ACTION_DOWN 이후 ACTION_UP이 오지 않는 경우를 대비해 타임아웃을 설정합니다.
     */
    private fun startGestureTimeout(xRatio: Float, yRatio: Float) {
        cancelGestureTimeout()
        gestureTimeoutRunnable = Runnable {
            synchronized(gesturePoints) {
                if (gesturePoints.isNotEmpty()) {
                    Log.w(TAG, "Gesture timeout — forcing dispatch of buffered gesture")
                    dispatchBufferedGesture(xRatio, yRatio)
                }
            }
        }
        mainHandler.postDelayed(gestureTimeoutRunnable!!, GESTURE_TIMEOUT_MS)
    }

    private fun cancelGestureTimeout() {
        gestureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        gestureTimeoutRunnable = null
    }

    /**
     * 버퍼링된 제스처를 분석하여 GestureDescription으로 변환하고 큐에 넣습니다.
     * 이 메서드는 synchronized(gesturePoints) 블록 안에서 호출되어야 합니다.
     */
    private fun dispatchBufferedGesture(xRatio: Float, yRatio: Float) {
        if (gesturePoints.isEmpty()) return

        val builder = GestureDescription.Builder()
        val points = gesturePoints.toList()
        gesturePoints.clear()

        // 첫 번째 점과 마지막 점 사이의 이동 거리를 계산하여 탭 여부를 더 정확히 판별
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        val totalDistance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // 탭 판단 기준 거리 (15dp 정도를 기준으로 설정)
        val density = resources.displayMetrics.density
        val tapThreshold = 15f * density

        val isTap = points.size == 1 || totalDistance < tapThreshold

        // 1. 네비게이션 바 영역(yRatio > 0.94f)의 탭인 경우, 시스템 Global Action을 직접 수행하여 100% 확실히 제어합니다.
        if (isTap && yRatio > 0.94f) {
            handleNavigationBarTap(xRatio)
            return
        }

        // 2. 일반 영역의 제스처 주입 처리
        if (isTap) {
            // 단일 탭 / 클릭 처리 (이동 거리가 작으면 탭으로 강제 변환)
            val path = Path().apply {
                moveTo(start.x, start.y)
                lineTo(start.x + 1f, start.y + 1f)
            }
            // 탭 누름 지속시간을 50ms로 조정 — 너무 길면 롱프레스로 인식될 수 있음
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            builder.addStroke(stroke)
            Log.d(TAG, "dispatchBufferedGesture: TAP at (${start.x}, ${start.y}), distance=$totalDistance")
        } else {
            // 멀티 세그먼트 드래그/스와이프 — 단일 StrokeDescription에 멀티 세그먼트 Path를 담아 처리
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            var duration = points.last().time - points.first().time
            if (duration < 250) {
                duration = 250L // 자연스러운 스와이프 페이지 넘김이 동작하도록 최소 250ms 보장
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            builder.addStroke(stroke)
            Log.d(TAG, "dispatchBufferedGesture: SWIPE/DRAG multi-pts, duration=$duration, points=${points.size}")
        }

        val gestureDesc = builder.build()
        enqueueGesture(gestureDesc)
    }

    /**
     * 네비게이션 바 탭을 처리합니다.
     * Global Action을 직접 사용하여 항상 확실하게 동작합니다.
     */
    private fun handleNavigationBarTap(xRatio: Float) {
        val isSamsung = android.os.Build.MANUFACTURER.lowercase().contains("samsung")
        var keyOrder = "recents-home-back" // 삼성 기본값
        try {
            val settingsOrder = android.provider.Settings.Secure.getString(contentResolver, "navigationbar_key_order")
            if (settingsOrder != null) {
                keyOrder = settingsOrder
            } else if (!isSamsung) {
                keyOrder = "back-home-recents" // AOSP/Pixel 기본값
            }
        } catch (e: Exception) {
            if (!isSamsung) keyOrder = "back-home-recents"
        }

        val isBackOnLeft = keyOrder.startsWith("back")

        if (xRatio >= 0.35f && xRatio <= 0.65f) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Log.d(TAG, "Nav Bar Click: HOME performed via Global Action")
        } else if (xRatio < 0.35f) {
            if (isBackOnLeft) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Nav Bar Click: BACK performed via Global Action")
            } else {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.d(TAG, "Nav Bar Click: RECENTS performed via Global Action")
            }
        } else { // xRatio > 0.65f
            if (isBackOnLeft) {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.d(TAG, "Nav Bar Click: RECENTS performed via Global Action")
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Nav Bar Click: BACK performed via Global Action")
            }
        }
    }

    /**
     * 제스처를 큐에 넣고, 큐 처리를 시작합니다.
     * dispatchGesture는 비동기이므로 이전 제스처가 완료된 후 다음을 실행합니다.
     */
    private fun enqueueGesture(gesture: GestureDescription) {
        gestureQueue.add(gesture)
        processGestureQueue()
    }

    /**
     * 큐에 있는 제스처를 순차적으로 실행합니다.
     * isDispatching 플래그를 사용하여 동시에 하나의 제스처만 실행되도록 보장합니다.
     */
    private fun processGestureQueue() {
        if (!isDispatching.compareAndSet(false, true)) {
            // 이미 제스처가 실행 중이면 콜백에서 다시 호출됨
            return
        }

        val nextGesture = gestureQueue.poll()
        if (nextGesture == null) {
            isDispatching.set(false)
            return
        }

        try {
            val success = dispatchGesture(nextGesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture dispatched successfully")
                    isDispatching.set(false)
                    // 큐에 대기 중인 다음 제스처가 있으면 실행
                    if (gestureQueue.isNotEmpty()) {
                        processGestureQueue()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture was cancelled — retrying from queue")
                    isDispatching.set(false)
                    // 취소된 경우에도 다음 제스처 처리 계속
                    if (gestureQueue.isNotEmpty()) {
                        // 약간의 지연 후 재시도하여 시스템 과부하 방지
                        mainHandler.postDelayed({
                            processGestureQueue()
                        }, 30)
                    }
                }
            }, mainHandler)
            Log.d(TAG, "dispatchGesture raw result: $success")
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture error: ${e.message}", e)
            isDispatching.set(false)
            // 에러 발생 시에도 큐 처리 계속
            if (gestureQueue.isNotEmpty()) {
                mainHandler.postDelayed({
                    processGestureQueue()
                }, 50)
            }
        }
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val displayManager = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: run {
            val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        }

        val realMetrics = android.util.DisplayMetrics()
        display.getRealMetrics(realMetrics)

        var realWidth = realMetrics.widthPixels
        var realHeight = realMetrics.heightPixels

        val rotation = display.rotation
        val isLandscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270

        if (isLandscape && realWidth < realHeight) {
            realWidth = realMetrics.heightPixels
            realHeight = realMetrics.widthPixels
        } else if (!isLandscape && realWidth > realHeight) {
            realWidth = realMetrics.heightPixels
            realHeight = realMetrics.widthPixels
        }

        return Pair(realWidth, realHeight)
    }
}
