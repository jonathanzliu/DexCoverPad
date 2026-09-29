package com.example.dex_touchpad.services

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import androidx.annotation.Keep
import com.example.dex_touchpad.IMouseControl
import java.io.File
import kotlin.math.floor
import kotlin.math.min

private const val TAG = "ShizukuUserService"
private const val CLICK_HOLD_MS = 50L
private const val CLICK_SETTLE_MS = 60L

/** Keep Ctrl held this long after the last pinch step (Ctrl + wheel = zoom). */
private const val CTRL_HOLD_MS = 300L

/** Contact separation at the start of an injected pinch, as a fraction of the display. */
private const val PINCH_SPAN_FRACTION = 0.18f

/** Contact separation for an injected two-finger scroll drag. */
private const val SCROLL_SPAN_FRACTION = 0.10f

/** Quiet period after the pointer stops before re-reading its position. */
private const val CURSOR_REFRESH_DELAY_MS = 250L

/** android.view.Display.TYPE_EXTERNAL (the constant itself is hidden API). */
private const val DISPLAY_TYPE_EXTERNAL = 2

/** Android key codes for the discrete actions. */
private const val KEYCODE_BACK = 4
private const val KEYCODE_APP_SWITCH = 187

/** HID keyboard usages (see the USB HID Usage Tables). */
private const val HID_KEY_LEFT_CTRL = 0xE0

/**
 * Shizuku UserService that owns the virtual UHid mouse.
 *
 * Shizuku (server side) creates this class in a dedicated process running with
 * shell UID (2000) — or root (0) when Shizuku runs as root — using the app's own
 * ClassLoader, and passes the app [android.app.Application] as the [Context].
 * That process can open `/dev/uhid`, which a normal app process may not.
 *
 * The instance is itself the `IMouseControl` binder, so the main app talks to it
 * directly; there is no separate native process and no broadcast hand-off.
 *
 * The virtual devices are created and driven by our own `libuhidmouse.so`.
 */
class ShizukuUserService : IMouseControl.Stub {

    private val context: Context?

    @Volatile
    private var isUHidCreated = false

    @Volatile
    private var isReady = false

    /** Sub-pixel carry so slow drags are not swallowed by integer rounding. */
    private var lastX = 0f
    private var lastY = 0f

    /** Latest pointer position read from the cursor layer, and which display it is on. */
    @Volatile
    private var cursorX = -1f
    @Volatile
    private var cursorY = -1f
    @Volatile
    private var cursorDisplay = -1

    /** Debounced: refreshed once the pointer has been still for a moment. */
    private val cursorRefresh = Runnable { queryCursorPosition() }

    /** Sub-notch carry so slow two-finger scrolls accumulate into wheel notches. */
    private var scrollCarry = 0f

    /** Sub-notch carry for pinch zoom steps. */
    private var zoomCarry = 0f
    private var clickTime = 0L

    @Volatile
    private var isKeyboardCreated = false

    private var ctrlHeld = false

    /** Live injected touch gesture on the external display; null between gestures. */
    private var touchInjector: TouchInjector? = null
    private var gestureActive = false

    /** Half the contact separation when a pinch started. */
    private var pinchHalfSpan = 0f

    /** Centre the injected contacts are built around, in display pixels. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** Half the contact separation for a scroll, and the running contact centre. */
    private var scrollHalfSpan = 0f
    private var scrollCenterX = 0f
    private var scrollCenterY = 0f

    private val handler = Handler(Looper.getMainLooper())

    /** Ctrl is released shortly after the pinch stops, like a real touchpad. */
    private val releaseCtrlRunnable = Runnable {
        if (ctrlHeld) {
            try {
                UhidNative.nativeKey(HID_KEY_LEFT_CTRL, false)
            } catch (_: Throwable) {
            }
            ctrlHeld = false
        }
    }

    @Keep
    constructor(context: Context) : super() {
        this.context = context
        initialize()
    }

