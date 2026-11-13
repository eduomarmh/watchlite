# Phone ⇄ Watch link protocol (v1)

For building the companion phone app later. The watch side is implemented in `link/LinkService.kt` and `link/LinkManager.kt`.

## Transport: Bluetooth LE GATT
The **watch is the GATT server** (peripheral, advertises the service). The **phone is the central**.

| | UUID | properties |
|---|---|---|
| Service | `7f1a0001-5c3e-4b8e-9a51-6c2b0e9d1a01` | |
| RX (phone → watch) | `7f1a0002-5c3e-4b8e-9a51-6c2b0e9d1a01` | write / write-no-response |
| TX (watch → phone) | `7f1a0003-5c3e-4b8e-9a51-6c2b0e9d1a01` | notify (enable CCCD `0x2902`) |

Request a large MTU (e.g. 247) after connecting.

### Chunking
Each BLE write/notification is: `[flags:1][msgId:2 BE][seq:2 BE][data…]`
flags bit0 = first chunk, bit1 = last chunk. Concatenate `data` of one `msgId` in order → one **frame**.

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
| phone→watch | `media.state` | `{title, artist, playing}` |
| watch→phone | `media.cmd` | `{cmd:"toggle"/"next"/"prev"}` |
| phone→watch | `theme.install` | chunks `{id, index, total, data(base64 of .wtheme)}`; watch replies `theme.installed {id}` or `theme.error {id,error}` |
| phone→watch | `theme.apply` | `{id}` |
| watch→phone | `find.phone` | `{}` (ring the phone) |
| watch→phone | `camera.remote` | `{}` (take a photo) |

Every feature has its own switch on the watch (Settings → Phone link → Features); messages for a disabled module are ignored.
The watch includes a **simulator** (same screen) to test without the phone app.
