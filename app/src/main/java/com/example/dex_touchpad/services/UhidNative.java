package com.example.dex_touchpad.services;

/**
 * JNI bridge to our own UHID mouse library ({@code libuhidmouse.so}).
 *
 * The library opens {@code /dev/uhid}, creates a relative mouse and writes the
 * HID reports itself, keeping the button state in every report so that dragging
 * works. It must run in a process with access to {@code /dev/uhid} (the Shizuku
 * user service runs as shell, which is in the {@code uhid} group).
 */
public final class UhidNative {

    static {
        System.loadLibrary("uhidmouse");
    }

    private UhidNative() {
    }

    /** Opens /dev/uhid and registers the virtual mouse. */
    public static native boolean nativeCreate(String name);

    /** Unregisters and closes the virtual mouse. */
    public static native void nativeClose();

    /** Relative movement in device units. */
    public static native void nativeMove(int dx, int dy);

    /** Holds or releases a button: 1 = left, 2 = right, 3 = middle. */
    public static native void nativeButton(int buttonCode, boolean pressed);

    /** Wheel notches; positive scrolls down. */
    public static native void nativeScroll(int delta);

    /** Opens /dev/uhid and registers a virtual keyboard (for modifier combos). */
    public static native boolean nativeKeyboardCreate(String name);

    /** Unregisters and closes the virtual keyboard. */
    public static native void nativeKeyboardClose();

    /**
     * Presses or releases one HID keyboard usage.
     * Modifiers are 0xE0 (left Ctrl) … 0xE7; e.g. 0x2B is Tab.
     */
    public static native void nativeKey(int usage, boolean pressed);
}
