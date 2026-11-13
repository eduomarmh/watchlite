# WatchOS-Lite — Smartwatch Launcher: Full Implementation Plan

> **Audience:** an AI coding agent (and the human owner) that will implement this project end-to-end in Android Studio.
> **Owner's language:** Arabic (Egyptian). The launcher UI **must fully support Arabic + RTL** and English (LTR). Default = follow system locale; user can override in settings.
> **Working name:** `WatchOS-Lite` — package `com.watchlite.launcher` (change freely).

---

## 0. How the agent should work (read first)

1. Implement **phase by phase** (Section 17). Do not start a phase until the previous one builds and runs.
2. After every phase: the project must compile (`./gradlew assembleDebug`), and the phase's **Acceptance Criteria** must be listed with pass/fail in a `PROGRESS.md`.
3. Prefer **plain Android framework APIs + Kotlin stdlib + Coroutines**. No Compose, no heavy libraries. The target device is very weak (see Section 1). Every dependency must be justified.
4. Never hard-code pixel sizes. Everything is **density- and ratio-based** (Section 5).
5. Every user-visible string goes into `strings.xml` with **both `values/` (English) and `values-ar/` (Arabic)**. No hard-coded strings in Kotlin.
6. Keep a `docs/DEVICE_NOTES.md` where findings about the real device are recorded (key codes of the crown, quirks, etc.).
7. If something requires privileges the app cannot get (e.g. real shutdown without root), implement the documented fallback and state it clearly in `PROGRESS.md`; do not silently skip.

---

## 1. Target device & constraints

| Item | Value |
|---|---|
| Device | Telzal TC 5G smartwatch (Android-based, **not Wear OS**) |
| OS | **Android 8.x (API 26/27)** |
| RAM | Advertised 4 GB, **assume ~1–2 GB usable / weak SoC** — design for the worst case |
| Display | Small touch screen. Screenshot provided by owner is about **320×385 px** and looks rectangular/portrait with rounded corners. **Do not assume**; the launcher must support **round, square, rectangular, any resolution/density** |
| Hardware inputs | **Rotary "scroll wheel"** with a **Back** button on it; a **large Home button**; a power button |
| Sensors | Vibration motor, speaker, microphone, step counter, **GPS**. Heart-rate sensor is **fake → ignore it** (never display HR). No double-tap-to-wake sensor. |
| Store | Google Play exists, but the launcher is installed via **APK sideload** |
| System behavior to preserve | **Long-press on Home already opens the firmware power menu** (shutdown / restart / running apps) even with a third-party launcher. The launcher must **not break it** (verify in Phase 2). If a custom launcher breaks it, implement the fallback in Section 11. |