    @Keep
    constructor() : super() {
        this.context = null
        initialize()
    }

    private fun initialize() {
        val uid = Process.myUid()
        Log.i(TAG, "Shizuku user service starting (uid=$uid, pid=${Process.myPid()})")

        if (uid != 0 && uid != 2000) {
            // Not fatal for debugging, but /dev/uhid will almost certainly be denied.
            Log.e(TAG, "Unexpected UID $uid — the UHid device usually needs shell (2000) or root (0)")
        }

        // Shizuku can leave an older instance of this same user service running
        // after a force-stop or app update. Each instance owns its own UHid device,
        // so reap the stale siblings first; we run as shell, which is allowed to.
        killStaleSiblings()

        val uhid = File("/dev/uhid")
        Log.i(
            TAG,
            "/dev/uhid exists=${uhid.exists()} readable=${uhid.canRead()} writable=${uhid.canWrite()}"
        )

        isUHidCreated = try {
            UhidNative.nativeCreate("DeX Touchpad Mouse")
        } catch (t: Throwable) {
            Log.e(TAG, "nativeCreate threw", t)
            false
        }

        if (!isUHidCreated) {
            Log.e(TAG, "Failed to create the UHid mouse device — cursor will not move")
            return
        }

        // A second virtual device, a keyboard, is used for the Ctrl modifier of
        // Ctrl+wheel (pinch zoom).
        isKeyboardCreated = try {
            UhidNative.nativeKeyboardCreate("Dex Touchpad Keyboard")
        } catch (t: Throwable) {
            Log.e(TAG, "nativeKeyboardCreate threw", t)
            false
        }

        isReady = true
        Log.i(TAG, "UHid mouse device ready (keyboard=$isKeyboardCreated)")
    }

    /**
     * Reaps any other `<pkg>:user_service` process.
     *
     * Shizuku may leave an older instance of this same service running after a
     * force-stop or an app update. Every instance owns its own `/dev/uhid` device,
     * so without this the input system accumulates "DeX Touchpad Mouse" devices.
     * We run as shell (uid 2000) and may signal our own processes, which is enough.
     */
    private fun killStaleSiblings() {
        val packageName = context?.packageName ?: return
        val myPid = Process.myPid()
        val suffix = "$packageName:user_service"
        try {
            val command = "for p in \$(pidof '$suffix'); do " +
                "if [ \"\$p\" != \"$myPid\" ]; then kill -9 \"\$p\"; fi; done; true"
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Give the kernel a moment to unregister the dead instances' UHid devices.
            Thread.sleep(200)
            Log.i(TAG, "Reaped stale '$suffix' siblings (self=$myPid)")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to reap stale siblings", t)
        }
    }

