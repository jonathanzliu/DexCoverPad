package com.example.dex_touchpad;

interface IMouseControl {
    void moveCursor(float deltaX, float deltaY);

    /** Press and release a button once. 1 = left, 2 = right, 3 = middle. */
    void sendClick(int buttonCode);

    /** Hold or release a button without releasing it (used for drag). */
    void sendButton(int buttonCode, boolean pressed);

    void sendScroll(float verticalDelta, float horizontalDelta);

    /** Pinch-to-zoom: positive zooms in, negative zooms out (Ctrl + wheel). */
    void sendZoom(float amount);

    /**
     * Starts a real two-finger pinch on the external display by injecting touch.
     * Returns false when injection is unavailable, so the caller falls back to
     * sendZoom().
     */
    boolean pinchBegin();

    /** scale = current finger distance / distance when the gesture started. */
    void pinchUpdate(float scale);

    /** Lifts both injected contacts. Safe to call without a matching begin(). */
    void pinchEnd();

    /**
     * Starts a real two-finger drag on the external display. The app's own touch
     * pipeline turns it into a scroll and computes the fling, so momentum is the
     * platform's rather than something we simulate. False means fall back to
     * sendScroll() on the wheel device.
     */
    boolean scrollBegin();

    /**
     * Moves the contacts. Fractions of the touchpad's own width/height, so a
     * full-height swipe scrolls a full display height whatever the screen sizes.
     */
    void scrollUpdate(float fracX, float fracY);

    /** Lifts both injected contacts and lets the app fling. */
    void scrollEnd();

    /** Injects a system BACK key event. */
    void sendBack();

    /** Opens the Recents / overview screen. */
    void sendRecents();

    void destroy();
}