### Performance budgets (hard requirements)
- Cold start of launcher: **< 1.5 s**. Home screen first frame: **< 500 ms** after resume.
- Idle RAM (home screen showing static theme): **< 60 MB PSS**; animated theme: **< 90 MB**.
- Home screen frame rate: **30 fps max for animated themes** (configurable 15/24/30); **0 fps when screen is off / AOD** except 1 update/minute.
- Zero work in background when the screen is off, except: hourly alarm, battery receiver, (optional) companion link.
- APK size target: **< 25 MB** (themes' heavy assets are optional packs).
- No wakelocks held except during AOD (via window flag) and chime playback.

---

## 2. Feature overview

1. Replace the stock launcher (HOME category).
2. **Watch-face themes** (many): static + animated backgrounds, per-theme clock placement, analog/digital, complications. User can **import/create/edit** themes (also via companion phone app later).
3. **Gestures / navigation** (Section 6): swipe right = app drawer, swipe down = notifications, swipe left = Control Center, swipe up = widgets/shortcuts, long-press = theme edit.
4. **Rotary wheel** support (zoom in app drawer, scroll lists, switch themes) + **Back** + **Home** buttons.
5. **Always-On Display (AOD)** with its own independent brightness, battery threshold, burn-in protection, **double-tap to wake from AOD**.
6. **Hourly chime** at exact hour (sounds bundled + user's own files).
7. **Companion link layer** (notifications mirror, battery, internet, location, contacts, etc.) — **the launcher side is built now as toggleable modules; the phone app is built later.** The launcher must expose a stable protocol (Section 12) so the phone app can be written afterwards.
8. **Settings app/screen** with a shortcut in the app drawer and on the control center.
9. Extras agreed with the owner (Section 10): theater mode, DND, water/touch lock, prayer times + Hijri date, Arabic-Indic digits, battery saver, time-based theme switching, step counter, stopwatch/timer, flashlight, find-my-phone, camera remote, app hide/rename, backup/restore, safe mode, button shortcuts, haptics, raise-to-wake (if the accelerometer/gravity sensor is present).

---

## 3. Tech stack & project setup

- **Language:** Kotlin (JVM target 17), Coroutines only.
- **Build:** Gradle (Android Gradle Plugin compatible with current stable Android Studio), version catalog (`libs.versions.toml`).
- `minSdk = 26`, `compileSdk = 34` (or latest installed), **`targetSdk = 26`** (sideloaded launcher on an Android 8 device; avoids newer background/foreground-service restrictions that don't exist on the device anyway). Add a comment explaining why.
- **UI:** Custom `View` / `Canvas` for the watch face and drawer; plain `ViewGroup`s + `RecyclerView` (androidx.recyclerview only) for lists. `AppCompat` is allowed but prefer framework `Activity` to save RAM.
- **Allowed dependencies (justify each in README):**
  - `androidx.recyclerview`, `androidx.core:core-ktx`, `kotlinx-coroutines-android`
  - Optional: `lottie` (only if animated-theme requirement can't be met by frame-animation/WebP) — measure memory before keeping it.
  - JSON: use `org.json` from the framework (no Moshi/Gson).
- **Proguard/R8:** enabled for release; shrinkResources on.
- **Modules (single Gradle module `app` is fine):** keep packages clean:

```
com.watchlite.launcher
├── app/            Application class, DI-lite (manual service locator)
├── home/           HomeActivity (launcher), gesture router, page host
├── face/           ThemeEngine, WatchFaceView, renderers, complications
├── theme/          ThemeRepository, manifest parser, importer, editor UI
├── drawer/         AppDrawer (grid/list/honeycomb), AppRepository
├── notifications/  Local notification center + NotificationListenerService
├── control/        Control Center tiles
├── widgets/        Swipe-up widgets (steps, weather, timer, stopwatch, music)
├── aod/            AodController, AodView, brightness + battery policy
├── chime/          HourlyChimeScheduler, ChimePlayer, sound library
├── input/          Rotary/key handling, InputProbeActivity
├── link/           Companion link layer (BLE GATT server + protocol) — module toggle
├── prayer/         Offline prayer-times calculator, Hijri date
├── settings/       SettingsActivity + PreferencesRepository
├── system/         Power menu fallback (AccessibilityService), flashlight, DND, etc.
├── safety/         Crash guard / Safe mode
└── util/
```

---

## 4. Application lifecycle & manifest essentials

- `HomeActivity`: `launchMode="singleTask"`, intent-filter: `MAIN` + `HOME` + `DEFAULT`. `android:excludeFromRecents="true"`, `android:stateNotNeeded="true"`, `android:clearTaskOnLaunch="true"`, `android:resumeWhilePausing="true"`. Hardware accelerated.
- On `onNewIntent` with HOME category: route to **"home pressed" handler** (Section 7 / AOD logic).
- Permissions (request only when the corresponding feature is enabled, with an in-app rationale in Arabic/English):
  - `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `WAKE_LOCK`, `SET_ALARM`, `FLASHLIGHT`/`CAMERA` (flashlight), `ACCESS_NOTIFICATION_POLICY` (DND), `WRITE_SETTINGS` (only for optional features, never for AOD), `READ_PHONE_STATE` (optional), `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (BLE scan on API 26 + prayer times/GPS), `ACTIVITY_RECOGNITION`-equivalent step counter (`TYPE_STEP_COUNTER` needs no permission on API 26), `READ_EXTERNAL_STORAGE` (custom chime sounds / theme import; use SAF document picker where possible), `QUERY_ALL_PACKAGES` not needed on API 26.
  - `BIND_NOTIFICATION_LISTENER_SERVICE` (service declaration), `BIND_ACCESSIBILITY_SERVICE` (service declaration, optional).
- Services: `NotificationListener`, `LinkService` (foreground only while a phone is connected, with a minimal ongoing notification), `PowerMenuAccessibilityService` (disabled by default).
- Receivers: `BOOT_COMPLETED`, `ACTION_BATTERY_CHANGED` (registered dynamically), `ACTION_SCREEN_ON/OFF` (dynamic), `ACTION_TIME_TICK` (dynamic, only while face visible), `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, `ACTION_LOCALE_CHANGED`, alarm receiver for chime.

---

## 5. Responsive layout system (supports ALL screen sizes)

**Rule:** the launcher never uses fixed dp/px for layout of the watch face or pages.

- Define a **"canvas"**: the full display in px. All theme coordinates are **normalized floats 0.0–1.0** of canvas width (x) and height (y). Sizes of text are normalized to `min(width, height)`.
- Provide `ScreenProfile` detected at runtime: `width`, `height`, `densityDpi`, `isRound` (`Configuration.isScreenRound`), `shortSide`, `safeInsets` (for round screens, a 10–12 % inset so UI never touches the corners).
- For round screens, compute the **inscribed circle**; interactive elements in drawer/control center are arranged to stay inside it.
- Text uses a `Paint` with `textSize = norm * shortSide`; auto-shrink-to-fit for Arabic strings (which can be wider).
- **Test matrix** (emulator profiles, mandatory in Phase 6): 320×385 (owner's device), 360×360 round, 400×400 round, 280×280 square, 454×454 round, 240×240 square.

---

## 6. Navigation, pages & gestures

### 6.1 Page model
The Home screen is the center. Four edge pages, opened by swipe from the edge direction with a **short, interruptible, GPU-friendly translation animation** (no overdraw, no blur).

| Gesture (on Home) | Opens | Notes |
|---|---|---|
| Swipe **from right → left** (finger moves left) | **App drawer** | Apple-Watch-style. Modes: Honeycomb, Grid, List (switch in settings; also a quick toggle button inside the drawer). **Rotary wheel = zoom icons larger/smaller** (changes columns / icon size, persisted). |
| Swipe **down** (from top) | **Notification Center** | Local notifications (from the watch itself) + mirrored phone notifications if link is enabled. Swipe item sideways to dismiss; "Clear all" button; tap to open; **inline reply** for messaging apps (RemoteInput) with canned replies + keyboard/voice if available. |
| Swipe **from left → right** (finger moves right) | **Control Center** (agreed) | Tiles: Wi-Fi, Bluetooth, Mobile data, Airplane, DND, Theater mode, Flashlight, Volume, Brightness (system), Screen-lock/Touch-lock, Water lock, AOD on/off, Location, Find phone, Settings shortcut, Battery saver. |
| Swipe **up** (from bottom) | **Widgets / quick glance** | Steps, weather (needs link), next alarm, stopwatch, timer, music controls (needs link), prayer times, calendar next event (link). |
| **Long-press** anywhere on the face (≥ 600 ms) | **Face editor** | Switch theme (horizontal swipe through previews), customize complications/colors, "Add theme" shortcut. Haptic tick on entry. |
| Long-press **Home hardware button** | Firmware power menu | Leave to system; fallback in Section 11. |

**Direction wording is physical-finger direction.** In RTL locales do **not** mirror the swipe directions of these system-like gestures (they must stay physically consistent), but do mirror internal list layouts.

### 6.2 Hardware buttons
- **Back button (on the crown):** goes back one level (page → home; sub-screen → previous). On Home with nothing open: no-op (or optional "show last app").
- **Home button (large):**
  - If another page/app is open: return to the watch face.
  - If already on the watch face:
    - **AOD enabled & allowed** (battery ≥ threshold): start AOD transition (Section 8).
    - **AOD disabled / not allowed:** turn the screen off. Implementation order: (1) `DevicePolicyManager.lockNow()` if the user granted device-admin, (2) otherwise `PowerManager`-less fallback: set window to minimal brightness + show black overlay and let the system timeout (documented), (3) Accessibility `GLOBAL_ACTION_LOCK_SCREEN` — exists only from API 28, so **not available on Android 8**. Therefore the recommended path is **Device Admin `lockNow()`** (single permission prompt, explained in settings). Document in `docs/DEVICE_NOTES.md`.
- **Rotary wheel:** see Section 6.3.

### 6.3 Rotary input discovery (IMPORTANT — do first in Phase 1)
The crown's event type on this device is unknown. Build **`InputProbeActivity`** (reachable from a hidden entry in settings): it logs on-screen and to logcat every `KeyEvent` (keyCode, scanCode, source), `MotionEvent` generic events (`AXIS_SCROLL`, `AXIS_VSCROLL`, `AXIS_RSB_SCROLL`, `SOURCE_ROTARY_ENCODER`, `SOURCE_MOUSE`…), and **hardware button** presses. Then the real mapping is stored in `docs/DEVICE_NOTES.md` and in an `InputMapper` class with a configurable mapping (the owner can remap in settings: wheel-up/wheel-down → which key/axis).
Possible encodings to support: `onGenericMotionEvent` with `AXIS_SCROLL`/`AXIS_VSCROLL`; `KEYCODE_DPAD_UP/DOWN`, `KEYCODE_PAGE_UP/DOWN`, `KEYCODE_VOLUME_UP/DOWN`, `KEYCODE_NAVIGATE_NEXT/PREVIOUS`, `KEYCODE_STEM_PRIMARY/1/2/3`.
Wheel actions by context:
- Home: switch theme (optional, off by default) or nothing.
- Drawer: zoom (default) / scroll (toggle).
- Lists/Notifications/Settings: scroll with **haptic tick per detent** (optional).

---

## 7. Home screen & watch-face engine

### 7.1 Rendering
- `WatchFaceView` (custom `View`, hardware-layer disabled for animated bg to avoid memory; for static themes cache the background bitmap once).
- Layers (z-order): `background` → `decorations` → `complications` → `hands/digits` → `overlay` (e.g., seconds sweep).
- Redraw policy: digital w/o seconds = once per minute (`ACTION_TIME_TICK`); with seconds or sweeping hands = `Choreographer` at configured FPS **only while the view is visible and screen is on**.
- Animated backgrounds supported types (in priority order, choose the lightest that works on the device):
  1. **Animated WebP / frame sequence** decoded into a small ring buffer (≤ 3 frames in memory), via `AnimatedImageDrawable` (API 28+ only!) → **not available on API 26**, so implement a **custom frame-sequence player** (sprite sheet or numbered WebP/JPEG frames) with `BitmapFactory` + `inBitmap` reuse.
  2. **Short looping video** with `MediaPlayer` + `TextureView` (low-res, e.g. ≤ 480p, no audio) — only if memory allows; auto-fallback to static first frame in battery saver.
  3. **Procedural backgrounds** drawn in code (gradients, flowing blobs, particles, starfield) — cheapest and resolution-independent. Provide a small set of these (e.g. "Aurora flow", "Starfield", "Gradient waves", "Bubbles").
  4. Lottie (optional dependency).
- When screen turns off or AOD starts: stop all animation, release large bitmaps/players (`onTrimMemory` aware).
- Wallpaper reference look (from owner's screenshot): dark background with flowing blue/purple/orange glossy shapes and a rounded translucent "74 %" battery chip at the top-left → implement a theme **"Flow"** reproducing this style procedurally or with a bundled image, and make the **battery chip** a reusable complication (rounded rect, translucent, text + optional fill-level tint).

### 7.2 Theme package format `.wtheme`
A ZIP with this layout:

```
mytheme.wtheme
├── manifest.json
├── preview.webp            (square, ≤ 256 px)
├── bg/                      (images / frames / video)
├── fonts/                   (optional .ttf, must include Arabic glyphs if Arabic digits/dates are used)
└── assets/                  (hands, icons, overlays)
```

`manifest.json` (schema v1):

```json
{
  "schema": 1,
  "id": "com.example.flow",
  "name": { "en": "Flow", "ar": "تدفق" },
  "author": "Owner",
  "version": 1,
  "supportsAod": true,
  "background": {
    "type": "image | frames | video | procedural",
    "src": "bg/flow.webp",
    "frames": { "pattern": "bg/f_%03d.webp", "count": 60, "fps": 15, "loop": true },
    "procedural": { "name": "aurora", "colors": ["#3a1c71","#d76d77","#ffaf7b"] },
    "scaleMode": "centerCrop"
  },
  "elements": [
    {
      "type": "digitalTime",
      "x": 0.5, "y": 0.42, "anchor": "center",
      "size": 0.22,
      "format": "HH:mm",
      "font": "fonts/Bold.ttf",
      "color": "#FFFFFF",
      "shadow": { "r": 0.01, "dx": 0, "dy": 0.005, "color": "#80000000" },
      "digits": "auto | latin | arabicIndic"
    },
    {
      "type": "analogHands",
      "cx": 0.5, "cy": 0.5, "radius": 0.45,
      "hour":   { "src": "assets/hour.webp",   "pivot": [0.5, 0.9] },
      "minute": { "src": "assets/minute.webp", "pivot": [0.5, 0.92] },
      "second": { "src": "assets/second.webp", "pivot": [0.5, 0.9], "smooth": false }
    },
    { "type": "date", "x": 0.5, "y": 0.62, "size": 0.06, "format": "EEE d MMM", "calendar": "gregorian | hijri" },
    { "type": "batteryChip", "x": 0.04, "y": 0.04, "anchor": "topLeft", "w": 0.30, "h": 0.11, "style": "glass" },
    { "type": "complication", "slot": "bottom", "x": 0.5, "y": 0.85, "w": 0.4, "h": 0.1, "default": "steps" },
    { "type": "text", "x": 0.5, "y": 0.2, "text": "{prayer.next} {prayer.nextTime}", "size": 0.05 }
  ],
  "aod": {
    "elements": [ { "type": "digitalTime", "x": 0.5, "y": 0.5, "size": 0.18, "color": "#FFFFFF", "style": "outline" } ],
    "pixelShift": true
  },
  "editable": {
    "colors": ["time", "accent"],
    "complicationSlots": ["bottom"]
  }
}
```

Requirements for the engine:
- **The clock position, size, style differ per theme** — everything comes from the manifest, nothing hard-coded.
- Elements are validated; unknown element types are ignored with a log (forward compatibility). A broken theme must never crash the launcher — fall back to the default theme and show a toast.
- Placeholders in text: `{time}`, `{date}`, `{battery}`, `{steps}`, `{prayer.next}`, `{prayer.nextTime}`, `{hijri}`, `{weather.temp}`, etc.
- Support both **Latin digits and Arabic-Indic digits** (٠١٢٣٤٥٦٧٨٩) per theme/global setting; support Arabic month/day names; correct **bidi** handling for mixed text (use `BidiFormatter`).

### 7.3 Bundled themes (initial set: 12–15)
Create real, good-looking themes (research modern smartwatch face styles for inspiration; **do not copy trademarked designs** such as Apple/Samsung faces; use original assets or procedural art). Suggested set:

1. **Flow** (default; the owner's screenshot style; glossy blobs, battery chip top-left)
2. **Minimal Digital** (big thin numerals)
3. **Classic Analog** (clean hands, tick marks)
4. **Sport** (bold digits + steps ring + battery ring)
5. **Aurora** (procedural animated gradient)
6. **Starfield** (procedural animated particles; very light)
7. **Neon Grid**
8. **Dual Time / Date focus** (big date, small time)
9. **Arabic Calligraphy** (Arabic-Indic digits, Hijri date, prayer next-time)
10. **Dark OLED** (pure black, very low power)
11. **Nature still** (static photo-like gradient landscape, procedurally drawn)
12. **Retro LCD**
13. **Gradient Waves** (animated)
14. **Bubbles** (animated)
15. **Pro Chrono** (analog + sub-dials: steps, battery, date)

Themes **can be added/removed by the user** (import `.wtheme` from storage; later pushed from the phone companion app).

### 7.4 Face editor (long-press)
- Horizontal carousel of theme previews (rendered live at low-res), tap to apply.
- "Customize": pick accent/time color, toggle seconds, choose complication for each slot, choose digit style (Latin/Arabic-Indic), choose date calendar.
- "Add theme" → file picker for `.wtheme` + link to companion app flow (future).
- "Settings" shortcut icon.
- Theme scheduling rule editor (Section 10.7).

---

## 8. Always-On Display (AOD) — detailed spec

**Goal:** the owner can turn AOD on/off; when on, pressing Home on the watch face (or the idle timeout, see below) fades the screen to a **very dim, minimal clock** that stays on, with **its own brightness that never fights the system brightness**.

### 8.1 Behavior
- **Trigger A — Home button on watch face:** if AOD enabled and battery ≥ threshold: animate brightness from current → AOD level over ~800 ms, then switch to `AodView`. If AOD disabled or battery < threshold: turn screen off (Section 6.2).
- **Trigger B — Idle timeout (optional setting "AOD after inactivity"):** a few seconds (user-selectable 5/10/15/30 s) before the normal system timeout would turn the screen off, begin the dim-fade and enter AOD. To achieve this the launcher sets `FLAG_KEEP_SCREEN_ON` on its window while the face is visible and runs **its own inactivity timer**; the system screen-off timeout is therefore never reached while on Home. (If AOD is disabled, drop the flag and let the system handle timeout normally.)
- **Exit:** any touch, Home, Back, or rotary event → brightness animates back to "no override", normal face shown. **Double-tap on the screen while in AOD wakes the full face** (owner's request: replaces the missing double-tap sensor). A single tap in AOD can be configured as: ignore / wake. Default: **wake on double-tap only** to avoid accidental wake in the pocket; "wake on single tap" is a setting.
- The power button still turns the screen off at the OS level; when woken again the launcher resumes normally (documented limitation).

### 8.2 Independent brightness (key requirement)
- Use **per-window brightness**: `window.attributes.screenBrightness = level` (0.0–1.0; use a very small value like 0.01–0.05; user slider "AOD brightness" with the lowest step as dim as hardware allows). **Never modify `Settings.System.SCREEN_BRIGHTNESS`.** On exit, set `screenBrightness = BRIGHTNESS_OVERRIDE_NONE (-1f)` so the system brightness the user chose is untouched.
- Additionally, the AOD UI uses **low-luminance colors** (pure black background + dim gray/white text, optional user "AOD text opacity" slider) and no large bright areas; max ~10–15 % of pixels lit.
- Provide "AOD brightness" + "AOD content opacity" sliders with live preview.

### 8.3 Battery policy
- Setting **"Disable AOD below X %"** (slider 5–90 %, default 30 %).
- Register `ACTION_BATTERY_CHANGED`. If level < X and not charging → AOD automatically not entered, and if currently in AOD → leave it (screen off normally). When level rises ≥ X + 2 (hysteresis) or charging starts → AOD allowed again (setting "Allow AOD while charging" default on).
- If AOD is already running while the threshold is crossed, exit gracefully (fade to off).

### 8.4 Burn-in & power
- Pixel shift: move content by a few px (random within ±(1.5 % of short side)) **once per minute**.
- Redraw **once per minute** (`ACTION_TIME_TICK`), no animations, no seconds.
- Release animated-theme resources while in AOD.
- AOD layout comes from the theme's `aod` block (so each theme has its own AOD look); fallback = generic dim clock.
- Optional "AOD only between hours" schedule (e.g. disable at night 00:00–06:00 to save battery).

### 8.5 Acceptance criteria
- Toggle AOD on, press Home on face → fades into AOD; system brightness slider unchanged before/after.
- At battery below threshold (simulate via `adb shell dumpsys battery set level 20`) AOD is not entered; when raised above threshold + hysteresis it is allowed again.
- Double-tap wakes; single tap does not (default).
- AOD consumes ≈ 0 CPU between minute ticks (verify with `adb shell dumpsys cpuinfo` / profiler).

---

## 9. Hourly chime

- Scheduler: `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, nextHourMillis, pendingIntent)`; the receiver plays the sound then **re-schedules the next hour**. Reschedule on boot, time change, timezone change, and settings change.
- Exactness: trigger at `HH:00:00` (± 1 s). Pre-load the sound ~2 s before if latency is noticeable (use `SoundPool` for short sounds, `MediaPlayer` for long).
- Settings:
  - Enable/disable.
  - **Sound library:** at least **8–10 bundled sounds** (original/royalty-free or synthesized: soft bell, chime, bird, marimba, digital beep, church bell, water drop, harp, etc.) + **"Add my own sound"** (pick audio file via SAF; copy into app storage; keep a list; allow delete/rename; max length 10 s).
  - Optional **different sound per hour** or "n strikes = hour" mode (cuckoo style).
  - Active hours range (e.g. 08:00–23:00), days of week.
  - Volume (own slider, independent of media volume where possible via `AudioManager.STREAM_ALARM` or `STREAM_NOTIFICATION`), vibration option.
  - Respect DND / Theater mode (default: silent in those modes, configurable).
  - Test-play button.
- Holds a short `PARTIAL_WAKE_LOCK` only during playback.

---

## 10. Extra features (all approved by owner)

10.1 **Raise-to-wake** — if a suitable motion sensor (e.g. `TYPE_WAKE_UP_GESTURE`/`TYPE_SIGNIFICANT_MOTION`/accelerometer-based detection) exists. Implement a low-power accelerometer heuristic only if no dedicated sensor; make it optional and off by default; measure battery. *(Double-tap-to-wake in AOD is implemented in software; see Section 8.)*

10.2 **Theater mode & Do-Not-Disturb** — Theater: screen stays off, no wake on notifications, sounds off. DND: via `NotificationManager.setInterruptionFilter` (needs notification-policy access) with schedule.

10.3 **Water lock / touch lock** — blocks touch (full-screen transparent overlay) until the user performs a defined unlock (e.g. hold Home for 2 s, or rotate wheel N detents).

10.4 **Prayer times + Hijri date** — fully **offline** calculation (implement standard algorithms: Egyptian General Authority of Survey default; also MWL, Umm al-Qura, ISNA, Karachi; Hanafi/Shafi'i Asr option) using the watch GPS or a manually chosen city (bundle a small list of Arab/Islamic cities). Show next prayer + countdown as a complication; optional adhan/notification sound (user-supplied file), silent in Theater mode. Hijri calendar via `java.time.chrono.HijrahChronology` is **API 26+ OK**, with a user day-adjustment (−2…+2).

10.5 **Arabic-Indic digits & full RTL** — global toggle; theme can override. All settings UIs mirrored properly in RTL. Provide Arabic fonts that render well on small screens (bundle one compact Arabic-capable font, e.g., an open-license one such as Noto Sans Arabic/Cairo/Tajawal; check license, include `LICENSES.md`).

10.6 **Battery saver mode** — below a configurable threshold (default 15 %) or manually: disable animated backgrounds, cap FPS, disable AOD, stop companion link sync, dim UI, optionally switch to "Dark OLED" theme.

10.7 **Time-based theme switching** — rules: by time range (day/night/work), by charging state, by battery level. Evaluated on `ACTION_TIME_TICK`/events (no polling).

10.8 **Steps counter & widgets** — `TYPE_STEP_COUNTER` (daily reset at midnight, persisted baseline across reboots), daily goal ring complication. **No heart-rate UI** (sensor is fake). Stopwatch, Timer, Alarm shortcut widgets.

10.9 **Flashlight** (camera torch or screen-white fallback), **Find my phone** (sends a ring command through the link layer), **Camera remote** (link layer command).

10.10 **App management** — hide apps, rename, reorder, per-app notification filter, "favorites" row on top of the drawer.

10.11 **Backup & restore** — export all settings + custom themes + custom sounds to a single `.wlbackup` zip on storage; import restores.

10.12 **Safe mode** — a crash guard: if `HomeActivity` crashes ≥ 3 times within 60 s, next start launches a **minimal launcher mode** (plain clock + app list + "Reset launcher" and "Disable last theme" buttons) and offers to send logs to a file. Never leave the watch without a working home screen.

10.13 **Button shortcuts** — configurable actions: double-press Back, long-press Back (e.g. flashlight, open settings, toggle AOD, open last app). Only for events the OS delivers to the launcher.

10.14 **Haptics** — short tick on wheel detents, long-press entry, theme switch; user-adjustable (off/light/strong).

10.15 **Extra ideas to include if time permits** — screen-on timeout slider handled by the launcher; "quick reply" canned messages editable by the user (Arabic + English); low-battery warning toast; auto-brightness shortcut; app usage-based ordering in the drawer; "Go to phone call screen" shortcut if the device has telephony (it is a 5G watch, so a **dialer/messages shortcut** may exist — detect and show only if present).

---

## 11. System-level actions & the Home long-press menu

- **Step 1 (Phase 2):** verify that the firmware's long-press-Home power menu still works with this launcher. If yes → do nothing and document it.
- **If it breaks:** implement `PowerMenuAccessibilityService` (user enables it manually in Accessibility settings; guide screen in the app):
  - Power menu: `performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)` (API 21+, works on Android 8 for opening the system power dialog).
  - Running apps: `GLOBAL_ACTION_RECENTS`.
  - Detect long-press Home: not deliverable to the app normally → map the **long-press of another hardware button / a long-press on a dedicated UI element / the Control Center tile** to open it; document the limitation.
- **Direct shutdown/reboot** require system permission/root; if the owner has root or ADB-granted privileges later, add optional `su -c reboot -p` / `su -c reboot` actions behind a "Root features" toggle. Otherwise rely on the system power dialog.
- Lock screen: Device Admin `lockNow()` (Section 6.2).
- "Running apps" in our own UI: show last-used apps via `UsageStatsManager` (needs usage-access permission) with a **"close"** action using `ActivityManager.killBackgroundProcesses` (`KILL_BACKGROUND_PROCESSES` permission).

---

## 12. Companion link layer (launcher side now; phone app later)

All companion features are **individual modules the user can turn on/off** in Settings → "Phone link". Off by default. When all are off, `LinkService` is not running.

### 12.1 Transport
- **Primary: Bluetooth LE GATT.** The watch acts as a **GATT server (peripheral)** advertising a custom service UUID; the phone app (later) is the central. Use an MTU-aware chunking layer (length-prefixed framing, CRC, resume), since messages (notification payloads, images, themes) exceed the MTU.
- Pairing: one-time QR/numeric-code pairing shown on the watch; derive a shared key (ECDH) and then **encrypt (AES-GCM) + authenticate** every frame. Store keys in Android Keystore (or encrypted prefs on API 26).
- Optional later: Wi-Fi/TCP bulk channel for large theme files; or relay via internet since the watch has 5G (out of scope for v1, keep transport abstract: `interface LinkTransport`).

### 12.2 Protocol (JSON over framed messages; schema-versioned)
Message envelope: `{ "v":1, "id":"uuid", "type":"...", "ts":1731500000, "payload":{...} }`.
Types the **launcher must implement handlers for now** (stubs with a mock sender in a debug screen "Link Simulator" so everything is testable without the phone app):

| Direction | Type | Purpose |
|---|---|---|
| phone→watch | `notif.post` / `notif.remove` / `notif.update` | Mirror notification (app, title, text, icon bitmap small, actions incl. reply RemoteInput key, conversation id) |
| watch→phone | `notif.dismiss` / `notif.action` / `notif.reply` | Dismiss, invoke action, reply to WhatsApp-like apps |
| phone→watch | `battery.phone` | Phone battery/charging state (show on watch, optional low-phone-battery alert) |
| phone→watch | `net.state` | Phone internet/Wi-Fi/mobile state |
| phone→watch | `location.state` | Phone location (optional, for weather/prayer/“find me”) |
| phone→watch | `contacts.sync` | Contacts (names + numbers, hashed photo refs) for dialer/messages UI |
| phone→watch | `calls.state` | Incoming call (answer/reject commands back) |
| phone→watch | `weather.update` | Weather data for complications |
| phone→watch | `media.state` / watch→phone `media.cmd` | Music info + play/pause/next/prev/volume |
| phone→watch | `theme.install` (+ chunks) | Deliver a `.wtheme` file; watch validates & installs |
| phone→watch | `theme.apply` | Apply a theme by id; or push **parameter edits** (colors, element positions) for live theming from the phone editor |
| watch→phone | `find.phone` / `camera.remote` | Ring phone / shutter |
| both | `ping` / `hello` / `capabilities` | Handshake and capability negotiation |

- Each module exposes a `Settings → Phone link → <module>` switch (notifications, battery, internet state, location, contacts, calls, media, weather, themes, find-phone, camera remote).
- `NotificationListenerService` on the watch (user enables access once) is used for **the watch's own** notifications; mirrored phone notifications are rendered by the same Notification Center UI through an internal model `NotificationItem(source = LOCAL | PHONE)`.
- Provide a `docs/LINK_PROTOCOL.md` documenting everything so the phone app can be built later by another AI session without reading the launcher code.

### 12.3 Phone app (future project — design constraints now)
- Android app; features: pairing, permissions to read phone notifications (NotificationListener) and forward, theme library/editor (WYSIWYG: pick background, drag the clock/complications, choose fonts/colors, preview round/square/rect profiles), sound manager, sync toggles. **The theme editor produces the same `.wtheme` schema defined in Section 7.2** — keep that schema stable and versioned.

---

## 13. Settings (`SettingsActivity`, also reachable via drawer icon + control-center tile)

Sections (all searchable by title; every label in AR/EN):
1. **Appearance:** theme gallery/manager, import theme, digits (Latin/Arabic-Indic), 12/24 h, language override, accent color, animation FPS cap.
2. **Gestures & buttons:** swipe-left page content (control center / widgets), rotary action per context, wheel direction invert, button shortcuts, haptics.
3. **Always-On Display:** enable, brightness slider, content opacity, idle-to-AOD time, battery threshold, allow while charging, schedule, wake-on (double-tap / single-tap), pixel shift.
4. **Hourly chime:** as Section 9.
5. **App drawer:** layout mode, icon size, hide/rename/reorder, favorites.
6. **Notifications:** local notifications access, per-app filter, canned replies, phone mirror.
7. **Phone link:** enable, pair, per-module toggles, link simulator (debug).
8. **Prayer & calendar:** location mode, calculation method, Asr method, Hijri adjustment, adhan sound.
9. **Power:** battery saver threshold/behavior, screen lock method (Device Admin), Theater/DND schedule.
10. **System helpers:** accessibility service guide, usage-access permission, root features (hidden).
11. **Backup & restore, Safe mode, About, Diagnostics** (input probe, device info, log export).

Persistence: `SharedPreferences` (typed wrapper `PreferencesRepository`) or a small JSON file; no Room/DataStore needed.

---

## 14. Localization & RTL requirements

- `android:supportsRtl="true"`; use `start/end` instead of `left/right`.
- `values/strings.xml` (en) and `values-ar/strings.xml` (ar) complete for **every** string including theme names, notification texts, setting descriptions, errors, toasts.
- Dates: use `java.time` with `Locale`; month/day names localized; Arabic-Indic digits via `NumberFormat`/custom mapper according to the setting.
- Numerals in text mixed with Arabic: use `BidiFormatter.unicodeWrap` for numbers/percentages (e.g., "٧٤٪" vs "74%").
- Test strings in Arabic that are long: no truncation in important places; marquee/ellipsize where needed.
- The launcher drawer should **sort Arabic app names correctly** (use `Collator` for the locale).
- Fonts: bundle one Arabic-capable compact font; ensure the theme digitalTime element supports it.

---

## 15. Quality, testing, diagnostics

- **Emulator profiles:** create AVDs for a round watch (API 26) and a custom 320×385 device; also test on a standard phone AVD API 26 for sanity.
- **On-device (owner's watch):** instrument with an in-app **Diagnostics screen**: current PSS memory, FPS, battery stats, last crash, input-probe, screen profile (px, dpi, round?).
- **Unit tests:** theme manifest parser/validator, prayer-times calculator (golden values for Cairo and a few cities), Hijri conversion, AOD battery hysteresis state machine, chime next-hour computation (incl. DST/timezone changes), backup/restore round-trip.
- **Instrumented tests (light):** launcher registers as HOME; gesture router; theme import.
- **Manual QA checklist** at the end of each phase in `PROGRESS.md`.
- **Logging:** a tiny in-app ring-buffer logger (exportable to a file); no network analytics, no tracking.
- **Security/privacy:** companion data (notifications, contacts) never leaves the device/link; encrypt link; no logging of notification content in release; imported `.wtheme` is **data only** (no code execution) and validated (path traversal in zip entries must be rejected, size caps, max frame counts).

---

## 16. Owner setup guide (include as `docs/INSTALL_AR.md` in Arabic and `docs/INSTALL_EN.md`)

1. Enable **Developer options** on the watch: *Settings → About watch/phone → tap "Build number" 7 times* (the label may differ; check also *Version* / *System*).
2. Enable **USB debugging** (and **Wireless debugging / ADB over Wi-Fi** if available) in Developer options. If the watch has no USB data pins on the charger, use **ADB over Wi-Fi**: `adb connect <watch-ip>:5555` (after `adb tcpip 5555` once via USB, or via the "Wireless debugging" pairing screen if it exists on this firmware).
3. Install: `adb install -r app-debug.apk`, or copy the APK to the watch and install with the file manager (enable "Install unknown apps").
4. Press Home → choose **WatchOS-Lite** → **Always**.
5. Grant requested permissions from the in-app onboarding (notification access, usage access, device admin, exact alarms, location) — each can be skipped; features degrade gracefully.
6. To revert to the stock launcher: *Settings → Apps → Default apps → Home* (or `adb shell cmd package set-home-activity <stock-component>`). The launcher must provide an in-app **"Switch back to stock launcher"** button that opens the system default-apps screen.

---

## 17. Phases, deliverables & acceptance criteria

### Phase 0 — Project scaffold
- Gradle project, packages, theme/style, manifest, localization skeleton (en/ar), `PROGRESS.md`, `docs/`.
- **AC:** builds & installs; appears in the "choose Home app" dialog.

### Phase 1 — Home host, screen profile, input probe, first themes
- `HomeActivity`, `ScreenProfile`, normalized-coordinate engine, `WatchFaceView`, manifest parser, **3 themes** (Flow, Minimal Digital, Classic Analog), battery-chip complication, `InputProbeActivity`.
- **AC:** face renders correctly on all 6 test profiles; crown & buttons logged and documented in `docs/DEVICE_NOTES.md`.

### Phase 2 — Navigation, drawer, buttons
- Gesture router, page animations, app drawer (grid/list/honeycomb) + wheel zoom, Back/Home semantics, check firmware long-press-Home menu (fallback if broken).
- **AC:** all swipes work; wheel zoom works; Home/Back semantics as in Section 6.2; long-press-Home status documented.

### Phase 3 — Always-On Display
- Full Section 8.
- **AC:** Section 8.5.

### Phase 4 — Notifications, control center, widgets, lock/theater/DND
- Local `NotificationListenerService`, Notification Center UI (dismiss, clear all, open, inline reply), Control Center tiles, widgets page (steps, stopwatch, timer, alarm), theater/DND/water lock, flashlight, device-admin lock.
- **AC:** notifications from any app appear/dismiss; tiles toggle real system state where permitted; Theater/DND behave.

### Phase 5 — Chime, settings, face editor, backup
- Hourly chime with sound library + custom sounds, complete Settings screens, face editor (long-press), theme import (`.wtheme`), backup/restore, safe mode.
- **AC:** chime fires at exact hour (test by setting system time to xx:59:30), survives reboot; custom sound plays; backup round-trip works; crash-loop guard works.

### Phase 6 — Themes, performance, polish
- Remaining themes (12–15 total), procedural animations, frame-sequence player, FPS caps, memory trimming, battery saver, time-based theme switching, Arabic digits/Hijri/prayer times, test on all profiles, performance pass vs budgets in Section 1.
- **AC:** budgets met on the owner's device (report numbers in `PROGRESS.md`); Arabic UI verified screen-by-screen (screenshots in `docs/screens/`).

### Phase 7 — Link layer (launcher side) & docs
- BLE GATT server, framing/crypto, protocol handlers, mirrored notifications through the Notification Center, per-module toggles, **Link Simulator**, `docs/LINK_PROTOCOL.md`.
- **AC:** simulator can inject notifications/battery/theme install; all modules toggle on/off and stop work when off.

### Phase 8 — (Separate project) Phone companion app
- Pairing, notification forwarding, theme editor/library, sound manager, sync switches. Uses `docs/LINK_PROTOCOL.md` and the `.wtheme` schema.

---

## 18. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Weak SoC/RAM → jank | Procedural + frame-sequence animations, FPS caps, bitmap reuse, aggressive release on screen-off, performance budgets in CI checklist |
| Crown events unknown | `InputProbeActivity` first; configurable `InputMapper` |
| System blocks lock-screen / power actions | Device Admin `lockNow()`, Accessibility power dialog, root-optional extras; documented fallbacks |
| AOD changes brightness globally | Per-window brightness only; never write system settings |
| Firmware power menu breaks | Phase 2 verification + Section 11 fallback |
| Android 8 API gaps (no `AnimatedImageDrawable`, no `GLOBAL_ACTION_LOCK_SCREEN`, etc.) | Custom frame player; Device Admin; every API call checked against API 26 |
| Companion data privacy | Encrypted link, no cloud, opt-in per module |
| Theme files from unknown sources | Data-only format, strict validation, size limits, zip-slip protection |
| Launcher crash → unusable watch | Safe mode (Section 10.12) |

---

## 19. Definition of Done (v1.0)

- Launcher replaces stock home; all gestures, buttons and wheel behaviors in Section 6 work.
- ≥ 12 themes, ≥ 4 animated, user can import `.wtheme`; face editor works; clock position/style differs per theme.
- AOD per Section 8 (independent brightness, battery threshold with hysteresis, double-tap wake, pixel shift).
- Hourly chime with ≥ 8 bundled sounds + user sounds.
- Full Arabic + English UI with correct RTL, Arabic-Indic digits option, Hijri date, offline prayer times.
- All Section 10 extras implemented or explicitly marked "blocked by hardware/OS" with a documented fallback.
- Link layer + simulator + protocol doc ready for the future phone app.
- Performance budgets (Section 1) met on the real watch.
- `README.md`, `docs/INSTALL_AR.md`, `docs/INSTALL_EN.md`, `docs/DEVICE_NOTES.md`, `docs/LINK_PROTOCOL.md`, `PROGRESS.md` present and accurate.
