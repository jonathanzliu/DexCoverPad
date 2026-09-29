package com.example.dex_touchpad.services

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlin.math.min

/**
 * Injects a genuine two-finger pinch as real touch events on the external display.
 *
 * This replaces the Ctrl+wheel approximation: a wheel notch is a discrete,
 * animated zoom step, which is what made pinching feel notchy. Two moving
 * contacts are an ordinary touch gesture, so every app that can pinch at all
 * handles this the same way it handles fingers on a real touchscreen.
 *
 * Runs in the Shizuku user service (uid 2000 / shell), which holds
 * INJECT_EVENTS — that is what makes `input -d <id> keyevent` work from the
 * same process. Both entry points are hidden API, reached by reflection;
 * Shizuku-hosted services are not subject to the hidden-API blocklist, which
 * the existing Display.getType() call in this service already relies on.
 *
 * Every failure path is soft: if anything is unavailable the caller falls back
 * to Ctrl+wheel zoom.
 */
internal class PinchInjector(
    context: Context?,
    private val displayId: Int
) {

    private val inputManager: Any? = context?.getSystemService(Context.INPUT_SERVICE)

    private val injectMethod = runCatching {
        Class.forName("android.hardware.input.InputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()

    private val setDisplayIdMethod = runCatching {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull()

    /** Physical size of the target display, in its own pixels. */
    private val displaySize: Point? = runCatching {
        val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display: Display? = dm?.getDisplay(displayId)
        display?.let { Point(it.mode.physicalWidth, it.mode.physicalHeight) }
    }.getOrNull()

    private var downTime = 0L
    private var lastMoveTime = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var baseSpan = 0f

    val isAvailable: Boolean
        get() = inputManager != null &&
            injectMethod != null &&
            setDisplayIdMethod != null &&
            displaySize != null

    /**
     * Places two contacts around the display centre. Returns false when
     * injection is not usable, so the caller can use the wheel fallback.
     */
    fun begin(): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "pinch injection unavailable (inputManager=${inputManager != null}, " +
                "inject=${injectMethod != null}, setDisplayId=${setDisplayIdMethod != null}, " +
                "size=${displaySize != null})")
            return false
        }
        val size = displaySize!!
        centerX = size.x / 2f
        centerY = size.y / 2f
        // A span in the same ballpark as a real two-finger pinch on a monitor.
        baseSpan = min(size.x, size.y) * BASE_SPAN_FRACTION

        downTime = SystemClock.uptimeMillis()
        lastMoveTime = 0L

        // ACTION_DOWN carries the first pointer only; the second arrives as
        // ACTION_POINTER_DOWN. Injecting an invalid pointer count for the
        // action makes the framework drop the whole gesture.
        val half = baseSpan / 2f
        if (!send(ACTION_DOWN, pointerCount = 1, count = 1, x0 = centerX - half, y0 = centerY)) {
            return false
        }
        return send(
            MotionEvent.ACTION_POINTER_DOWN, pointerCount = 2, count = 2,
            x0 = centerX - half, y0 = centerY, x1 = centerX + half, y1 = centerY
        )
    }

    /**
     * @param scale current finger distance divided by the distance at the start
     *              of the gesture. 1.0 leaves the contacts where they began.
     */
    fun update(scale: Float) {
        if (!isAvailable) return
        // Touch panels deliver ~120 Hz; no point in more binder traffic.
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveTime < MIN_MOVE_INTERVAL_MS) return
        lastMoveTime = now

        val span = (baseSpan * scale).coerceIn(minSpan(), maxSpan())
        val half = span / 2f
        send(
            MotionEvent.ACTION_MOVE, pointerCount = 2, count = 2,
            x0 = centerX - half, y0 = centerY, x1 = centerX + half, y1 = centerY
        )
    }

    /** Lifts both contacts. Safe to call even if the gesture never started. */
    fun end() {
        if (!isAvailable) return
        val half = (baseSpan / 2f).coerceAtLeast(1f)
        send(
            MotionEvent.ACTION_POINTER_UP, pointerCount = 2, count = 2,
            x0 = centerX - half, y0 = centerY, x1 = centerX + half, y1 = centerY
        )
        send(ACTION_UP, pointerCount = 1, count = 1, x0 = centerX - half, y0 = centerY)
        lastMoveTime = 0L
    }

    private fun minSpan(): Float = 1f

    private fun maxSpan(): Float = displaySize?.let { min(it.x, it.y) * 0.9f } ?: 1f

    /**
     * Builds and injects one frame. [count] is how many of the two pointers this
     * frame actually carries; [pointerCount] is the frame's action index.
     */
    private fun send(
        action: Int,
        pointerCount: Int,
        count: Int,
        x0: Float,
        y0: Float,
        x1: Float = 0f,
        y1: Float = 0f
    ): Boolean {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = x0
                y = y0
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = x1
                y = y1
                pressure = 1f
                size = 1f
            }
        )

        // POINTER_DOWN/UP must name which pointer changed.
        val encoded = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }

        val event = try {
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                encoded,
                count,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
        } catch (t: Throwable) {
            Log.e(TAG, "could not build pinch MotionEvent", t)
            return false
        }

        return try {
            setDisplayIdMethod!!.invoke(event, displayId)
            val ok = injectMethod!!.invoke(inputManager, event, INJECT_MODE_ASYNC) as? Boolean ?: false
            if (!ok) Log.w(TAG, "injectInputEvent rejected action=$encoded")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "pinch injection failed", t)
            false
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "PinchInjector"

        /** android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 0

        /** ACTION_DOWN has no pointer index to encode. */
        const val ACTION_DOWN = MotionEvent.ACTION_DOWN
        const val ACTION_UP = MotionEvent.ACTION_UP

        const val BASE_SPAN_FRACTION = 0.18f
        const val MIN_MOVE_INTERVAL_MS = 8L
    }
}
