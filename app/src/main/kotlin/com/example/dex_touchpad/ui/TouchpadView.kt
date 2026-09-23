package com.example.dex_touchpad.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.dex_touchpad.IMouseControl
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private const val TAG = "TouchpadView"
private const val PREFS_NAME = "dex_touchpad_prefs"
private const val PREF_SENSITIVITY = "sensitivity"
private const val DEFAULT_SENSITIVITY = 1.0f
private const val MAX_DELTA_PER_EVENT = 50.0f
/** Wheel notches per scrolled pixel; halved from 0.1 because it scrolled too fast. */
private const val SCROLL_SENSITIVITY = 0.05f

/** Pointer-distance change (px) per zoom step; smaller = more sensitive. */
private const val PINCH_SENSITIVITY = 0.008f

/** How far the average of two fingers must travel before it counts as a scroll. */
private const val TWO_FINGER_SLOP_PX = 10f

/** Three-finger swipe distance that triggers Recents. */
private const val THREE_SWIPE_THRESHOLD_PX = 90f

private const val MOVEMENT_FLUSH_TIMEOUT = 16L

/** After a tap, a second touch within this window starts a drag. */
private const val TAP_DRAG_WINDOW_MS = 400L

/** Hold still this long before moving and the drag engages ("press and drag"). */
private const val HOLD_TO_DRAG_MS = 300L

/** Movement needed to start a drag after an arming tap (deliberate, so tiny). */
private const val TAP_DRAG_ENGAGE_PX = 2f

private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2

