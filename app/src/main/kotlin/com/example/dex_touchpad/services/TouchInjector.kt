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
 * Streams a genuine two-contact touch gesture onto the external display.
 *
 * Both pinch-zoom and two-finger scrolling are built on this. Injecting real
 * contacts rather than wheel notches means the receiving app runs its ordinary
 * touch pipeline: zoom tracks the fingers, and a scroll gets the same
 * velocity-based fling and overscroll it would from a real touchscreen. The
 * momentum is therefore the app's own — there is no inertia to simulate here,
 * only clean positions and timestamps for its VelocityTracker to read.
 *
 * Runs in the Shizuku user service (uid 2000 / shell), which holds
 * INJECT_EVENTS — the same reason `input -d <id> keyevent` works from it. Both
 * entry points are hidden API, reached by reflection; Shizuku-hosted services
 * are not subject to the hidden-API blocklist, which the existing
 * Display.getType() call in this service already relies on.
 *
 * Every failure path is soft: the caller falls back to the wheel devices.
 */
internal class TouchInjector(
    context: Context?,
    val displayId: Int
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

    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f

    val isAvailable: Boolean
        get() = inputManager != null &&
            injectMethod != null &&
            setDisplayIdMethod != null &&
            displaySize != null

    val width: Float get() = displaySize?.x?.toFloat() ?: 0f
    val height: Float get() = displaySize?.y?.toFloat() ?: 0f

    /**
     * Places both contacts. Returns false when injection is not usable, so the
     * caller can use the wheel fallback for the whole gesture.
     */
    fun begin(px0: Float, py0: Float, px1: Float, py1: Float): Boolean {
        if (!isAvailable) {
            Log.w(
                TAG,
                "touch injection unavailable (inputManager=${inputManager != null}, " +
                    "inject=${injectMethod != null}, setDisplayId=${setDisplayIdMethod != null}, " +
                    "size=${displaySize != null})"
            )
            return false
        }
        x0 = px0; y0 = py0; x1 = px1; y1 = py1
        downTime = SystemClock.uptimeMillis()
        lastMoveTime = 0L

        // ACTION_DOWN carries the first pointer only; the second arrives as
        // ACTION_POINTER_DOWN. Injecting an invalid pointer count for the
        // action makes the framework drop the whole gesture.
        if (!send(ACTION_DOWN, count = 1, x0 = x0, y0 = y0)) return false
        return send(MotionEvent.ACTION_POINTER_DOWN, count = 2, x0 = x0, y0 = y0, x1 = x1, y1 = y1)
    }

    /** Moves both contacts. Safe to call with the same values repeatedly. */
    fun move(px0: Float, py0: Float, px1: Float, py1: Float) {
        if (!isAvailable) return
        // Touch panels deliver ~120 Hz; more binder traffic buys nothing and
        // can bunch events up enough to confuse velocity tracking.
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveTime < MIN_MOVE_INTERVAL_MS) return
        lastMoveTime = now

        x0 = px0; y0 = py0; x1 = px1; y1 = py1
        send(MotionEvent.ACTION_MOVE, count = 2, x0 = x0, y0 = y0, x1 = x1, y1 = y1)
    }

    /** Lifts both contacts. Safe to call without a matching [begin]. */
    fun end() {
        if (!isAvailable) return
        send(MotionEvent.ACTION_POINTER_UP, count = 2, x0 = x0, y0 = y0, x1 = x1, y1 = y1)
        send(ACTION_UP, count = 1, x0 = x0, y0 = y0)
        lastMoveTime = 0L
    }

    /** Keeps a contact inside the display so the app never sees off-screen touches. */
    fun clampX(v: Float): Float = v.coerceIn(0f, (width - 1f).coerceAtLeast(0f))

    fun clampY(v: Float): Float = v.coerceIn(0f, (height - 1f).coerceAtLeast(0f))

    fun minSpan(): Float = 1f

    fun maxSpan(): Float = min(width, height) * 0.9f

    /**
     * Builds and injects one frame. [count] is how many of the two pointers this
     * frame actually carries.
     */
    private fun send(
        action: Int,
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
            Log.e(TAG, "could not build touch MotionEvent", t)
            return false
        }

        return try {
            setDisplayIdMethod!!.invoke(event, displayId)
            val ok = injectMethod!!.invoke(inputManager, event, INJECT_MODE_ASYNC) as? Boolean ?: false
            if (!ok) Log.w(TAG, "injectInputEvent rejected action=$encoded")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "touch injection failed", t)
            false
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "TouchInjector"

        /** android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 0

        const val ACTION_DOWN = MotionEvent.ACTION_DOWN
        const val ACTION_UP = MotionEvent.ACTION_UP

        const val MIN_MOVE_INTERVAL_MS = 8L
    }
}
