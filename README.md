# WatchLite

A lightweight launcher for Android 8 smartwatches (built for the Telzal TC 5G, works on any screen size, round or square).
Full Arabic (RTL) and English UI.

## Features
- **27 watch faces**: static, animated, analog and digital, each with its own clock layout and its own always-on look.
  Animated ones (liquid, warp, neon grid, ocean, bubbles, night drive, racer, speedometer…) are drawn procedurally at low cost.
- **Your own faces**: make one from any photo on the watch, or import a `.wtheme` file (format in `docs/THEME_FORMAT.md`).
- **Gestures**: swipe left = apps (honeycomb / grid / list, wheel zooms), right = Control Center, down = notifications, up = widgets, long-press = face editor.
- **Buttons**: Back, Home (screen off or always-on display), configurable double/long press on Back, rotary wheel.
- **Always-on display** with its own brightness (never touches system brightness), battery threshold with hysteresis, double-tap or raise to wake, burn-in protection, night schedule.
- **Hourly chime** at the exact hour, 10 built-in sounds plus your own, active hours and days, "strike the hour" mode.
- **Notifications** with dismiss, actions, quick replies (WhatsApp etc.), voice reply.
- **Widgets**: steps, prayer times, battery, stopwatch, timer, weather and music (from the phone), shortcuts.
- **Extras**: offline prayer times plus Hijri date, Arabic-Indic digits, theater mode, DND, touch and water lock (with water eject tone), flashlight, battery saver, automatic day/night/charging themes, backup and restore, safe mode, input probe for the crown.
- **Phone link layer** (Bluetooth LE, encrypted) ready for the companion app: see `docs/LINK_PROTOCOL.md`.

No third-party libraries in the app. Everything is drawn with Canvas to keep RAM and CPU low.

## Build
- Android Studio (latest) → Open this folder → Run. Or `./gradlew assembleDebug`.
- Every push builds on GitHub Actions: unit tests, APKs, and an Android 8 emulator run that renders every theme and screenshots each screen in English and Arabic (artifacts: `watchlite-apk`, `emulator-results`).

Install guide: `docs/INSTALL_AR.md` (Arabic) / `docs/INSTALL_EN.md`.
