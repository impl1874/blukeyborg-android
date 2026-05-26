package com.blu.blukeyborg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TouchpadView(context: Context) : View(context) {

    // Sensitivity multiplier (settable from outside)
    var sensitivity: Float = 2.0f

    // Callbacks for mouse events
    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onLeftClick: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onMiddleClick: (() -> Unit)? = null
    var onScroll: ((delta: Int) -> Unit)? = null

    // Touch state
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var isTracking: Boolean = false
    private var isTwoFinger: Boolean = false
    private var touchStartTime: Long = 0L
    private var lastScrollY: Float = 0f

    // For sending mouse button via callbacks
    fun sendLeftClick() { onLeftClick?.invoke() }
    fun sendRightClick() { onRightClick?.invoke() }
    fun sendMiddleClick() { onMiddleClick?.invoke() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                touchStartTime = System.currentTimeMillis()
                isTracking = true
                isTwoFinger = false
                lastScrollY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isTwoFinger = true
                    isTracking = false
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTracking && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y

                    // Send movement with sensitivity applied and clamped to HID range
                    val scaledDx = (dx * sensitivity).toInt().coerceIn(-127, 127)
                    val scaledDy = (dy * sensitivity).toInt().coerceIn(-127, 127)
                    if (scaledDx != 0 || scaledDy != 0) {
                        onMove?.invoke(scaledDx, scaledDy)
                    }
                } else if (isTwoFinger && event.pointerCount == 2) {
                    val currentScrollY = (event.getY(0) + event.getY(1)) / 2f
                    val scrollDelta = currentScrollY - lastScrollY
                    lastScrollY = currentScrollY

                    // Send scroll - accumulate until threshold
                    val scrollAmount = (scrollDelta * sensitivity).toInt()
                    if (abs(scrollAmount) >= 1) {
                        // Clamp to reasonable HID scroll value
                        val clampedScroll = scrollAmount.coerceIn(-127, 127)
                        onScroll?.invoke(clampedScroll)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isTwoFinger) {
                    // Two-finger tap = right click
                    val duration = System.currentTimeMillis() - touchStartTime
                    if (duration < 300) {
                        onRightClick?.invoke()
                    }
                } else if (isTracking) {
                    val duration = System.currentTimeMillis() - touchStartTime
                    val distance = abs(event.x - startX) + abs(event.y - startY)

                    // Short tap with minimal movement = left click
                    if (duration < 300 && distance < dp(15)) {
                        onLeftClick?.invoke()
                    }
                }
                isTracking = false
                isTwoFinger = false
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isTwoFinger = false
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isTracking = false
                isTwoFinger = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}