package com.sbs.telecom.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccService"

        var instance: RemoteAccessibilityService? = null
            private set
    }

    private val gesturePoints = mutableListOf<GesturePoint>()
    private val thresholdDistance = 8f // pixels
    private val thresholdTime = 40L // ms

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
        // 구글 플레이 정책에 따라 화면 분석을 하지 않으므로 구현을 비워둡니다.
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        instance = null
    }

    fun injectTouch(action: Int, xRatio: Float, yRatio: Float) {
        // 네비게이션 바를 포함한 실제 전체 화면 크기를 획득하여 좌표 매핑 오차를 해결합니다.
        val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            this.display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val realMetrics = android.util.DisplayMetrics()
        display?.getRealMetrics(realMetrics)
        
        val width = realMetrics.widthPixels
        val height = realMetrics.heightPixels

        val x = xRatio * width
        val y = yRatio * height
        val currentTime = System.currentTimeMillis()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gesturePoints.clear()
                gesturePoints.add(GesturePoint(x, y, currentTime))
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
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesturePoints.isNotEmpty()) {
                    gesturePoints.add(GesturePoint(x, y, currentTime))
                    dispatchBufferedGesture(xRatio, yRatio)
                } else {
                    // ACTION_DOWN이 누락된 경우 — 단일 탭으로 처리
                    gesturePoints.add(GesturePoint(x, y, currentTime))
                    dispatchBufferedGesture(xRatio, yRatio)
                }
            }
        }
    }

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
                return
            } else if (xRatio < 0.35f) {
                if (isBackOnLeft) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, "Nav Bar Click: BACK performed via Global Action")
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    Log.d(TAG, "Nav Bar Click: RECENTS performed via Global Action")
                }
                return
            } else { // xRatio > 0.65f
                if (isBackOnLeft) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    Log.d(TAG, "Nav Bar Click: RECENTS performed via Global Action")
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, "Nav Bar Click: BACK performed via Global Action")
                }
                return
            }
        }

        // 2. 일반 영역의 제스처 주입 처리
        if (isTap) {
            // 단일 탭 / 클릭 처리 (이동 거리가 작으면 탭으로 강제 변환)
            val path = Path().apply {
                moveTo(start.x, start.y)
                lineTo(start.x + 1f, start.y + 1f)
            }
            // 탭 누름 지속시간을 100ms로 소폭 증가시켜 시스템이 정확히 누르도록 조치합니다.
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            builder.addStroke(stroke)
            Log.d(TAG, "dispatchBufferedGesture: TAP at (${start.x}, ${start.y}), distance=$totalDistance")
        } else {
            // 멀티 세그먼트 드래그/스와이프 — 단일 StrokeDescription에 멀티 세그먼트 Path를 담아 처리
            // continueStroke 체이닝 방식보다 오류율이 훨씬 적고, 자연스럽게 홈 화면 페이지 전환 등이 작동합니다.
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

        try {
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture dispatched successfully")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture was cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture error: ${e.message}", e)
        }
    }
}
