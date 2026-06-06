package com.sbs.telecom.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class RemoteDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destRect = RectF()

    // 터치 이벤트 발생 시 호출될 리스너 (동작 유형, X비율, Y비율)
    var touchEventListener: ((action: Int, xRatio: Float, yRatio: Float) -> Unit)? = null

    fun updateFrame(bitmap: Bitmap) {
        val oldBitmap = currentBitmap
        currentBitmap = bitmap
        postInvalidate()
        // 이전 비트맵 재활용 (메모리 누수 방지)
        if (oldBitmap != null && oldBitmap != bitmap && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = currentBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            // 비율을 유지하면서 중앙 정렬로 그리기
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bmpWidth = bitmap.width.toFloat()
            val bmpHeight = bitmap.height.toFloat()

            val bmpAspect = bmpWidth / bmpHeight
            val viewAspect = viewWidth / viewHeight

            val drawWidth: Float
            val drawHeight: Float

            if (bmpAspect > viewAspect) {
                // 비트맵이 더 넓음 → 너비에 맞춤
                drawWidth = viewWidth
                drawHeight = viewWidth / bmpAspect
            } else {
                // 비트맵이 더 높음 → 높이에 맞춤
                drawHeight = viewHeight
                drawWidth = viewHeight * bmpAspect
            }

            val offsetX = (viewWidth - drawWidth) / 2f
            val offsetY = (viewHeight - drawHeight) / 2f

            destRect.set(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight)
            canvas.drawColor(Color.BLACK) // 레터박스 영역을 검정으로 채움
            canvas.drawBitmap(bitmap, null, destRect, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = touchEventListener
        if (listener != null) {
            val action = event.actionMasked

            // destRect 내의 좌표를 0.0~1.0 비율로 변환
            // destRect 바깥의 터치(레터박스 영역)도 0.0~1.0으로 클램핑
            val xInRect = event.x - destRect.left
            val yInRect = event.y - destRect.top
            val rectWidth = destRect.width()
            val rectHeight = destRect.height()

            if (rectWidth > 0 && rectHeight > 0) {
                val xRatio = (xInRect / rectWidth).coerceIn(0f, 1f)
                val yRatio = (yInRect / rectHeight).coerceIn(0f, 1f)
                listener(action, xRatio, yRatio)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
