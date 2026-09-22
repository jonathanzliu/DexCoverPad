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

    /** Injects a system BACK key event. */
    void sendBack();

    /** Opens the Recents / overview screen. */
    void sendRecents();

    void destroy();
}