    override fun moveCursor(deltaX: Float, deltaY: Float) {
        if (!isReady) return
        try {
            // Ignore movement immediately around a click so taps do not drag.
            if (System.currentTimeMillis() - clickTime < CLICK_SETTLE_MS) return

            val fx = deltaX + lastX
            val fy = deltaY + lastY
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            lastX = fx - ix
            lastY = fy - iy

            if (ix != 0 || iy != 0) {
                UhidNative.nativeMove(ix, iy)
                // Re-read the pointer position once it settles, so a following
                // gesture can anchor on it without paying for the query itself.
                handler.removeCallbacks(cursorRefresh)
                handler.postDelayed(cursorRefresh, CURSOR_REFRESH_DELAY_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "moveCursor failed", t)
        }
    }

    override fun sendClick(buttonCode: Int) {
        if (!isReady) return
        try {
            // Movement right around a click is ignored so a tap does not nudge the cursor.
            clickTime = System.currentTimeMillis()
            setButton(buttonCode, true)
            Thread.sleep(CLICK_HOLD_MS)
            setButton(buttonCode, false)
        } catch (t: Throwable) {
            Log.e(TAG, "sendClick failed", t)
        }
    }

    override fun sendButton(buttonCode: Int, pressed: Boolean) {
        if (!isReady) return
        try {
            if (pressed) clickTime = 0L
            setButton(buttonCode, pressed)
        } catch (t: Throwable) {
            Log.e(TAG, "sendButton failed", t)
        }
    }

    override fun sendBack() {
        injectKey(KEYCODE_BACK, "Back")
    }

    override fun sendRecents() {
        injectKey(KEYCODE_APP_SWITCH, "Recents")
    }

    override fun sendZoom(amount: Float) {
        if (!isReady) return
        try {
            // The view streams fractional pinch amounts; one whole unit is one
            // wheel notch. Fingers moving apart give a positive amount and must
            // zoom in, which is Ctrl + wheel *up* (positive REL_WHEEL).
            zoomCarry += amount
            val notches = floor(zoomCarry).toInt()
            if (notches == 0) return
            zoomCarry -= notches

            if (isKeyboardCreated && !ctrlHeld) {
                UhidNative.nativeKey(HID_KEY_LEFT_CTRL, true)
                ctrlHeld = true
            }
            UhidNative.nativeScroll(notches)
            clickTime = System.currentTimeMillis()
            if (isKeyboardCreated) {
                handler.removeCallbacks(releaseCtrlRunnable)
                handler.postDelayed(releaseCtrlRunnable, CTRL_HOLD_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendZoom failed", t)
        }
    }

    /**
     * Starts a real two-finger pinch on the external display.
     *
     * Returns false when there is no external display or injection is not
     * usable, which tells the view to fall back to Ctrl+wheel zoom rather than
     * silently doing nothing.
     */
    override fun pinchBegin(): Boolean {
        if (!isReady) return false
        return try {
            val injector = newInjector("pinchBegin") ?: return false
            pinchHalfSpan = min(injector.width, injector.height) * PINCH_SPAN_FRACTION / 2f
            val cx = anchorX(injector, pinchHalfSpan)
            val cy = injector.height / 2f
            anchorX = cx
            anchorY = cy
            val started = injector.begin(
                injector.clampX(cx - pinchHalfSpan), injector.clampY(cy),
                injector.clampX(cx + pinchHalfSpan), injector.clampY(cy)
            )
            if (!started) return false
            touchInjector = injector
            gestureActive = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "pinchBegin failed", t)
            false
        }
    }

    override fun pinchUpdate(scale: Float) {
        if (!gestureActive) return
        try {
            val injector = touchInjector ?: return
            val cx = anchorX
            val cy = anchorY
            val half = (pinchHalfSpan * scale)
                .coerceIn(injector.minSpan() / 2f, injector.maxSpan() / 2f)
            injector.move(
                injector.clampX(cx - half), injector.clampY(cy),
                injector.clampX(cx + half), injector.clampY(cy)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "pinchUpdate failed", t)
        }
    }

    override fun pinchEnd() = endTouchGesture("pinch")

    /**
     * Starts a two-finger drag on the external display. The receiving app turns
     * it into a scroll and derives the fling from the motion we stream, so the
     * momentum is the platform's own — there is nothing to simulate here, only
     * clean positions and timestamps.
     */
    override fun scrollBegin(): Boolean {
        if (!isReady) return false
        return try {
            val injector = newInjector("scrollBegin") ?: return false
            scrollHalfSpan = min(injector.width, injector.height) * SCROLL_SPAN_FRACTION / 2f
            // Anchor on the pointer so a multi-pane app scrolls the pane the
            // user is pointing at; vertically centred for travel both ways.
            scrollCenterX = anchorX(injector, scrollHalfSpan)
            scrollCenterY = injector.height / 2f
            val started = injector.begin(
                injector.clampX(scrollCenterX - scrollHalfSpan), injector.clampY(scrollCenterY),
                injector.clampX(scrollCenterX + scrollHalfSpan), injector.clampY(scrollCenterY)
            )
            if (!started) return false
            touchInjector = injector
            gestureActive = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "scrollBegin failed", t)
            false
        }
    }

    override fun scrollUpdate(fracX: Float, fracY: Float) {
        if (!gestureActive) return
        try {
            val injector = touchInjector ?: return
            // Keep the contacts on the display: a touch that leaves the view
            // ends the gesture, and a clamped edge just stops the scroll.
            scrollCenterX = (scrollCenterX + fracX * injector.width)
                .coerceIn(scrollHalfSpan, (injector.width - scrollHalfSpan).coerceAtLeast(scrollHalfSpan))
            scrollCenterY = (scrollCenterY + fracY * injector.height)
                .coerceIn(0f, injector.height)
            injector.move(
                injector.clampX(scrollCenterX - scrollHalfSpan), injector.clampY(scrollCenterY),
                injector.clampX(scrollCenterX + scrollHalfSpan), injector.clampY(scrollCenterY)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "scrollUpdate failed", t)
        }
    }

    override fun scrollEnd() = endTouchGesture("scroll")

    /**
     * Anchor x for a gesture: the pointer's real position when we know it,
     * otherwise the middle of the display.
     *
     * Android exposes no cursor-position API, but the pointer is a
     * SurfaceFlinger layer whose transform carries its x/y, so we read it from
     * there (see [queryCursorPosition]) instead of guessing.
     */
    private fun anchorX(injector: TouchInjector, halfSpan: Float): Float {
        val maxX = (injector.width - halfSpan).coerceAtLeast(halfSpan)
        val x = if (cursorDisplay == injector.displayId && cursorX >= 0f) {
            cursorX
        } else {
            injector.width / 2f
        }
        return x.coerceIn(halfSpan, maxX)
    }

    /**
     * Reads the pointer position from the cursor layer in SurfaceFlinger.
     *
     * The pointer is drawn as an ordinary layer with
     * {@code composition type=CURSOR}, whose transform translation is the
     * position on its display. Running this costs about 0.2 s, so it is
     * debounced to fire shortly after the pointer stops moving rather than on
     * the movement hot path, and never during a gesture.
     */
    private fun queryCursorPosition() {
        try {
            val lines = ProcessBuilder("sh", "-c", "dumpsys SurfaceFlinger 2>/dev/null")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader()
                .readLines()

            // Walk backwards from the CURSOR line to the nearest transform
            // matrix, which precedes it within the same layer block.
            var i = lines.indexOfFirst { it.contains("composition type=CURSOR") }
            if (i < 0) return
            while (i >= 0 && !lines[i].contains("geomLayerTransform")) i--
            if (i < 0 || i + 2 >= lines.size) return

            // Matrix rows: [1 0 tx] / [0 1 ty] / [0 0 1]
            val row0 = parseFloats(lines[i + 1])
            val row1 = parseFloats(lines[i + 2])
            if (row0.size < 3 || row1.size < 3) return

            // Layer stack tells us which display the pointer is on.
            var stack = -1
            var j = i
            while (j < lines.size && !lines[j].contains("composition type=CURSOR")) {
                val idx = lines[j].indexOf("layerStack=")
                if (idx >= 0) {
                    stack = lines[j].substring(idx + "layerStack=".length)
                        .takeWhile { it.isDigit() }.toIntOrNull() ?: -1
                }
                j++
            }

            @Suppress("UNUSED_EXPRESSION")
            cursorDisplay = stack
            cursorX = row0[2]
            cursorY = row1[2]
            Log.i(TAG, "cursor at ($cursorX, $cursorY) on display $cursorDisplay")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the pointer position", t)
        }
    }

    private fun parseFloats(line: String): List<Float> =
        line.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }

    /**
     * Builds an injector for the external display, or returns null (with a log
     * saying why) so the caller can fall back to the wheel devices.
     */
    private fun newInjector(label: String): TouchInjector? {        val displayId = externalDisplayId()
        if (displayId == null) {
            Log.i(TAG, "$label: no external display, wheel fallback")
            return null
        }
        val injector = TouchInjector(context, displayId)
        if (!injector.isAvailable) {
            Log.i(TAG, "$label: injection unavailable, wheel fallback")
            return null
        }
        Log.i(TAG, "$label: injecting touch on display $displayId")
        return injector
    }

    private fun endTouchGesture(label: String) {
        if (!gestureActive) return
        gestureActive = false
        try {
            touchInjector?.end()
        } catch (t: Throwable) {
            Log.e(TAG, "$label end failed", t)
        } finally {
            touchInjector = null
        }
    }

    /**
     * Injects a discrete key on the external (DeX) display. A key event with the
     * default display id is delivered to this app on the phone instead of to
     * whatever is focused on the monitor.
     */
    private fun injectKey(keyCode: Int, label: String) {
        try {
            val displayId = externalDisplayId()
            val command = if (displayId != null) {
                "input -d $displayId keyevent $keyCode >/dev/null 2>&1"
            } else {
                "input keyevent $keyCode >/dev/null 2>&1"
            }
            Log.i(TAG, "inject $label ($command)")
            ProcessBuilder("sh", "-c", command).start()
        } catch (t: Throwable) {
            Log.e(TAG, "inject $label failed", t)
        }
    }

