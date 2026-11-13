# Device notes: Telzal TC 5G

Fill in after testing on the real watch (use Settings → About → Buttons & wheel test).

| Item | Value |
|---|---|
| Screen (px / dpi / round) | Owner's screenshot ≈ 320×385, rectangular with rounded corners (confirm with `adb shell wm size`) |
| Android | 8.x |
| Crown events | _unknown, record what the probe shows (KEY code or MOTION source/axis)_ |
| Back key code | _usually KEYCODE_BACK (4)_ |
| Home long-press | Firmware power menu still opens with a third-party launcher (confirmed by owner) |
| Sensors | step counter, GPS, vibration, speaker, mic. Heart rate is fake, so it is never shown |
| Flash LED | _check: Control Center flashlight uses the screen if there is no LED_ |

Known Android 8 limits (handled):
- No `GLOBAL_ACTION_LOCK_SCREEN` (API 28+), so Home uses Device-Admin `lockNow()`; without it a black curtain is shown until the system timeout.
- No `AnimatedImageDrawable` (API 28+), so animated faces are procedural.
- Real shutdown/reboot needs root (hidden "Root features" switch); otherwise the firmware power menu (Home long-press) or the accessibility power dialog.
