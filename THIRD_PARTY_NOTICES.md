# Third-party notices

This project is licensed under the MIT License (see `LICENSE`). It depends on
the following third-party components:

## Shizuku API

- Artifacts: `dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`
- Copyright (C) RikkaApps
- Licensed under the Apache License, Version 2.0
- https://github.com/RikkaApps/Shizuku

The app talks to the Shizuku service through the official API. It does **not**
bundle Shizuku, is not called "Shizuku", and does not use the reserved
`moe.shizuku.privileged.api` application id or its launcher icon, in line with
Shizuku's attribution requirements.

## AndroidX and Material Components

- Copyright (C) The Android Open Source Project
- Licensed under the Apache License, Version 2.0
- https://developer.android.com/jetpack/androidx

## Native library

`libuhidmouse.so` is built from `app/src/main/cpp/uhid_mouse.c` in this
repository and is covered by this project's MIT license. It implements the
Linux UHID protocol (a stable kernel UAPI) from scratch; it contains no
third-party code.