    /**
     * Id of the active external display (the DeX monitor), or null when there is
     * none. Hidden APIs are fine here: Shizuku user services are not subject to
     * the hidden-API restrictions.
     */
    private fun externalDisplayId(): Int? {
        val ctx = context ?: return null
        return try {
            val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return null
            val getType = Display::class.java.getMethod("getType")
            displayManager.displays.firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    (getType.invoke(display) as? Int) == DISPLAY_TYPE_EXTERNAL &&
                    display.state == Display.STATE_ON
            }?.displayId
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve the external display", t)
            null
        }
    }

    private fun setButton(buttonCode: Int, pressed: Boolean) {
        // Accept both the Linux input codes (BTN_LEFT=272, BTN_RIGHT=273,
        // BTN_MIDDLE=274) and the compact codes the app uses.
        val compact = when (buttonCode) {
            1, 272 -> 1
            2, 273 -> 2
            3, 274 -> 3
            else -> {
                Log.w(TAG, "Unknown button code $buttonCode")
                return
            }
        }
        UhidNative.nativeButton(compact, pressed)
    }

    override fun sendScroll(verticalDelta: Float, horizontalDelta: Float) {
        if (!isReady) return
        try {
            // The view streams fractional deltas; accumulate until a whole wheel
            // notch is reached so slow scrolls are not lost.
            scrollCarry += verticalDelta
            val notches = floor(scrollCarry).toInt()
            if (notches != 0) {
                scrollCarry -= notches
                clickTime = System.currentTimeMillis()
                UhidNative.nativeScroll(notches)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendScroll failed", t)
        }
    }

    override fun destroy() {
        Log.i(TAG, "destroy() called")
        isReady = false
        // Lift any injected contacts first: a stuck touch is worse than a stuck key.
        try {
            pinchEnd()
        } catch (_: Throwable) {
        }
        handler.removeCallbacks(releaseCtrlRunnable)
        try {
            if (ctrlHeld) {
                UhidNative.nativeKey(HID_KEY_LEFT_CTRL, false)
                ctrlHeld = false
            }
        } catch (_: Throwable) {
        }
        try {
            if (isKeyboardCreated) {
                UhidNative.nativeKeyboardClose()
                isKeyboardCreated = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "nativeKeyboardClose failed", t)
        }
        try {
            if (isUHidCreated) {
                UhidNative.nativeClose()
                isUHidCreated = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "nativeClose failed", t)
        }
        // Shizuku expects the reserved destroy() to terminate the user-service
        // process; leaving it alive would keep a dead UHid device attached.
        System.exit(0)
    }
}
