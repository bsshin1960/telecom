package com.sbs.telecom.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class RemoteDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // 화면 비율에 맞춘 기본 표시 영역 (줌 없이)
    private val baseRect = RectF()

    // ── 줌/패닝 상태 ──────────────────────────────────────────────
    private var scaleFactor = 1f
    private var translateX  = 0f
    private var translateY  = 0f

    private val minScale = 1f
    private val maxScale = 5f

    // 두 손가락 패닝 추적
    private var lastPanX  = 0f
    private var lastPanY  = 0f
    private var isPinching = false

    // ── 원격 터치 리스너 ──────────────────────────────────────────
    /** (action, xRatio 0~1, yRatio 0~1) → 서버로 전송 */
    var touchEventListener: ((action: Int, xRatio: Float, yRatio: Float) -> Unit)? = null

    // ── 핀치 줌 감지기 ────────────────────────────────────────────
    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)

                // 포커스 지점을 중심으로 줌
                val fx = detector.focusX
                val fy = detector.focusY
                val ratio = scaleFactor / prev
                translateX = fx - (fx - translateX) * ratio
                translateY = fy - (fy - translateY) * ratio

                clampTranslation()
                invalidate()
                return true
            }
        }
    )

    // ── Public API ───────────────────────────────────────────────
    fun updateFrame(bitmap: Bitmap) {
        val old = currentBitmap
        currentBitmap = bitmap
        postInvalidate()
        if (old != null && old != bitmap && !old.isRecycled) old.recycle()
    }

    /** 더블탭 등으로 줌 초기화 */
    fun resetZoom() {
        scaleFactor = 1f
        translateX  = 0f
        translateY  = 0f
        invalidate()
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────
    /** 현재 뷰 크기와 비트맵 비율로 baseRect 재계산 */
    private fun computeBaseRect() {
        val bitmap = currentBitmap ?: run { baseRect.setEmpty(); return }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return

        val bAspect = bw / bh
        val vAspect = vw / vh
        val dw: Float
        val dh: Float
        if (bAspect > vAspect) {
            dw = vw; dh = vw / bAspect
        } else {
            dh = vh; dw = vh * bAspect
        }
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f
        baseRect.set(ox, oy, ox + dw, oy + dh)
    }

    /** 줌 시 이미지가 화면 바깥으로 나가지 않도록 이동 제한 */
    private fun clampTranslation() {
        if (scaleFactor <= 1f) {
            translateX = 0f; translateY = 0f; return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val cx = baseRect.centerX()
        val cy = baseRect.centerY()

        val halfW = baseRect.width()  * scaleFactor / 2f
        val halfH = baseRect.height() * scaleFactor / 2f

        val leftEdge   = cx - halfW + translateX
        val rightEdge  = cx + halfW + translateX
        val topEdge    = cy - halfH + translateY
        val bottomEdge = cy + halfH + translateY

        if (leftEdge   > 0f)  translateX -= leftEdge
        if (rightEdge  < vw)  translateX += vw - rightEdge
        if (topEdge    > 0f)  translateY -= topEdge
        if (bottomEdge < vh)  translateY += vh - bottomEdge
    }

    private fun getCentroidX(e: MotionEvent): Float {
        var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount
    }
    private fun getCentroidY(e: MotionEvent): Float {
        var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount
    }

    // ── 렌더링 ───────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = currentBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            computeBaseRect()
            canvas.save()
            // 이동 → 중심 기준 스케일
            canvas.translate(translateX, translateY)
            canvas.scale(scaleFactor, scaleFactor, baseRect.centerX(), baseRect.centerY())
            canvas.drawBitmap(bitmap, null, baseRect, paint)
            canvas.restore()
        }
    }

    // ── 터치 처리 ────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 1) 핀치 감지기에 먼저 전달
        scaleGestureDetector.onTouchEvent(event)

        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
                isPinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPanX = getCentroidX(event)
                lastPanY = getCentroidY(event)
                isPinching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && scaleFactor > 1f) {
                    // 두 손가락 패닝
                    val cx = getCentroidX(event)
                    val cy = getCentroidY(event)
                    translateX += cx - lastPanX
                    translateY += cy - lastPanY
                    clampTranslation()
                    invalidate()
                    lastPanX = cx
                    lastPanY = cy
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isPinching = event.pointerCount > 2
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPinching = false
            }
        }

        // 2) 단일 손가락 & 핀치 아닐 때만 원격 터치로 전송
        val listener = touchEventListener
        if (!isPinching && event.pointerCount == 1 && listener != null) {
            computeBaseRect()
            if (!baseRect.isEmpty) {
                val cx = baseRect.centerX()
                val cy = baseRect.centerY()

                // 캔버스 변환 역산: 화면 좌표 → 원본 비트맵 좌표
                // screenX = (bitmapX - cx) * scale + cx + translateX
                // bitmapX = (screenX - translateX - cx) / scale + cx
                val adjX = (event.x - translateX - cx) / scaleFactor + cx
                val adjY = (event.y - translateY - cy) / scaleFactor + cy

                val rw = baseRect.width()
                val rh = baseRect.height()
                if (rw > 0f && rh > 0f) {
                    val xRatio = ((adjX - baseRect.left) / rw).coerceIn(0f, 1f)
                    val yRatio = ((adjY - baseRect.top)  / rh).coerceIn(0f, 1f)
                    listener(action, xRatio, yRatio)
                }
            }
        }

        return true
    }
}
