# Dex Touchpad

Use a Samsung cover/outer display as a touchpad for Samsung DeX. The cursor is a
real virtual HID mouse created through **Shizuku**.

## Requirements

- Samsung device with a cover/outer display (verified on `SM-F956U`, Android 16)
- [Shizuku](https://shizuku.rikka.app/) **13 or newer**, installed **and started**
  - Starting Shizuku itself still needs root or wireless debugging, per Shizuku's
    own design; that is outside this app.
- Shizuku permission granted to this app (prompted on first launch)

## Setup

1. Install and **start** Shizuku.
2. Install the APK.
3. Open the app and tap **Allow** on the Shizuku permission dialog.
4. The screen becomes a touchpad and the status line shows
   *"Connected — cover display is a touchpad"*.

## How it works

```
cover display touch  ──►  MainActivity / TouchpadView
                              │  IMouseControl (AIDL over Binder)
                              ▼
                    Shizuku UserService   ← created by Shizuku in a
                    (uid 2000 / shell)       separate process, app ClassLoader
                              │  JNI (UhidNative)
                              ▼
                    libuhidmouse.so        ← built from app/src/main/cpp
                              │  write(2) to /dev/uhid
                              ▼
                    kernel uhid ──► "DeX Touchpad Mouse" ──► DeX cursor
```

- `ShizukuUserService` is instantiated by Shizuku. Shizuku creates the
  app package context, makes the `Application`, and calls the `(Context)`
  constructor with that `Application`. The class therefore must extend
  `IMouseControl.Stub` (an `IBinder`) and expose a public `(Context)` and/or `()`
  constructor. It is deliberately **not** declared in `AndroidManifest.xml`.
- That process runs as shell (uid 2000). Shell is in the `uhid` group on these
  devices, so it can open `/dev/uhid`; a normal app process cannot.
- `UhidNative` is the JNI bridge and `app/src/main/cpp/uhid_mouse.c` is a small
  original implementation of the Linux UHID protocol. It sends
  `UHID_CREATE2` / `UHID_INPUT2` events (`11` / `12` on Linux 6.x — note the
  legacy entries occupy the low values) and keeps the button state in every
  movement report, which is what makes dragging work.

## Lifecycle and reliability

- The user service is bound in **daemon mode**, so Shizuku keeps exactly one
  instance and hands the same binder back on later binds. Reopening the app does
  not create a second virtual mouse.
- On startup the service kills any stale sibling `<pkg>:user_service` process it
  can find (it runs as shell, so it may). This reclaims orphaned `/dev/uhid`
  devices left behind by a force-stop, an app update, or an earlier crash.
- `TouchpadService` is `START_NOT_STICKY`, so a killed process is not silently
  resurrected into a second service.
- `MainActivity` re-binds automatically if the service disconnects, and ignores
  a dead binder instead of using it.

## Gestures

| Gesture | Action |
|---|---|
| One-finger drag | Move cursor |
| Tap | Left click |
| Tap, then touch and drag | Drag (left button held) |
| Press, hold, then drag | Drag (left button held; short buzz when it grabs) |
| Two-finger tap | Right click |
| Two-finger drag | Scroll |
| Pinch in / out | Zoom (Ctrl + scroll wheel) |
| Three-finger tap | System Back (sent to the external/DeX display) |
| Three-finger swipe up | Recents |
| Four-finger tap | Leave fullscreen |
| Left / Right Click buttons | Click |
| Fullscreen button | Hide all controls and the system bars — only the pad remains |

Pinch uses the keyboard device this app also registers over UHID, so it maps onto Ctrl + wheel. Recents and Back are
injected into the external/DeX display with `input -d <display> keyevent`.

Sensitivity is adjustable with the slider (0.1×–5.0×, applied on the app side).

The UI uses a pure-black AMOLED theme.

## How to build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

Requires JDK 17, Android SDK 34 (`local.properties` → `sdk.dir`) and the Android
NDK `26.3.11579264`:

```bash
sdkmanager "ndk;26.3.11579264"
```

If a copy of the NDK sits at `.ndk-dl/android-ndk-r26d`, the build uses it
automatically (`.ndk-dl/` is git-ignored).

## License

MIT — see `LICENSE`. Third-party components and their licenses are listed in
`THIRD_PARTY_NOTICES.md`.

This is a fork of [Gh0strab/DexCoverPad](https://github.com/Gh0strab/DexCoverPad)
by Will White. The original wireless-ADB app is his work; this fork replaces the
transport with Shizuku, adds the native UHID library and the trackpad gestures.

## Packaging

The application id is `com.example.dex_touchpad.shizuku`, so this build installs
**alongside** the original wireless-ADB `com.example.dex_touchpad` app. To replace
it in place, change `applicationId` in `app/build.gradle.kts` back to
`com.example.dex_touchpad` and uninstall the original first (the signing keys
differ, so an in-place update over it is not possible). Use a release signing
config before distributing widely.
