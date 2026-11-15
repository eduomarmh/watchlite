# Phone ⇄ Watch link protocol (v1)

For building the companion phone app later. The watch side is implemented in `link/LinkService.kt` and `link/LinkManager.kt`.

## Transport: classic Bluetooth (RFCOMM / SPP)
BLE peripheral mode is missing on many budget watch chipsets, so the link uses classic Bluetooth, which every watch
and every phone supports (no Google services involved).

- The **watch** runs an RFCOMM server: service name `WatchLite`, UUID `7f1a0001-5c3e-4b8e-9a51-6c2b0e9d1a01`.
- The **phone** pairs with the watch in the normal Bluetooth settings, then connects with
  `createRfcommSocketToServiceRecord(UUID)` and reconnects automatically (3 s → 60 s backoff).
- Stream framing: `[length: 4 bytes big-endian][frame]`.

### Encryption (frame)
`frame = nonce(12 bytes) || AES-128-GCM(ciphertext + 16-byte tag)`
key = first 16 bytes of `SHA-256("watchlite-v1:" + pairingCode)`. The 6-digit pairing code is shown on the watch
(Watch Settings → Phone link). Frames that fail authentication are dropped.

### Message (decrypted frame = UTF-8 JSON)
```json
{ "v": 1, "id": "uuid", "type": "notif.post", "ts": 1731500000, "payload": { } }
```

## Messages
| direction | type | payload |
|---|---|---|
| phone→watch | `hello` | `{}`; watch answers `capabilities` `{modules:[…], app, version}` |
| both | `ping` / `pong` | `{}` |
| phone→watch | `notif.post` / `notif.update` | `{key, pkg, app, title, text, time, icon(base64 PNG ≤48px), actions:[{id,title,reply:bool}]}` |
| phone→watch | `notif.remove` | `{key}` |
| watch→phone | `notif.dismiss` | `{key}` |
| watch→phone | `notif.action` | `{key, action}` (`action:"open"` = open on phone) |
| watch→phone | `notif.reply` | `{key, action, text}` |
| phone→watch | `battery.phone` | `{level, charging}` |
| phone→watch | `net.state` | `{type:"wifi"/"mobile"/"none"}` |
| phone→watch | `location.state` | `{lat, lng}` |
| phone→watch | `contacts.sync` | `{contacts:[{name, numbers:[…]}]}` |
| phone→watch | `weather.update` | `{temp:"24°", desc:"Sunny"}` |
| phone→watch | `media.state` | `{title, artist, playing, art?}`; `art` = base64 JPEG cover (~104 px) sent only when the track changes, "" = no cover |
| watch→phone | `media.cmd` | `{cmd:"toggle"/"next"/"prev"}` |
| phone→watch | `theme.install` | chunks `{id, index, total, apply, data(base64 of .wtheme)}` (48 KB each; `apply` selects it after install); watch replies `theme.installed {id}` or `theme.error {id,error}` |
| phone→watch | `theme.apply` | `{id}` |
| phone→watch | `theme.thumbs` | `{have:{id:rev}}`; watch streams `theme.thumb {id, rev, name, builtIn, data(jpeg base64)}` for faces whose preview changed, then `theme.thumbs.done {ids, current}` |
| both ways | `file.start` / `file.chunk` / `file.end` | `{fid, name, size}` → `{fid, i, data(base64, 32 KB)}` ×n → `{fid, n}`; receiver answers `file.done {fid, ok}`; either side may send `file.cancel {fid}`. Max 200 MB. Watch saves to Download/WatchLite, phone to Download/WatchLite (MediaStore). |
| phone→watch | `theme.delete` | `{id}` (user faces only) |
| watch→phone | `theme.progress` | `{id, index, total}` while a `theme.install` is received |
| watch→phone | `find.phone` | `{}` (ring the phone) |
| watch→phone | `camera.remote` | `{}` (take a photo) |
| phone→watch | `stats.request` | `{}`; watch answers `watch.stats` |
| watch→phone | `watch.stats` | `{battery, charging, steps, goal, stepsSource, theme, aod, chime, version, model, android, uptimeMin, ctl}`; `ctl` = current control values + capabilities (`canDnd`, `canBrightness`, `canLock`), plus `themes:[{id,name}]` only in the answer to `stats.request` |
| phone→watch | `ctl.set` | `{k, v}` with k = `wifi`, `dnd`, `aod`, `chime`, `notifBanner`, `notifVibrate` (bool) · `brightness`, `aodBrightness`, `volMedia`, `volRing`, `volAlarm` (0–100) · `ringer` (`normal`/`vibrate`/`silent`) · `goal` (int) · `theme` (id) · `lock`, `wake` (no v). Watch answers with fresh `watch.stats`. Bluetooth is not switchable remotely (it would cut the link). |
| phone→watch | `watch.locate` | `{}`; watch answers `watch.location` (last fix now, fresh GPS fix when ready) |
| watch→phone | `watch.location` | `{lat, lng, acc, time, fresh, provider}` or `{error: "permission"/"gps_off"}` |
| phone→watch | `find.watch` / `find.watch.stop` | watch rings and vibrates up to 30 s (touching the watch stops it) |

Every feature has its own switch on the watch (Settings → Phone link → Features); messages for a disabled module are ignored.
The watch includes a **simulator** (same screen) to test without the phone app.
