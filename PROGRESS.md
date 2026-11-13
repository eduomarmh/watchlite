# Progress

| Phase | Status |
|---|---|
| 0 Scaffold (Gradle, manifest, en/ar strings, CI) | done |
| 1 Home, screen profile, theme engine, input probe | done |
| 2 Gestures, pages, drawer (honeycomb/grid/list + wheel zoom), Back/Home | done |
| 3 Always-on display (own brightness, battery hysteresis, double-tap/raise wake, pixel shift, night off) | done |
| 4 Notifications (dismiss, actions, reply), Control Center, widgets, theater/DND/touch & water lock, flashlight | done |
| 5 Hourly chime, full settings, face editor, theme import + photo themes, backup/restore, safe mode | done |
| 6 27 themes (13 animated/overlay), battery saver, auto themes, Arabic digits, Hijri, prayer times | done; performance numbers come from CI (`emulator-results/themes/timings.txt`, `meminfo.txt`, `gfxinfo.txt`) |
| 7 BLE link layer + encryption + protocol doc + simulator | done (watch side) |
| 8 Phone companion app | not started (separate project; see docs/LINK_PROTOCOL.md) |

## To verify on the real watch
- Crown event type (Buttons & wheel test) → note in docs/DEVICE_NOTES.md
- Firmware Home long-press menu still works (owner says yes)
- Real RAM/CPU numbers (Settings → About shows app memory)
