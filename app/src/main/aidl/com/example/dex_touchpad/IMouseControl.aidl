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

    /** Injects a system BACK key event. */
    void sendBack();

    /** Opens the Recents / overview screen. */
    void sendRecents();

    void destroy();
}
