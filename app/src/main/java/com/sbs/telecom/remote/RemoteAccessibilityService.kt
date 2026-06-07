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
                    dispatchBufferedGesture()
                } else {
                    // ACTION_DOWN이 누락된 경우 — 단일 탭으로 처리
                    gesturePoints.add(GesturePoint(x, y, currentTime))
                    dispatchBufferedGesture()
                }
            }
        }
    }

    private fun dispatchBufferedGesture() {
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

        if (points.size == 1 || totalDistance < tapThreshold) {
            // 단일 탭 / 클릭 처리 (이동 거리가 작으면 탭으로 강제 변환)
            val path = Path().apply {
                moveTo(start.x, start.y)
                lineTo(start.x + 1f, start.y + 1f)
            }
            // 탭 누름 지속시간을 100ms로 소폭 증가시켜 시스템 네비게이션 버튼이 정확히 누르도록 조치합니다.
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            builder.addStroke(stroke)
            Log.d(TAG, "dispatchBufferedGesture: TAP at (${start.x}, ${start.y}), distance=$totalDistance")
        } else if (points.size == 2) {
            // 두 점짜리 단순 스와이프 — continueStroke 불필요
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                lineTo(points[1].x, points[1].y)
            }
            var duration = points[1].time - points[0].time
            if (duration <= 0) duration = 50
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            builder.addStroke(stroke)
            Log.d(TAG, "dispatchBufferedGesture: SWIPE 2pts, duration=$duration")
        } else {
            // 멀티 세그먼트 드래그/스와이프 — continueStroke 체이닝
            // 핵심 수정: addStroke는 첫 번째 세그먼트에 대해서만 호출하고,
            // 이후 세그먼트들은 continueStroke로 체이닝만 한다.
            // 마지막 세그먼트의 willContinue=false로 체인을 종료한 후 addStroke 한다.
            var currentStroke: GestureDescription.StrokeDescription? = null

            for (i in 1 until points.size) {
                val prevPoint = points[i - 1]
                val nextPoint = points[i]
                var duration = nextPoint.time - prevPoint.time
                if (duration <= 0) duration = 1 // 0 이하일 수 없으므로 최소 1ms 지정

                val path = Path().apply {
                    moveTo(prevPoint.x, prevPoint.y)
                    if (prevPoint.x == nextPoint.x && prevPoint.y == nextPoint.y) {
                        lineTo(nextPoint.x + 0.1f, nextPoint.y + 0.1f)
                    } else {
                        lineTo(nextPoint.x, nextPoint.y)
                    }
                }

                val willContinue = (i < points.size - 1)

                currentStroke = if (currentStroke == null) {
                    // 첫 번째 세그먼트
                    GestureDescription.StrokeDescription(path, 0, duration, willContinue)
                } else {
                    // 후속 세그먼트 — 이전 스트로크에 체이닝
                    currentStroke.continueStroke(path, 0, duration, willContinue)
                }
            }

            // 체이닝이 완료된 최종 스트로크를 한 번만 추가
            if (currentStroke != null) {
                builder.addStroke(currentStroke)
            }
            Log.d(TAG, "dispatchBufferedGesture: DRAG ${points.size}pts")
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