/**
 * Cover-display touchpad surface.
 *
 * Single finger:
 *  - drag             → move cursor
 *  - tap              → left click
 *  - tap then drag    → drag (left button held)
 *  - hold then drag   → drag (left button held; short buzz when it grabs)
 *
 * Two fingers:
 *  - tap              → right click
 *  - drag             → scroll
 *  - pinch            → zoom (Ctrl + wheel)
 *
 * Three fingers:
 *  - tap              → Back
 *  - swipe up         → Recents
 *
 * Four fingers:
 *  - tap              → leave fullscreen
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { NONE, SCROLL, PINCH, THREE, CONSUMED }

    var mouseControlService: IMouseControl? = null

    /** Invoked on a four-finger tap (used to leave fullscreen). */
    var onFourFingerTap: (() -> Unit)? = null

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var sensitivity: Float = prefs.getFloat(PREF_SENSITIVITY, DEFAULT_SENSITIVITY)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // AMOLED-friendly: pure black surface with a faint outline.
    private val backgroundPaint = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFF1A1A1A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val touchPaint = Paint().apply {
        color = 0xFF3D7EFF.toInt()
        style = Paint.Style.FILL
        alpha = 150
    }
    private val dragPaint = Paint().apply {
        color = 0xFF3D7EFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 220
    }

    private val handler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibrator unavailable", t)
            null
        }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f

    private var isTouching = false
    private var isMultiTouch = false
    private var moved = false
    private var multiMoved = false
    private var buttonHeld = false
    private var dragArmed = false
    private var dragEngagePx = 0f
    private var maxPointers = 0
    private var lastTapUpTime = 0L
    private var mode = Mode.NONE

    // Two-finger tracking
    private var initialDistance = 0f
    private var lastDistance = 0f
    private var twoLastY = 0f

    // Three-finger tracking (average position at the start of the gesture)
    private var threeStartX = 0f
    private var threeStartY = 0f

    private val dragArmRunnable = Runnable {
        if (isTouching && !moved && !isMultiTouch && !buttonHeld) {
            dragArmed = true
            dragEngagePx = touchSlop
        }
    }

    private val flushMovement = Runnable { flushPendingMovement() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp()
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        isTouching = true
        isMultiTouch = false
        moved = false
        multiMoved = false
        buttonHeld = false
        maxPointers = 1
        mode = Mode.NONE
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        lastX = event.x
        lastY = event.y
        downX = event.x
        downY = event.y
        touchX = event.x
        touchY = event.y

        val now = System.currentTimeMillis()
        dragArmed = now - lastTapUpTime <= TAP_DRAG_WINDOW_MS
        if (dragArmed) {
            lastTapUpTime = 0L
        }
        dragEngagePx = TAP_DRAG_ENGAGE_PX

        handler.removeCallbacks(dragArmRunnable)
        handler.postDelayed(dragArmRunnable, HOLD_TO_DRAG_MS)
        invalidate()
    }

    private fun onPointerDown(event: MotionEvent) {
        maxPointers = max(maxPointers, event.pointerCount)
        isMultiTouch = true
        handler.removeCallbacks(dragArmRunnable)

        when (event.pointerCount) {
            2 -> {
                mode = Mode.NONE
                initialDistance = pointerDistance(event)
                lastDistance = initialDistance
                twoLastY = averageY(event)
            }
            3 -> {
                mode = Mode.THREE
                threeStartX = averageX(event)
                threeStartY = averageY(event)
            }
        }
        invalidate()
    }

    private fun onMove(event: MotionEvent) {
        maxPointers = max(maxPointers, event.pointerCount)
        when {
            event.pointerCount >= 4 -> {
                // No four-finger gestures beyond the tap; just note the movement
                // so a four-finger swipe does not fire the tap action.
                if (hypot(averageX(event) - threeStartX, averageY(event) - threeStartY) > touchSlop) {
                    multiMoved = true
                }
            }
            event.pointerCount == 3 -> handleThreeMove(event)
            event.pointerCount == 2 -> handleTwoMove(event)
            else -> handleOneMove(event)
        }
    }

    private fun handleThreeMove(event: MotionEvent) {
        if (mode == Mode.CONSUMED) return
        mode = Mode.THREE
        val dx = averageX(event) - threeStartX
        val dy = averageY(event) - threeStartY
        // Any real movement disqualifies the gesture from being a three-finger
        // tap (Back). A horizontal swipe still counts as movement even though it
        // has no action of its own, so it must not fall through to Back.
        if (hypot(dx, dy) > touchSlop) {
            multiMoved = true
        }
        if (dy < -THREE_SWIPE_THRESHOLD_PX) {
            Log.i(TAG, "three-finger swipe up -> Recents")
            mode = Mode.CONSUMED
            sendRecents()
        }
    }

    private fun handleTwoMove(event: MotionEvent) {
        if (mode == Mode.THREE || mode == Mode.CONSUMED) return

        val distance = pointerDistance(event)
        val ay = averageY(event)

        if (mode == Mode.NONE) {
            if (abs(distance - initialDistance) > touchSlop) {
                mode = Mode.PINCH
                lastDistance = initialDistance
            } else if (abs(ay - twoLastY) > TWO_FINGER_SLOP_PX) {
                mode = Mode.SCROLL
            }
        }

        when (mode) {
            Mode.PINCH -> {
                val delta = distance - lastDistance
                lastDistance = distance
                if (delta != 0f) {
                    multiMoved = true
                    // Fingers moving apart (delta > 0) zooms in.
                    sendZoom(delta * PINCH_SENSITIVITY)
                }
            }
            Mode.SCROLL -> {
                val dy = (ay - twoLastY) * SCROLL_SENSITIVITY
                twoLastY = ay
                if (dy != 0f) {
                    multiMoved = true
                    // Positive when the fingers move down, matching the wheel convention.
                    sendScroll(dy)
                }
            }
            else -> Unit
        }
    }

    private fun handleOneMove(event: MotionEvent) {
        // After a multi-finger gesture the remaining finger must not jump the cursor.
        if (mode != Mode.NONE) return

        val dx = event.x - lastX
        val dy = event.y - lastY
        // Distance from where the finger went down, NOT the per-event delta:
        // a slow drag produces many tiny deltas that never exceed the slop
        // individually.
        val totalDist = hypot(event.x - downX, event.y - downY)

        if (!moved) {
            val threshold = if (dragArmed) dragEngagePx else touchSlop
            if (totalDist > threshold) {
                moved = true
                if (dragArmed) {
                    sendButton(BUTTON_LEFT, true)
                    buttonHeld = true
                    buzz()
                }
            }
        }

        if (moved) {
            pendingDeltaX += (dx * sensitivity).coerceIn(-MAX_DELTA_PER_EVENT, MAX_DELTA_PER_EVENT)
            pendingDeltaY += (dy * sensitivity).coerceIn(-MAX_DELTA_PER_EVENT, MAX_DELTA_PER_EVENT)
            handler.removeCallbacks(flushMovement)
            handler.postDelayed(flushMovement, MOVEMENT_FLUSH_TIMEOUT)
            lastX = event.x
            lastY = event.y
            touchX = event.x
            touchY = event.y
            invalidate()
        }
    }

    private fun onPointerUp(event: MotionEvent) {
        // Re-anchor when a single finger remains so nothing jumps.
        if (event.pointerCount == 2) {
            val remaining = if (event.actionIndex == 0) 1 else 0
            if (remaining < event.pointerCount) {
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                downX = lastX
                downY = lastY
            }
        }
    }

    private fun onUp() {
        handler.removeCallbacks(dragArmRunnable)
        handler.removeCallbacks(flushMovement)
        flushPendingMovement()

        if (buttonHeld) {
            sendButton(BUTTON_LEFT, false)
            buttonHeld = false
        }

        when {
            maxPointers >= 4 -> if (mode != Mode.CONSUMED && !multiMoved) onFourFingerTap?.invoke()
            maxPointers == 3 -> if (mode == Mode.THREE && !multiMoved) sendBack()
            maxPointers == 2 -> if (mode == Mode.NONE && !multiMoved) {
                performClick()
                sendClick(BUTTON_RIGHT)
            }
            maxPointers == 1 -> if (!moved) {
                performClick()
                sendClick(BUTTON_LEFT)
                lastTapUpTime = System.currentTimeMillis()
            }
        }

        resetGesture()
    }

    private fun onCancel() {
        handler.removeCallbacks(dragArmRunnable)
        handler.removeCallbacks(flushMovement)
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        if (buttonHeld) {
            sendButton(BUTTON_LEFT, false)
            buttonHeld = false
        }
        resetGesture()
    }

    private fun resetGesture() {
        isTouching = false
        isMultiTouch = false
        moved = false
        multiMoved = false
        mode = Mode.NONE
        maxPointers = 0
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        invalidate()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return if (event.pointerCount == 0) 0f else sum / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return if (event.pointerCount == 0) 0f else sum / event.pointerCount
    }

    private fun flushPendingMovement() {
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

    /** Short, soft tick to confirm that a drag engaged. */
    private fun buzz() {
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(15L, 60))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(15L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not vibrate", t)
        }
    }

    private fun sendClick(buttonCode: Int) {
        try {
            mouseControlService?.sendClick(buttonCode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send click", e)
        }
    }

    private fun sendButton(buttonCode: Int, pressed: Boolean) {
        try {
            mouseControlService?.sendButton(buttonCode, pressed)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send button state", e)
        }
    }

    private fun sendScroll(verticalDelta: Float) {
        try {
            mouseControlService?.sendScroll(verticalDelta, 0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send scroll", e)
        }
    }

    private fun sendZoom(amount: Float) {
        try {
            mouseControlService?.sendZoom(amount)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send zoom", e)
        }
    }

    private fun sendBack() {
        try {
            mouseControlService?.sendBack()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send back", e)
        }
    }

    private fun sendRecents() {
        try {
            mouseControlService?.sendRecents()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send recents", e)
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
            if (buttonHeld) {
                canvas.drawCircle(touchX, touchY, 34f, dragPaint)
            } else {
                canvas.drawCircle(touchX, touchY, 30f, touchPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
