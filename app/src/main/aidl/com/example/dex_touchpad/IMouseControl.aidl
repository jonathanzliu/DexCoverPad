package com.example.dex_touchpad;

interface IMouseControl {
    void moveCursor(float deltaX, float deltaY);
    void sendClick(int buttonCode);
    void sendScroll(float deltaY, float deltaX);
}
