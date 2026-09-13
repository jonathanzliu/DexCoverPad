package com.example.dex_touchpad.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.dex_touchpad.IMouseControl
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "TouchpadView"
private const val PREFS_NAME = "dex_touchpad_prefs"
private const val PREF_SENSITIVITY = "sensitivity"
private const val DEFAULT_SENSITIVITY = 1.5f
private const val MIN_MOVEMENT_THRESHOLD = 2.0f
private const val MAX_DELTA_PER_EVENT = 50.0f
private const val SCROLL_SENSITIVITY = 0.5f
private const val DOUBLE_TAP_TIMEOUT = 300L
private const val LONG_PRESS_TIMEOUT = 500L
private const val MOVEMENT_FLUSH_TIMEOUT = 16L

class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var mouseControlService: IMouseControl? = null

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var sensitivity: Float = prefs.getFloat(PREF_SENSITIVITY, DEFAULT_SENSITIVITY)

    private val backgroundPaint = Paint().apply {
        color = 0xFF1A1A2E.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFF4A4A8A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val touchPaint = Paint().apply {
        color = 0xFF6A6ABA.toInt()
        style = Paint.Style.FILL
        alpha = 180
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var isTouching = false
    private var isMultiTouch = false
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            performClick()
            sendClick(272)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            sendClick(273)
        }
    })

    private val flushMovement = Runnable {
        if (pendingDeltaX != 0f || pendingDeltaY != 0f) {
            try {
                mouseControlService?.moveCursor(pendingDeltaX, pendingDeltaY)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send movement", e)
            }
            pendingDeltaX = 0f
            pendingDeltaY = 0f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return if (event.pointerCount > 1) {
            handleMultiTouch(event)
        } else {
            handleSingleTouch(event)
        }
    }

    private fun handleSingleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                touchX = event.x
                touchY = event.y
                isTouching = true
                isMultiTouch = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch) return true
                val dx = (event.x - lastX) * sensitivity
                val dy = (event.y - lastY) * sensitivity
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > MIN_MOVEMENT_THRESHOLD) {
                    val clampedDx = dx.coerceIn(-MAX_DELTA_PER_EVENT, MAX_DELTA_PER_EVENT)
                    val clampedDy = dy.coerceIn(-MAX_DELTA_PER_EVENT, MAX_DELTA_PER_EVENT)
                    pendingDeltaX += clampedDx
                    pendingDeltaY += clampedDy
                    handler.removeCallbacks(flushMovement)
                    handler.postDelayed(flushMovement, MOVEMENT_FLUSH_TIMEOUT)
                    lastX = event.x
                    lastY = event.y
                    touchX = event.x
                    touchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                isMultiTouch = false
                handler.removeCallbacks(flushMovement)
                if (pendingDeltaX != 0f || pendingDeltaY != 0f) {
                    try {
                        mouseControlService?.moveCursor(pendingDeltaX, pendingDeltaY)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to flush movement", e)
                    }
                    pendingDeltaX = 0f
                    pendingDeltaY = 0f
                }
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleMultiTouch(event: MotionEvent): Boolean {
        isMultiTouch = true
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastY = event.getY(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val dy = (event.getY(0) - lastY) * SCROLL_SENSITIVITY
                    if (abs(dy) > 1f) {
                        try {
                            mouseControlService?.sendScroll(dy, 0f)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send scroll", e)
                        }
                        lastY = event.getY(0)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun sendClick(buttonCode: Int) {
        try {
            mouseControlService?.sendClick(buttonCode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send click", e)
        }
    }

    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(0.1f, 5.0f)
        prefs.edit().putFloat(PREF_SENSITIVITY, sensitivity).apply()
    }

    fun getSensitivity(): Float = sensitivity

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 16f, 16f, backgroundPaint)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        if (isTouching && !isMultiTouch) {
            canvas.drawCircle(touchX, touchY, 30f, touchPaint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
